package com.startupsurvival

import com.startupsurvival.models.{NewsRecord, ScoredNewsRecord, SentimentClass}

/**
 * PHASE 9 - Transparent lexicon-based sentiment for startup headlines.
 *
 * tokenize -> drop stopwords -> count lexicon hits -> score = pos - neg ->
 * classify by sign. The word lists are an original compilation scoped to
 * funding/startup news (generic lexicons misread business text - Loughran &
 * McDonald 2011). No stemming, so inflections are listed explicitly.
 */
object SentimentScorer {

  val Stopwords: Set[String] = Set(
    "a", "an", "the", "of", "in", "on", "for", "to", "and", "or", "is",
    "are", "was", "were", "at", "by", "with", "as", "from", "this",
    "that", "it", "its", "be", "been", "has", "have", "had", "will",
    "after", "into", "over", "up", "down", "out", "amid"
  )

  val PositiveTerms: Set[String] = Set(
    "raise", "raises", "raised", "raising", "funding", "funded", "funds",
    "invest", "invests", "invested", "investment", "investors", "backed",
    "backing", "expand", "expands", "expanded", "expansion", "growth",
    "grows", "growing", "grew", "launch", "launches", "launched",
    "launching", "success", "successful", "successfully", "profit",
    "profits", "profitable", "profitability", "partnership", "partners",
    "partner", "partnering", "innovative", "innovation", "award",
    "awarded", "wins", "won", "winning", "milestone", "record", "strong",
    "boost", "boosts", "boosted", "surge", "surges", "surging", "soar",
    "soars", "soaring", "hires", "hiring", "hired", "valuation",
    "unicorn", "ipo", "public", "acquired", "acquisition", "acquires",
    "breakthrough", "leading", "leader", "top", "best", "opportunity",
    "opportunities", "positive", "optimistic", "thrive", "thriving",
    "scale", "scaling", "scaled", "secures", "secured", "secure"
  )

  val NegativeTerms: Set[String] = Set(
    "close", "closes", "closed", "closing", "shuts", "shut", "shutdown",
    "shutting", "bankrupt", "bankruptcy", "layoffs", "layoff", "laysoff",
    "cuts", "cutting", "cut", "decline", "declines", "declined",
    "declining", "loss", "losses", "losing", "lost", "fails", "failed",
    "failure", "failing", "struggles", "struggling", "struggled",
    "collapse", "collapses", "collapsed", "collapsing", "warns",
    "warning", "warned", "lawsuit", "lawsuits", "sues", "sued", "suing",
    "fraud", "scandal", "resigns", "resigned", "resignation", "fired",
    "fires", "firing", "delay", "delays", "delayed", "delaying", "recall",
    "recalls", "recalled", "plunge", "plunges", "plunged", "drop",
    "drops", "dropped", "dropping", "concern", "concerns", "concerning",
    "controversy", "controversial", "criticized", "criticism",
    "criticizes", "trouble", "troubled", "troubling", "crisis", "weak",
    "weakness", "weakening", "disappointing", "disappoints",
    "disappointed", "downturn", "slump", "slumps", "slumping"
  )

  def tokenize(text: String): List[String] =
    text
      .toLowerCase
      .split("[^a-zA-Z]+")
      .filter(_.nonEmpty)
      .toList

  def removeStopwords(tokens: List[String]): List[String] =
    tokens.filterNot(Stopwords.contains)

  def countSentimentWords(tokens: List[String]): (Int, Int) =
    tokens.foldLeft((0, 0)) { case ((posCount, negCount), token) =>
      token match {
        case t if PositiveTerms.contains(t) => (posCount + 1, negCount)
        case t if NegativeTerms.contains(t) => (posCount, negCount + 1)
        case _                              => (posCount, negCount)
      }
    }

  def classify(score: Int): SentimentClass =
    score match {
      case s if s > 0 => SentimentClass.Positive
      case s if s < 0 => SentimentClass.Negative
      case _          => SentimentClass.Neutral
    }

  final case class ScoreBreakdown(
      positiveMatches: Int,
      negativeMatches: Int,
      sentimentScore: Int,
      sentimentClass: SentimentClass,
      matchedPositiveTerms: List[String],
      matchedNegativeTerms: List[String]
  )

  def scoreHeadline(headline: String): ScoreBreakdown = {
    val tokens = removeStopwords(tokenize(headline))
    val (pos, neg) = countSentimentWords(tokens)
    val score = pos - neg
    ScoreBreakdown(
      positiveMatches = pos,
      negativeMatches = neg,
      sentimentScore = score,
      sentimentClass = classify(score),
      matchedPositiveTerms = tokens.filter(PositiveTerms.contains),
      matchedNegativeTerms = tokens.filter(NegativeTerms.contains)
    )
  }

  def scoreNews(news: NewsRecord): ScoredNewsRecord = {
    val breakdown = scoreHeadline(news.headline)
    ScoredNewsRecord(
      news = news,
      positiveMatches = breakdown.positiveMatches,
      negativeMatches = breakdown.negativeMatches,
      sentimentScore = breakdown.sentimentScore,
      sentimentClass = breakdown.sentimentClass
    )
  }

  /** Self-check: the brief's worked example, then real and constructed headlines. */
  def main(args: Array[String]): Unit = {
    val brief = scoreHeadline("Startup X raises major funding and expands into new markets")
    println(s"Brief example: $brief")
    assert(brief.matchedPositiveTerms == List("raises", "funding", "expands") &&
      brief.sentimentClass == SentimentClass.Positive, "brief's worked example must score Positive")

    val headlines = SampleNews.joinable.map(_._2) ++ List(
      "Startup shuts down after failing to raise sufficient funding, layoffs follow",
      "Company announces bankruptcy amid mounting losses and investor lawsuit"
    )
    headlines.foreach { h =>
      val r = scoreHeadline(h)
      println(s"""[${r.sentimentClass}] score=${r.sentimentScore} pos=${r.matchedPositiveTerms} neg=${r.matchedNegativeTerms}\n  "$h"""")
    }
  }
}
