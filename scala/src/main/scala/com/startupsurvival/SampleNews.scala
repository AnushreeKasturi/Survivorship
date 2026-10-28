package com.startupsurvival

import java.time.LocalDate
import com.startupsurvival.models.{NewsRecord, ScoredNewsRecord}

/**
 * Real headlines captured by Phase 7's live NewsCollector runs and accepted
 * by NameMatcher (Phase 8). Shared by the Phase 10 demo and the Phase 12-18
 * Spark pipeline, which uses it as its news input until batch collection runs.
 *
 * Sifteo is kept out of `joinable`: it was acquired in 2014, so Phase 2's R2
 * rule excludes it from labeled_eligible_startups.csv (see PHASE13 doc).
 */
object SampleNews {

  /** (permalink, headline, publication date) */
  val joinable: List[(String, String, String)] = List(
    ("/organization/kabbage", "Kabbage Raises Some Serious Cabbage for Small-Business Loans - WIRED", "2012-09-19"),
    ("/organization/kabbage", "Kabbage: The Merchant Cash Advance of the Online Business World - deBanked", "2011-08-23"),
    ("/organization/color-labs", "$41 million can't buy success as Color app finally gives up (update: Color denies shutdown) - The Verge", "2012-10-17"),
    ("/organization/color-labs", "A Mess Of Family Dynamics Alleged In Lawsuit Against Silicon Valley Entrepreneur And Color Founder Bill Nguyen - Forbes", "2012-11-20"),
    ("/organization/color-labs", "Apple to acquire troubled startup Color Labs? - Gadgets 360", "2012-10-18"),
    ("/organization/color-labs", "Exploring The \"Labs\" Trend in Consumer Startups - TechCrunch", "2011-12-04"),
    ("/organization/pinterest", "Ben Silbermann On How Pinterest Slowly Grew To Massive Scale - Forbes", "2012-10-22"),
    ("/organization/pinterest", "INSIDE PINTEREST: An Overnight Success Four Years In The Making - Business Insider", "2012-05-01"),
    ("/organization/pinterest", "42 Awesome and Creative Pinterest Boards - matadornetwork.com", "2012-01-31"),
    ("/organization/pinterest", "Pinterest, Tumblr and the Trouble With ‘Curation’ (Published 2012) - The New York Times", "2012-07-20"),
    ("/organization/pinterest", "The Pinterest Pivot - Fast Company", "2012-10-23")
  )

  val sifteo: List[(String, String, String)] = List(
    ("/organization/sifteo", "Sifteo Cubes Are Out Today, And Even Better Than You Imagined - Fast Company", "2011-08-11"),
    ("/organization/sifteo", "Tactile Digital Play, Part 1: Sifteo Cubes and Hasbro Zapped Toys - WIRED", "2012-10-27"),
    ("/organization/sifteo", "First Look: New Sifteo Cubes Go Gaming - NBC Bay Area", "2012-08-30"),
    ("/organization/sifteo", "Review: Sifteo Cubes bring physicality back to digital games - Ars Technica", "2011-08-10")
  )

  def scored(permalink: String, headline: String, date: LocalDate): ScoredNewsRecord =
    SentimentScorer.scoreNews(NewsRecord(
      headline = headline,
      publicationDate = Some(date),
      source = None,
      url = None,
      retrievalDate = LocalDate.now(),
      matchedPermalink = Some(permalink),
      matchConfidence = 1.0,
      matchedStartupName = None
    ))

  def scoredBy(rows: List[(String, String, String)]): List[(String, ScoredNewsRecord)] =
    rows.map { case (permalink, headline, date) =>
      (permalink, scored(permalink, headline, LocalDate.parse(date)))
    }

  def forStartup(permalink: String): List[ScoredNewsRecord] =
    scoredBy((joinable ++ sifteo).filter(_._1 == permalink)).map(_._2)
}
