package com.startupsurvival

import java.net.{URI, URLEncoder}
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Locale
import scala.io.Source
import scala.util.{Failure, Success, Try}
import com.startupsurvival.models.StartupRecord

/**
 * PHASE 7 - Fetches candidate headlines for a startup from Google News RSS.
 *
 * NewsAPI/GNews free tiers cannot reach back to 2013, so Google News RSS is
 * the only free option; its archive depth varies per company (PHASE07 doc).
 * RSS items are pulled with scoped regexes rather than adding an XML
 * dependency - fine for Google's fixed RSS shape, not for general XML.
 */
object NewsCollector {

  private val PredictionCutoff = StartupRecord.PredictionCutoff
  private val LookbackMonths = 24

  final case class RawNewsItem(
      title: String,
      link: String,
      pubDateRaw: String,
      source: Option[String]
  )

  /** `before:`/`after:` are best-effort; collectFor re-filters by date. */
  def buildRssUrl(companyName: String, cutoff: LocalDate = PredictionCutoff, context: List[String] = Nil): String = {
    val windowStart = cutoff.minusMonths(LookbackMonths)
    // Context keywords (a founder, a product) scope searches for names that are ordinary words.
    val scope = if (context.isEmpty) "" else context.map(k => s""""$k"""").mkString(" (", " OR ", ")")
    val query = s""""$companyName"$scope before:${cutoff} after:${windowStart}"""
    val encoded = URLEncoder.encode(query, "UTF-8")
    s"https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en"
  }

  /** Browser-like UA (Google rejects Java's default) and a timeout so one hung request can't stall a run. */
  def fetchUrl(url: String, timeoutMs: Int = 10000): Try[String] = Try {
    val connection = new URI(url).toURL.openConnection()
    connection.setConnectTimeout(timeoutMs)
    connection.setReadTimeout(timeoutMs)
    connection.setRequestProperty(
      "User-Agent",
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )
    val source = Source.fromInputStream(connection.getInputStream, "UTF-8")
    try source.mkString
    finally source.close()
  }

  /** Non-greedy matches scoped to one <item>; CDATA wrappers stripped. */
  def parseRssItems(rssXml: String): List[RawNewsItem] = {
    val itemPattern = "(?s)<item>(.*?)</item>".r
    val titlePattern = "(?s)<title>(.*?)</title>".r
    val linkPattern = "(?s)<link>(.*?)</link>".r
    val pubDatePattern = "(?s)<pubDate>(.*?)</pubDate>".r
    val sourcePattern = "(?s)<source[^>]*>(.*?)</source>".r

    def stripCdata(s: String): String =
      s.trim
        .stripPrefix("<![CDATA[")
        .stripSuffix("]]>")
        .trim

    def firstMatch(pattern: scala.util.matching.Regex, in: String): Option[String] =
      pattern.findFirstMatchIn(in).map(m => stripCdata(m.group(1)))

    itemPattern
      .findAllMatchIn(rssXml)
      .map(_.group(1))
      .flatMap { itemBody =>
        for {
          title <- firstMatch(titlePattern, itemBody)
          link  <- firstMatch(linkPattern, itemBody)
        } yield RawNewsItem(
          title = title,
          link = link,
          pubDateRaw = firstMatch(pubDatePattern, itemBody).getOrElse(""),
          source = firstMatch(sourcePattern, itemBody)
        )
      }
      .toList
  }

  /** RSS pubDate is RFC-822 format, e.g. "Wed, 12 Jan 2011 05:00:00 GMT". */
  private val RssDateFormatter = DateTimeFormatter
    .ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)

  def parsePubDate(raw: String): Option[LocalDate] =
    Try {
      val parsed = RssDateFormatter.parse(raw.trim)
      LocalDate.of(
        parsed.get(ChronoField.YEAR),
        parsed.get(ChronoField.MONTH_OF_YEAR),
        parsed.get(ChronoField.DAY_OF_MONTH)
      )
    }.toOption

  /** Fetch, parse, and keep only items published before the cutoff. */
  def collectFor(
      companyName: String,
      cutoff: LocalDate = PredictionCutoff
  ): List[RawNewsItem] = {
    val url = buildRssUrl(companyName, cutoff)
    fetchUrl(url) match {
      case Success(xml) =>
        parseRssItems(xml).filter { item =>
          parsePubDate(item.pubDateRaw).exists(_.isBefore(cutoff))
        }
      case Failure(e) =>
        println(s"  [WARN] fetch failed for '$companyName': ${e.getMessage}")
        Nil
    }
  }

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      println("Usage: NewsCollector \"Company Name\" [\"Another Company\" ...]")
      println("This is a MANUAL FEASIBILITY SPIKE, not a batch collector.")
      println("Run against a handful of companies first - do not point this")
      println("at all 22,075 startups without reviewing docs/PHASE07_news_collection.md.")
      sys.exit(1)
    }
    args.foreach { name =>
      println(s"\n=== $name ===")
      println(s"URL: ${buildRssUrl(name)}")
      val items = collectFor(name)
      println(s"Pre-cutoff items found: ${items.length}")
      items.take(5).foreach { item =>
        println(s"  - [${item.pubDateRaw}] ${item.title}")
      }
    }
  }
}
