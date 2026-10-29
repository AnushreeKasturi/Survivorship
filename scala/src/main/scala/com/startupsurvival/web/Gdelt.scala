package com.startupsurvival.web

import java.io.{BufferedReader, InputStreamReader}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipInputStream
import scala.util.Try
import com.startupsurvival.BatchNewsCollector

/**
 * GDELT 2.0 Global Knowledge Graph, read from its free bulk files: one zip
 * every 15 minutes with ~1,500 articles from news sites worldwide, including
 * the small local outlets that cover small and mid-sized companies. The DOC
 * search API rate-limits shared hosts hard, so the bulk files are used instead.
 */
object Gdelt {

  final case class Article(date: String, title: String, source: String, url: String)

  private val Stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
  private val Title = "<PAGE_TITLE>(.*?)</PAGE_TITLE>".r

  /** Files are published on quarter-hour boundaries. */
  def slot(t: Instant): Instant = {
    val minute = t.truncatedTo(ChronoUnit.MINUTES)
    minute.minus((minute.atZone(ZoneOffset.UTC).getMinute % 15).toLong, ChronoUnit.MINUTES)
  }

  def fileUrl(slot: Instant): String = s"https://data.gdeltproject.org/gdeltv2/${Stamp.format(slot)}.gkg.csv.zip"

  /** One GKG 2.1 row: DATE is column 1, the source domain 3, the URL 4, and the page title sits in the extras (26). */
  def parseLine(line: String): Option[Article] = {
    val c = line.split("\t", -1)
    if (c.length < 27) None
    else Title.findFirstMatchIn(c(26)).map(m => BatchNewsCollector.clean(m.group(1))).filter(_.nonEmpty).map { title =>
      val d = c(1)
      Article(if (d.length >= 8) s"${d.take(4)}-${d.slice(4, 6)}-${d.slice(6, 8)}" else "", title, c(3), c(4))
    }
  }

  /** Streams one file's articles to `f`. Success(false) means the file isn't published (yet). */
  def scan(slot: Instant, timeoutMs: Int = 30000)(f: Article => Unit): Try[Boolean] = Try {
    val conn = new URI(fileUrl(slot)).toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(timeoutMs)
    conn.setReadTimeout(timeoutMs)
    try {
      if (conn.getResponseCode == 404) false
      else {
        val zip = new ZipInputStream(conn.getInputStream)
        try {
          if (zip.getNextEntry != null) {
            val reader = new BufferedReader(new InputStreamReader(zip, UTF_8))
            Iterator.continually(reader.readLine()).takeWhile(_ != null).flatMap(parseLine).foreach(f)
          }
          true
        } finally zip.close()
      }
    } finally conn.disconnect()
  }
}
