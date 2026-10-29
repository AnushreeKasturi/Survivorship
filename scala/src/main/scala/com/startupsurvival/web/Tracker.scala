package com.startupsurvival.web

import java.io.{File, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.time.{Instant, LocalDate}
import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import scala.collection.JavaConverters._
import scala.util.Try
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.{write, writePretty}
import com.startupsurvival.{NameMatcher, NewsCollector, NewsRelevance}
import com.startupsurvival.spark.NewsRDD
import Server.{Headline, Models, PredictRequest}

/**
 * Runs one startup profile through the real pipeline as of a date: Google News
 * for the two years before it (the training window), NameMatcher, the
 * sentiment features and the leak-free models.
 *
 *   sbt "runMain com.startupsurvival.web.Tracker"   # re-scores startups/case_studies.json
 */
object Tracker {

  private implicit val formats: Formats = DefaultFormats

  final case class Profile(
      name: String,
      foundedAt: String,
      firstFundingAt: String,
      categories: List[String],
      country: Option[String],
      outcome: Option[String] = None,
      outcomeDate: Option[String] = None,
      note: Option[String] = None,
      sources: List[String] = Nil,
      keywords: List[String] = Nil // a founder or product, used to scope searches for common-word names
  )

  private val NamePattern = """^[\p{L}\p{N} .&'+-]{2,60}$""".r

  def validate(p: Profile): Unit = {
    require(NamePattern.findFirstIn(p.name.trim).isDefined, "name must be 2-60 letters, digits, spaces or .&'+-")
    require(p.country.forall(_.matches("[A-Za-z]{0,3}")), "country must be a 3-letter code")
    require(p.categories.length <= 10 && p.categories.forall(c => c.length <= 40 && !c.contains('|')), "at most 10 categories of 40 characters")
    require(p.keywords.length <= 3 && p.keywords.forall(k => NamePattern.findFirstIn(k.trim).isDefined), "at most 3 keywords of 2-60 letters, digits or spaces")
  }

  def key(name: String): String = NameMatcher.normalize(name)

  /** Name-match confidence; profiles with keywords use the common-word rule. */
  def matchConfidence(p: Profile, text: String): Double =
    (if (p.keywords.nonEmpty) NameMatcher.confirmCommonWord(p.name, text) else NameMatcher.computeMatchConfidence(p.name, text)).confidence

  def loadProfiles(path: String): List[Profile] =
    parse(new String(Files.readAllBytes(Paths.get(path)), UTF_8)).extract[List[Profile]]

  /** Scores a profile as of `asOf` from Google News plus any archived GDELT articles in the same two-year window. */
  def check(p: Profile, asOf: LocalDate, models: Models, timeoutMs: Int, bounds: Server.Maturity = Server.Maturity.Design,
      archive: Option[NewsArchive] = None): Map[String, Any] = {
    val fetched = NewsCollector.fetchUrl(NewsCollector.buildRssUrl(p.name, asOf, p.keywords), timeoutMs)
    val windowStart = asOf.minusMonths(24)
    val google = fetched.toOption.toList.flatMap(NewsCollector.parseRssItems).flatMap { item =>
      NewsCollector.parsePubDate(item.pubDateRaw)
        .filter(d => d.isBefore(asOf) && !d.isBefore(windowStart))
        .map(d => (d, NewsRDD.stripSourceSuffix(item.title, item.source.getOrElse("")), item.source.getOrElse("")))
    }
    val gdelt = archive.toList.flatMap(_.articlesFor(p, windowStart, asOf)).map(a => (LocalDate.parse(a.date), a.title, a.source))
    val raw = google ++ gdelt
    val named = raw
      .filter { case (_, text, _) => matchConfidence(p, text) >= NameMatcher.AcceptanceThreshold }
      .groupBy(_._2.toLowerCase).values.map(_.minBy(_._1.toEpochDay)) // Google repeats titles across outlets
      .toList.sortBy(_._1.toEpochDay)
    // Naming the company isn't enough: only business news about it counts (NewsRelevance).
    val judged = named.map { case h @ (_, text, source) => (h, NewsRelevance.judge(text, source)) }
    val accepted = judged.collect { case ((d, text, source), v) if v.keep => Headline(d.toString, text, Some(source).filter(_.nonEmpty)) }
      .takeRight(50)
    val setAside = judged.filterNot(_._2.keep)

    val prediction = Server.predict(
      PredictRequest(p.foundedAt, p.firstFundingAt, Some(p.categories), p.country, Some(accepted)), models, asOf, bounds)
    prediction - "financial" ++ Map(
      "profile" -> p,
      "asOf" -> asOf.toString,
      "checkedAt" -> Instant.now().toString,
      "rawHeadlines" -> raw.size,
      "ambiguousName" -> NameMatcher.isAmbiguousName(p.name),
      "scopedSearch" -> p.keywords.nonEmpty,
      "sources" -> Map("googleNews" -> google.size, "gdelt" -> gdelt.size),
      "nameMatched" -> named.size,
      "setAside" -> setAside.groupBy(_._2.reason).map { case (reason, hs) =>
        Map("reason" -> reason, "count" -> hs.size, "examples" -> hs.takeRight(3).map(_._1._2)) }.toList.sortBy(-_("count").asInstanceOf[Int]),
      "newsError" -> fetched.failed.toOption.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}")
    )
  }

  /**
   * Case studies are scored two years after their first round, or six months
   * before they shut down if that is sooner: young enough that the outcome was
   * still open, mirroring training's 2013 cutoff with 2011-2012 news.
   */
  def caseStudyAsOf(p: Profile): LocalDate = {
    val twoYearsIn = LocalDate.parse(p.firstFundingAt).plusYears(2)
    p.outcome.filter(_ == "closed").flatMap(_ => p.outcomeDate)
      .map(d => LocalDate.parse(d).minusMonths(6))
      .filter(_.isBefore(twoYearsIn))
      .getOrElse(twoYearsIn)
  }

  final case class Point(checkedAt: String, financialOnly: Double, financialSentiment: Option[Double], headlines: Int)
  final case class Queued(profile: Profile, queuedAt: String)

  /**
   * The live watchlist: curated startups plus visitor suggestions. Visitors
   * suggest a startup, it waits in a queue, and the next run scores it and
   * moves it into the archive; every score is kept as history. GDELT is read
   * into the news archive before each scheduled run.
   */
  final class Watch(models: Models, curated: List[Profile], bounds: Server.Maturity, store: Option[Store],
      archive: NewsArchive, periodHours: Int = 6) {
    // Keys are namespaced: the key-value store is shared with another project.
    private val VisitorsKey = "startup-survival:watchlist:visitors"
    private val QueueKey = "startup-survival:watchlist:queue"
    private val HistoryKey = "startup-survival:watchlist:history"
    private val visitors = new ConcurrentHashMap[String, Profile]()
    private val visitorOrder = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    private val queue = new java.util.concurrent.ConcurrentLinkedQueue[Queued]()
    private val results = new ConcurrentHashMap[String, Map[String, Any]]()
    private val history = new ConcurrentHashMap[String, Vector[Point]]()
    private val lastSubmit = new ConcurrentHashMap[String, java.lang.Long]()
    @volatile private var lastRun: Option[Instant] = None
    @volatile private var running = false
    private val MaxVisitors = 50
    private val MaxQueue = 25
    private val MaxHistory = 120 // a month of 6-hourly checks
    private val SubmitGapMs = 20000L
    private val GlobalGapMs = 3000L // across all clients, in case client identity is spoofed
    private val lastAnySubmit = new java.util.concurrent.atomic.AtomicLong(0L)
    private val jobLock = new Object // background jobs only; request handlers never wait on news I/O
    // Guards every change to visitors, queue and history, and the save, so concurrent
    // writers can't interleave. Slow news I/O always happens outside it.
    private val stateLock = new Object

    private def save(): Unit = stateLock.synchronized { store.foreach { kv =>
      Try {
        kv.set(VisitorsKey, write(visitorOrder.asScala.toList.flatMap(k => Option(visitors.get(k)))))
        kv.set(QueueKey, write(queue.asScala.toList))
        kv.set(HistoryKey, write(history.asScala.toMap))
      }.failed.foreach(e => println(s"[WARN] could not save the watchlist: ${e.getMessage}"))
    } }

    private def restore(): Unit = store.foreach { kv =>
      Try {
        kv.get(VisitorsKey).toList.flatMap(j => parse(j).extract[List[Profile]]).takeRight(MaxVisitors)
          .foreach { p => if (visitors.put(key(p.name), p) == null) visitorOrder.add(key(p.name)) }
        kv.get(QueueKey).toList.flatMap(j => parse(j).extract[List[Queued]]).foreach(queue.add)
        kv.get(HistoryKey).foreach(j => parse(j).extract[Map[String, List[Point]]].foreach { case (k, v) => history.put(k, v.toVector) })
        println(s"Restored ${visitors.size} visitor startups and ${queue.size} queued from the key-value store")
      }.failed.foreach(e => println(s"[WARN] key-value store unavailable, starting empty: ${e.getMessage}"))
    }

    /** Scores one profile now; keeps the result and appends it to that startup's history. */
    private def runOne(p: Profile, timeoutMs: Int): Map[String, Any] = {
      val r = Try(check(p, LocalDate.now(), models, timeoutMs, bounds, Some(archive)))
        .getOrElse(Map("profile" -> p, "error" -> "could not score this profile"))
      results.put(key(p.name), r)
      if (!r.contains("error")) {
        val point = Point(r("checkedAt").toString, r("financialOnly").asInstanceOf[Double],
          r("financialSentiment").asInstanceOf[Option[Double]], r("headlines").asInstanceOf[List[_]].size)
        stateLock.synchronized {
          history.put(key(p.name), (history.getOrDefault(key(p.name), Vector.empty) :+ point).takeRight(MaxHistory))
        }
      }
      r
    }

    /** Moves queued suggestions into the archive by scoring them. */
    private def evictOldVisitors(): Unit =
      while (visitors.size > MaxVisitors) Option(visitorOrder.poll()).foreach { o =>
        visitors.remove(o); results.remove(o); history.remove(o); archive.forget(o)
      }

    def drainQueue(): Unit = jobLock.synchronized {
      if (!queue.isEmpty) println(s"Scoring ${queue.size} queued suggestion(s)")
      // Peek, score outside the lock, then move it out of the queue: a suggestion is never lost mid-score.
      Iterator.continually(stateLock.synchronized(Option(queue.peek()))).takeWhile(_.isDefined).map(_.get).foreach { q =>
        runOne(q.profile, 8000)
        stateLock.synchronized {
          queue.remove(q)
          if (visitors.put(key(q.profile.name), q.profile) == null) visitorOrder.add(key(q.profile.name))
          evictOldVisitors()
          save()
        }
        Thread.sleep(1000)
      }
    }

    def refresh(): Unit = jobLock.synchronized {
      running = true
      try {
        val tracked = curated ++ visitors.values.asScala ++ queue.asScala.map(_.profile)
        Try(archive.ingest(tracked)).failed.foreach(e => println(s"[WARN] GDELT ingest failed: ${e.getMessage}"))
        drainQueue()
        (curated ++ visitors.values.asScala).foreach { p => runOne(p, 8000); Thread.sleep(1000) } // gentle on Google News
        save()
      } finally { running = false; lastRun = Some(Instant.now()) }
    }

    def start(): Unit = {
      restore()
      archive.restore()
      val scheduler = Executors.newScheduledThreadPool(2)
      scheduler.scheduleAtFixedRate(() => Try(refresh()), 0, periodHours.toLong, TimeUnit.HOURS)
      // Suggestions shouldn't wait six hours while the server is awake.
      val every = sys.env.get("QUEUE_MINUTES").flatMap(m => Try(m.toLong).toOption).getOrElse(10L)
      scheduler.scheduleAtFixedRate(() => Try(drainQueue()).failed.foreach(e => println(s"[WARN] queue: $e")), every, every, TimeUnit.MINUTES)
    }

    def snapshot: Map[String, Any] = {
      def withHistory(ps: Seq[Profile]) = ps.flatMap(p => Option(results.get(key(p.name))).map(_ + ("history" -> history.getOrDefault(key(p.name), Vector.empty))))
      Map(
        "lastRun" -> lastRun.map(_.toString),
        "nextRun" -> lastRun.map(_.plusSeconds(periodHours * 3600L).toString),
        "running" -> running,
        "periodHours" -> periodHours,
        "threshold" -> models.threshold,
        "model" -> models.structuralName,
        "curated" -> withHistory(curated),
        "visitors" -> withHistory(visitorOrder.asScala.toList.reverse.flatMap(k => Option(visitors.get(k)))),
        "queue" -> queue.asScala.toList,
        "news" -> archive.stats
      )
    }

    /** Queues a visitor's suggestion; the next run scores it. */
    def suggest(p: Profile, client: String): Map[String, Any] = {
      validate(p)
      val profile = p.copy(name = p.name.trim, outcome = None, outcomeDate = None, note = None, sources = Nil)
      // Dates are checked the way scoring will check them, so a bad one is a 400 now, not a failure later.
      Server.predict(PredictRequest(profile.foundedAt, profile.firstFundingAt, Some(profile.categories), profile.country, None), models)
      val now = System.currentTimeMillis()
      lastSubmit.entrySet.removeIf(_.getValue < now - SubmitGapMs) // keeps the map to recent clients only
      val last = Option(lastSubmit.put(client.take(64), now)).map(_.longValue).getOrElse(0L)
      require(now - last > SubmitGapMs, "one startup every 20 seconds, please try again shortly")
      val previous = lastAnySubmit.get()
      require(now - previous > GlobalGapMs && lastAnySubmit.compareAndSet(previous, now),
        "another suggestion just arrived, please try again in a few seconds")
      val k = key(profile.name)
      val position = stateLock.synchronized { // check and add atomically
        require(curated.forall(c => key(c.name) != k) && !visitors.containsKey(k) && queue.asScala.forall(q => key(q.profile.name) != k),
          s"${profile.name} is already in the archive")
        require(queue.size < MaxQueue, "the queue is full, please try again after the next check")
        queue.add(Queued(profile, Instant.now().toString))
        save()
        queue.size
      }
      Map("queued" -> true, "position" -> position, "profile" -> profile)
    }
  }

  def main(args: Array[String]): Unit = {
    val models = Server.loadModels(args.lift(0).getOrElse("../results/model_coefficients_leakfree.json"), Some("../results/model_structural.json"))
    val cases = loadProfiles(args.lift(1).getOrElse("../startups/case_studies.json"))
    val scored = cases.map { p =>
      val r = check(p, caseStudyAsOf(p), models, 10000)
      Thread.sleep(1500)
      val p0 = r("financialOnly").asInstanceOf[Double]
      val correct = (p0 >= models.threshold) == p.outcome.contains("operating")
      println(f"${p.name}%-10s as of ${r("asOf")}  P(operating)=$p0%.3f  actual=${p.outcome.getOrElse("?")}%-9s " +
        f"${if (correct) "hit" else "miss"}  news ${r("rawHeadlines")} raw / ${r("headlines").asInstanceOf[List[_]].size} matched")
      r + ("correct" -> correct)
    }
    val out = new File(args.lift(2).getOrElse("../results/case_studies.json"))
    val w = new PrintWriter(out, "UTF-8")
    try w.println(writePretty(Map("generatedAt" -> Instant.now().toString, "threshold" -> models.threshold,
      "model" -> models.structuralName, "cases" -> scored)))
    finally w.close()
    println(s"${scored.count(_("correct") == true)} of ${scored.size} correct; wrote $out")
  }
}
