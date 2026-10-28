package com.startupsurvival

import java.io.{File, FileWriter, PrintWriter}
import java.time.LocalDate
import scala.io.Source
import scala.util.{Failure, Random, Success, Try}

/**
 * PHASE 13b - Batch News Collection
 *
 * Phase 7's NewsCollector was a manual spike over a handful of names;
 * Phase 13 left batch collection as "the genuinely necessary next step",
 * and without it only 3 of 22,075 startups had any sentiment features,
 * so Experiments 3/4 could not test the research question at all. This
 * object runs NewsCollector's verified URL builder, fetcher, RSS parser
 * and cutoff filter over the eligible population.
 *
 * Design decisions:
 *
 *  - BALANCED RANDOM ORDER, FIXED SEED (see collectionOrder). The set
 *    collected by ANY stopping point is a balanced random sample of
 *    closed and operating startups, so the run can be stopped at whatever
 *    size time allows without biasing the cohort (unlike, say,
 *    alphabetical or best-funded-first order).
 *
 *  - CHECKPOINTING. Every startup's outcome is appended and flushed to
 *    collection_log.tsv immediately; on restart, startups already logged
 *    as "ok" are skipped. A crash or a network block loses at most one
 *    request.
 *
 *  - FAILED != ZERO COVERAGE. A startup whose request failed is logged
 *    as "failed" and retried on the next run. It is never silently
 *    treated as "no news found" - that would add false zero-coverage
 *    rows to the experiment cohort.
 *
 *  - RAW STORAGE. Headlines are stored BEFORE name matching (raw_news.tsv),
 *    so NameMatcher can be changed and re-applied in Spark without
 *    re-collecting. Only items dated strictly before the cutoff are kept
 *    (Phase 2 R4), re-checked again at aggregation time (Phase 10).
 *
 *  - POLITENESS. One request per `delayMs` (default 6000 ms) plus jitter,
 *    with 1/5/10-minute cool-downs on failure; the run stops itself after
 *    MaxConsecutiveFailures startups in a row fail even after cool-downs.
 *
 * Usage:
 *   sbt "runMain com.startupsurvival.BatchNewsCollector \
 *        ../data/processed/labeled_eligible_startups.csv ../data/news [maxStartups] [delayMs]"
 */
object BatchNewsCollector {

  val Cutoff: LocalDate = LocalDate.of(2013, 1, 1)
  val Seed: Long = 2013L

  /** Tabs/newlines would break the TSV; basic HTML entities appear in
   * Google News titles. */
  def clean(s: String): String =
    s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
      .replace("&lt;", "<").replace("&gt;", ">")
      .replaceAll("[\\t\\r\\n]+", " ").trim

  /**
   * BALANCED CASE-CONTROL ORDER. The first full-population run was
   * rate-limited by Google News (HTTP 503) after ~175 requests, which puts
   * all 22,075 startups out of reach. Given a fixed request budget, a
   * random sample would contain only ~13% closed startups; instead closed
   * and operating startups are each shuffled (fixed seed, permalink-sorted
   * first so the order is reproducible) and interleaved, so every prefix
   * of the order is a balanced 1:1 random sample of each class. AUC-ROC -
   * the primary comparison metric - does not depend on class prevalence,
   * so this maximises statistical power per request without biasing it.
   * Once the 2,902 closed startups run out, operating ones continue.
   */
  def collectionOrder(startups: List[models.StartupRecord]): List[models.StartupRecord] = {
    val rnd = new Random(Seed)
    val (closed, operating) = startups.sortBy(_.permalink).partition(_.label == 0)
    val c = rnd.shuffle(closed)
    val o = rnd.shuffle(operating)
    c.zip(o).flatMap { case (a, b) => List(a, b) } ++ o.drop(c.length)
  }

