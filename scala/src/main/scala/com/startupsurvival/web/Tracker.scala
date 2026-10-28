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
import org.json4s.jackson.Serialization.writePretty
import com.startupsurvival.{NameMatcher, NewsCollector}
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

  def loadProfiles(path: String): List[Profile] =
    parse(new String(Files.readAllBytes(Paths.get(path)), UTF_8)).extract[List[Profile]]

  def check(p: Profile, asOf: LocalDate, models: Models, timeoutMs: Int, bounds: Server.Maturity = Server.Maturity.Design): Map[String, Any] = {
    val fetched = NewsCollector.fetchUrl(NewsCollector.buildRssUrl(p.name, asOf, p.keywords), timeoutMs)
    val scoped = p.keywords.nonEmpty
    def confidence(text: String) =
      (if (scoped) NameMatcher.confirmCommonWord(p.name, text) else NameMatcher.computeMatchConfidence(p.name, text)).confidence
    val windowStart = asOf.minusMonths(24)
    val raw = fetched.toOption.toList.flatMap(NewsCollector.parseRssItems).flatMap { item =>
      NewsCollector.parsePubDate(item.pubDateRaw)
        .filter(d => d.isBefore(asOf) && !d.isBefore(windowStart))
        .map(d => (d, NewsRDD.stripSourceSuffix(item.title, item.source.getOrElse(""))))
    }
    val accepted = raw
      .filter { case (_, text) => confidence(text) >= NameMatcher.AcceptanceThreshold }
      .groupBy(_._2.toLowerCase).values.map(_.minBy(_._1.toEpochDay)) // Google repeats titles across outlets
      .toList.sortBy(_._1.toEpochDay)
      .takeRight(50)
      .map { case (d, text) => Headline(d.toString, text) }

    val prediction = Server.predict(
      PredictRequest(p.foundedAt, p.firstFundingAt, Some(p.categories), p.country, Some(accepted)), models, asOf, bounds)
    prediction - "financial" ++ Map(
      "profile" -> p,
      "asOf" -> asOf.toString,
      "checkedAt" -> Instant.now().toString,
      "rawHeadlines" -> raw.size,
      "ambiguousName" -> NameMatcher.isAmbiguousName(p.name),
      "scopedSearch" -> scoped,
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

  /** The live watchlist: curated startups plus visitor-added ones, re-checked on a schedule. */
  final class Watch(models: Models, curated: List[Profile], bounds: Server.Maturity, store: Option[Store], periodHours: Int = 6) {
    // Visitor profiles persist in the key-value store when KV_URL is set; their scores are recomputed on each run.
    private val StoreKey = "startup-survival:watchlist:visitors" // namespaced: the store is shared with another project
    private val visitors = new ConcurrentHashMap[String, Profile]()
    private val visitorOrder = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    private val results = new ConcurrentHashMap[String, Map[String, Any]]()
    private val lastSubmit = new ConcurrentHashMap[String, java.lang.Long]()
    @volatile private var lastRun: Option[Instant] = None
    @volatile private var running = false
    private val MaxVisitors = 25
    private val SubmitGapMs = 20000L
    private val GlobalGapMs = 3000L // across all clients, in case client identity is spoofed
    @volatile private var lastAnySubmit = 0L

    private def key(name: String) = NameMatcher.normalize(name)

    private def score(p: Profile, timeoutMs: Int): Map[String, Any] =
      Try(check(p, LocalDate.now(), models, timeoutMs, bounds))
        .getOrElse(Map("profile" -> p, "error" -> "could not score this profile"))

    private def runOne(p: Profile, timeoutMs: Int): Unit = results.put(key(p.name), score(p, timeoutMs))

    def refresh(): Unit = {
      running = true
      try { (curated ++ visitors.values.asScala).foreach { p => runOne(p, 8000); Thread.sleep(1000) } } // gentle on Google News
      finally { running = false; lastRun = Some(Instant.now()) }
    }

    private def save(): Unit = store.foreach { kv =>
      val ordered = visitorOrder.asScala.toList.flatMap(k => Option(visitors.get(k)))
      Try(kv.set(StoreKey, org.json4s.jackson.Serialization.write(ordered)))
        .failed.foreach(e => println(s"[WARN] could not save visitors: ${e.getMessage}"))
    }

    def start(): Unit = {
      store.foreach { kv =>
        Try(kv.get(StoreKey).toList.flatMap(json => parse(json).extract[List[Profile]])) match {
          case scala.util.Success(saved) =>
            saved.takeRight(MaxVisitors).foreach { p => visitors.put(key(p.name), p); visitorOrder.add(key(p.name)) }
            println(s"Restored ${saved.size} visitor startups from the key-value store")
          case scala.util.Failure(e) => println(s"[WARN] key-value store unavailable, visitors start empty: ${e.getMessage}")
        }
      }
      val scheduler = Executors.newSingleThreadScheduledExecutor()
      scheduler.scheduleAtFixedRate(() => Try(refresh()), 0, periodHours.toLong, TimeUnit.HOURS)
    }

    def snapshot: Map[String, Any] = {
      def ordered(ps: Seq[Profile]) = ps.flatMap(p => Option(results.get(key(p.name))))
      Map(
        "lastRun" -> lastRun.map(_.toString),
        "nextRun" -> lastRun.map(_.plusSeconds(periodHours * 3600L).toString),
        "running" -> running,
        "periodHours" -> periodHours,
        "curated" -> ordered(curated),
        "visitors" -> ordered(visitors.values.asScala.toSeq.sortBy(_.name.toLowerCase))
      )
    }

    /** Scores a visitor's startup now and adds it to the scheduled list. */
    def track(p: Profile, client: String): Map[String, Any] = {
      validate(p)
      val now = System.currentTimeMillis()
      lastSubmit.entrySet.removeIf(_.getValue < now - SubmitGapMs) // keeps the map to recent clients only
      val last = Option(lastSubmit.put(client.take(64), now)).map(_.longValue).getOrElse(0L)
      require(now - last > SubmitGapMs, "one startup every 20 seconds, please try again shortly")
      synchronized {
        require(now - lastAnySubmit > GlobalGapMs, "the server is busy scoring another startup, please try again in a few seconds")
        lastAnySubmit = now
      }
      val profile = p.copy(name = p.name.trim, outcome = None, outcomeDate = None, note = None, sources = Nil)
      val k = key(profile.name)
      require(curated.forall(c => key(c.name) != k), s"${profile.name} is already on the watchlist")
      // Bad dates throw here and reach the visitor as a 400; news failures are recorded in the result.
      val result = check(profile, LocalDate.now(), models, 4000, bounds) // under the server's 10s response limit
      if (visitors.put(k, profile) == null) visitorOrder.add(k)
      results.put(k, result)
      while (visitors.size > MaxVisitors) Option(visitorOrder.poll()).foreach { o => visitors.remove(o); results.remove(o) }
      save()
      result
    }
  }

  def main(args: Array[String]): Unit = {
    val models = Server.loadModels(args.lift(0).getOrElse("../results/model_coefficients_leakfree.json"))
    val cases = loadProfiles(args.lift(1).getOrElse("../startups/case_studies.json"))
    val scored = cases.map { p =>
      val r = check(p, caseStudyAsOf(p), models, 10000)
      Thread.sleep(1500)
      val p0 = r("financialOnly").asInstanceOf[Double]
      val correct = (p0 >= 0.5) == p.outcome.contains("operating")
      println(f"${p.name}%-10s as of ${r("asOf")}  P(operating)=$p0%.3f  actual=${p.outcome.getOrElse("?")}%-9s " +
        f"${if (correct) "hit" else "miss"}  news ${r("rawHeadlines")} raw / ${r("headlines").asInstanceOf[List[_]].size} matched")
      r + ("correct" -> correct)
    }
    val out = new File(args.lift(2).getOrElse("../results/case_studies.json"))
    val w = new PrintWriter(out, "UTF-8")
    try w.println(writePretty(Map("generatedAt" -> Instant.now().toString, "threshold" -> 0.5, "cases" -> scored)))
    finally w.close()
    println(s"${scored.count(_("correct") == true)} of ${scored.size} correct; wrote $out")
  }
}
