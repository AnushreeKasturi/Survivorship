package com.startupsurvival

import java.time.LocalDate
import org.scalatest.funsuite.AnyFunSuite
import com.startupsurvival.models.{NewsRecord, SentimentClass}
import com.startupsurvival.spark.NewsRDD

/** Phases 7-10 and 13b: name matching, sentiment scoring, sentiment
 * features and the raw-news parsing used by NewsRDD. Cases are the real
 * headlines documented in docs/PHASE08-10 wherever one exists. */
class TextPipelineSpec extends AnyFunSuite {

  // ---------------------------------------------------------- NameMatcher

  test("exact distinctive name is accepted at full confidence") {
    val r = NameMatcher.computeMatchConfidence("Kabbage", "Kabbage Raises Some Serious Cabbage for Small-Business Loans")
    assert(r.confidence == 1.0)
  }

  test("Phase 7 false positive (tomato barons) is rejected") {
    val r = NameMatcher.computeMatchConfidence("Kabbage", "The tomato barons of the occupied Western Sahara")
    assert(!r.matched && r.confidence == 0.0)
  }

  test("corporate suffixes are ignored") {
    assert(NameMatcher.computeMatchConfidence("Kabbage Inc", "Kabbage expands lending").confidence == 1.0)
  }

  test("whole-word matching: name inside another word does not match") {
    assert(NameMatcher.computeMatchConfidence("Zuora", "Zuoracorp news").confidence == 0.0)
  }

  test("single dictionary-word name is ambiguous and below the acceptance threshold") {
    val r = NameMatcher.computeMatchConfidence("Quorum", "Senate fails to reach quorum")
    assert(r.confidence == 0.3)
    assert(r.confidence < NameMatcher.AcceptanceThreshold)
  }

  test("short names (<= 4 chars) stay ambiguous") {
    assert(NameMatcher.isAmbiguousName("Ox"))
    assert(!NameMatcher.isAmbiguousName("Kabbage"))
  }

  test("all-dictionary multi-word phrase is accepted at discounted confidence") {
    assert(NameMatcher.computeMatchConfidence("Wireless Tech", "Wireless tech stocks rally").confidence == 0.6)
  }

  test("partial match needs a distinctive token") {
    // only the dictionary word "wireless" appears
    assert(NameMatcher.computeMatchConfidence("Wireless Tech", "Wireless spectrum auction").confidence == 0.0)
    // the distinctive token "edmodo" appears
    assert(NameMatcher.computeMatchConfidence("Edmodo Learning", "Edmodo raises Series B").confidence >= NameMatcher.AcceptanceThreshold)
  }

  // ------------------------------------------------------ SentimentScorer

  test("project brief's worked example scores +3 Positive") {
    val b = SentimentScorer.scoreHeadline("Startup X raises major funding and expands into new markets")
    assert(b.matchedPositiveTerms == List("raises", "funding", "expands"))
    assert(b.sentimentScore == 3 && b.sentimentClass == SentimentClass.Positive)
  }

  test("mixed headline resolves by net count") {
    val b = SentimentScorer.scoreHeadline("Startup shuts down after failing to raise sufficient funding, layoffs follow")
    assert(b.sentimentScore == -1 && b.sentimentClass == SentimentClass.Negative)
  }

  test("headline without lexicon terms is Neutral") {
    assert(SentimentScorer.scoreHeadline("Sifteo Cubes Are Out Today").sentimentClass == SentimentClass.Neutral)
  }

  test("known limitation: no negation handling") {
    // documented in report/discussion.md - kept as a test so a future fix is noticed
    assert(SentimentScorer.scoreHeadline("$41 million can't buy success as app gives up").sentimentScore >= 0)
  }

  // ------------------------------------------------------ FeatureExtractor

  private def scored(headline: String, date: String) =
    SentimentScorer.scoreNews(NewsRecord(headline, Some(LocalDate.parse(date)), None, None,
      LocalDate.of(2026, 1, 1), Some("/organization/kabbage"), 1.0, None))

  private val cutoff = LocalDate.of(2013, 1, 1)

  test("no coverage gives the zero baseline") {
    val f = FeatureExtractor.extractSentimentFeatures("/organization/x", Nil, cutoff)
    assert(f.newsCount == 0 && f.averageSentiment == 0.0 && f.sentimentTrend.isEmpty)
  }

  test("Kabbage features match Phase 10's hand-verified values") {
    val f = FeatureExtractor.extractSentimentFeatures("/organization/kabbage", List(
      scored("Kabbage Raises Some Serious Cabbage for Small-Business Loans", "2012-09-19"),
      scored("Kabbage: The Merchant Cash Advance of the Online Business World", "2011-08-23")
    ), cutoff)
    assert(f.newsCount == 2 && f.positiveNewsCount == 1 && f.neutralNewsCount == 1)
    assert(f.positiveRatio == 0.5 && f.averageSentiment == 0.5)
    assert(f.recentSentiment.contains(1.0) && f.sentimentTrend.contains(1.0))
  }

  test("articles on or after the cutoff are ignored (leakage guard)") {
    val f = FeatureExtractor.extractSentimentFeatures("/organization/kabbage", List(
      scored("Kabbage raises funding", "2013-01-01"),
      scored("Kabbage shuts down", "2014-05-01")
    ), cutoff)
    assert(f.newsCount == 0)
  }

  // ------------------------------------------------------------- NewsRDD

  test("raw TSV line parses; malformed lines are dropped") {
    assert(NewsRDD.parseRawLine("/organization/a\tA\t2012-01-02\tWIRED\tA raises $5M - WIRED")
      .exists(_.publicationDate == LocalDate.of(2012, 1, 2)))
    assert(NewsRDD.parseRawLine("too\tfew\tfields").isEmpty)
    assert(NewsRDD.parseRawLine("/o/a\tA\tnot-a-date\tS\tT").isEmpty)
  }

  test("outlet suffix is stripped before matching (Seeking Alpha bug)") {
    assert(NewsRDD.stripSourceSuffix("Apple earnings preview - Seeking Alpha", "Seeking Alpha") == "Apple earnings preview")
    assert(NewsRDD.stripSourceSuffix("No suffix here", "WIRED") == "No suffix here")
    val h = NewsRDD.stripSourceSuffix("Apple earnings preview - Seeking Alpha", "Seeking Alpha")
    assert(NameMatcher.computeMatchConfidence("Seeking Alpha", h).confidence < NameMatcher.AcceptanceThreshold)
  }

  test("common-word names: accepted only when spelled as the company does (live tools)") {
    assert(NameMatcher.computeMatchConfidence("Ramp", "Ramp raises $150M for its corporate card").confidence == 0.3)
    assert(NameMatcher.confirmCommonWord("Ramp", "Ramp raises $150M for its corporate card").confidence == 0.7)
    assert(NameMatcher.confirmCommonWord("Ramp", "Lenders ramp up card offers").confidence < NameMatcher.AcceptanceThreshold)
    assert(NameMatcher.confirmCommonWord("Ramp", "Ramps closed on Route 9").confidence < NameMatcher.AcceptanceThreshold)
    assert(NameMatcher.confirmCommonWord("Kabbage", "Kabbage expands lending").confidence == 1.0)
  }
}