  /** Permalinks already collected successfully in a previous run. */
  def alreadyCollected(logFile: File): Set[String] =
    if (!logFile.exists()) Set.empty
    else {
      val src = Source.fromFile(logFile, "UTF-8")
      try src.getLines().map(_.split("\t", -1)).collect {
        case Array(permalink, "ok", _*) => permalink
      }.toSet
      finally src.close()
    }

  /** Cool-down schedule after a failed request. Google News answers a
   * burst of requests with HTTP 503 for a few minutes, so short retries
   * just extend the block; these waits let it clear. */
  val CoolDownsMs: List[Long] = List(60000L, 300000L, 600000L)

  /** Observed in the balanced run: ~100 requests succeed, then the block
   * can outlast all three cool-downs (16 minutes). Each failed startup
   * already costs those 16 minutes, so the run tolerates several in a row
   * (about 3 hours of blocking) before giving up. */
  val MaxConsecutiveFailures = 12

  def fetchWithRetry(url: String, coolDowns: List[Long] = CoolDownsMs): Try[String] =
    NewsCollector.fetchUrl(url) match {
      case ok @ Success(_) => ok
      case Failure(e) if coolDowns.nonEmpty =>
        println(s"  [cool-down ${coolDowns.head / 1000}s] ${e.getMessage.take(60)}")
        Thread.sleep(coolDowns.head)
        fetchWithRetry(url, coolDowns.tail)
      case failed => failed
    }

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("Usage: BatchNewsCollector <eligible.csv> <outDir> [maxStartups] [delayMs]")
      sys.exit(1)
    }
    val inputCsv = args(0)
    val outDir = new File(args(1))
    val maxStartups = if (args.length > 2) args(2).toInt else Int.MaxValue
    val delayMs = if (args.length > 3) args(3).toLong else 6000L
    outDir.mkdirs()

    val logFile = new File(outDir, "collection_log.tsv")
    val newsFile = new File(outDir, "raw_news.tsv")
    val done = alreadyCollected(logFile)

    val order = collectionOrder(DataCleaner.loadStartups(inputCsv)).take(maxStartups)
    val todo = order.filterNot(s => done.contains(s.permalink))
    println(s"Eligible startups in collection order: ${order.length}; " +
      s"already collected: ${done.size}; to collect now: ${todo.length}")

    val log = new PrintWriter(new FileWriter(logFile, true))
    val news = new PrintWriter(new FileWriter(newsFile, true))
    var consecutiveFailures = 0
    var processed = 0
    try {
      val it = todo.iterator
      while (it.hasNext && consecutiveFailures < MaxConsecutiveFailures) {
        val startup = it.next()
        val url = NewsCollector.buildRssUrl(startup.name, Cutoff)
        fetchWithRetry(url) match {
          case Success(xml) =>
            consecutiveFailures = 0
            val items = NewsCollector.parseRssItems(xml).flatMap { item =>
              NewsCollector.parsePubDate(item.pubDateRaw)
                .filter(_.isBefore(Cutoff))
                .map(date => (date, item))
            }
            items.foreach { case (date, item) =>
              news.println(Seq(startup.permalink, clean(startup.name), date.toString,
                clean(item.source.getOrElse("")), clean(item.title)).mkString("\t"))
            }
            log.println(Seq(startup.permalink, "ok", items.length, LocalDate.now()).mkString("\t"))
          case Failure(e) =>
            consecutiveFailures += 1
            log.println(Seq(startup.permalink, "failed", 0, LocalDate.now()).mkString("\t"))
            println(s"  [WARN] ${startup.name}: ${e.getMessage}")
        }
        news.flush(); log.flush()
        processed += 1
        if (processed % 100 == 0) println(s"  progress: $processed / ${todo.length}")
        Thread.sleep(delayMs + Random.nextInt(400))
      }
    } finally {
      log.close(); news.close()
    }
    if (consecutiveFailures >= MaxConsecutiveFailures)
      println(s"Stopped after $MaxConsecutiveFailures consecutive failures - re-run later to resume.")
    println(s"Processed $processed startups this run.")
  }
}
