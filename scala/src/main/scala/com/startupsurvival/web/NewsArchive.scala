package com.startupsurvival.web

import java.time.{Duration, Instant, LocalDate}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.write
import com.startupsurvival.NameMatcher
import Tracker.Profile

/**
 * A growing archive of GDELT articles about the tracked startups. Each run
 * reads the 15-minute GKG files since the last cursor, keeps articles whose
 * title names a tracked startup (the same matching rules as Google News),
 * and persists them with the cursor in the key-value store.
 */
final class NewsArchive(store: Option[Store]) {
  private implicit val formats: Formats = DefaultFormats
  private val ArticlesKey = "startup-survival:gdelt:articles"
  private val CursorKey = "startup-survival:gdelt:cursor"
  private val PerStartup = 200
  private val articles = new ConcurrentHashMap[String, Vector[Gdelt.Article]]()
  @volatile private var cursor: Option[Instant] = None
  @volatile private var lastIngest: Map[String, Any] = Map.empty

  def restore(): Unit = store.foreach { kv =>
    Try {
      kv.get(ArticlesKey).foreach(j => parse(j).extract[Map[String, List[Gdelt.Article]]].foreach { case (k, v) => articles.put(k, v.toVector) })
      cursor = kv.get(CursorKey).map(Instant.parse)
    }.failed.foreach(e => println(s"[WARN] news archive not restored: ${e.getMessage}"))
  }

  private def save(): Unit = store.foreach { kv =>
    Try {
      kv.set(ArticlesKey, write(articles.asScala.toMap))
      cursor.foreach(c => kv.set(CursorKey, c.toString))
    }.failed.foreach(e => println(s"[WARN] news archive not saved: ${e.getMessage}"))
  }

  /**
   * Reads up to `maxFiles` quarter-hour files after the cursor (at most a day
   * back). A missing file under 6 hours old is not published yet, so the run
   * stops there and resumes from it next time; older gaps are skipped.
   */
  def ingest(profiles: Seq[Profile], now: Instant = Instant.now(), maxFiles: Int = 24): Unit = {
    val floor = Gdelt.slot(now.minus(Duration.ofHours(24)))
    var slot = Gdelt.slot(cursor.map(_.plus(Duration.ofMinutes(15))).filter(_.isAfter(floor)).getOrElse(now.minus(Duration.ofHours(6))))
    val lower = profiles.map(p => (Tracker.key(p.name), p, p.name.toLowerCase))
    var files, matched, published = 0
    var stop = false
    while (!stop && files < maxFiles && slot.isBefore(now)) {
      Gdelt.scan(slot) { a =>
        val title = a.title.toLowerCase
        lower.foreach { case (k, p, name) =>
          // cheap substring test first; the full matcher only runs on likely hits
          if (title.contains(name) && Tracker.matchConfidence(p, a.title) >= NameMatcher.AcceptanceThreshold) {
            val existing = articles.getOrDefault(k, Vector.empty)
            if (!existing.exists(e => e.url == a.url || e.title.equalsIgnoreCase(a.title))) {
              articles.put(k, (existing :+ a).takeRight(PerStartup))
              matched += 1
            }
          }
        }
      } match {
        case Success(true) => published += 1; cursor = Some(slot)
        case Success(false) if Duration.between(slot, now).toHours < 6 => stop = true
        case Success(false) => cursor = Some(slot) // a gap in GDELT's archive; move past it
        case Failure(e) => println(s"[WARN] GDELT ${Gdelt.fileUrl(slot)}: ${e.getMessage}"); stop = true
      }
      files += 1
      slot = slot.plus(Duration.ofMinutes(15))
    }
    lastIngest = Map("at" -> now.toString, "filesRead" -> published, "articlesMatched" -> matched)
    save()
  }

  /** Archived articles for a startup published inside [from, to). */
  def articlesFor(p: Profile, from: LocalDate, to: LocalDate): Seq[Gdelt.Article] =
    articles.getOrDefault(Tracker.key(p.name), Vector.empty).filter { a =>
      Try(LocalDate.parse(a.date)).toOption.exists(d => !d.isBefore(from) && d.isBefore(to))
    }

  def stats: Map[String, Any] = Map(
    "articles" -> articles.values.asScala.map(_.size).sum,
    "startups" -> articles.size,
    "cursor" -> cursor.map(_.toString),
    "lastIngest" -> lastIngest
  )
}
