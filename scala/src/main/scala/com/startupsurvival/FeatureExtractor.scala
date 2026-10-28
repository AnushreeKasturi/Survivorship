package com.startupsurvival

import java.time.LocalDate
import com.startupsurvival.models.{ScoredNewsRecord, SentimentClass, SentimentFeatures, StartupRecord}

/**
 * PHASE 10 - Aggregates a startup's scored news into SentimentFeatures.
 *
 * The publicationDate < cutoff filter is re-applied here even though
 * NewsCollector already filters: one missed upstream filter would silently
 * reintroduce temporal leakage. No coverage yields a zero baseline, not an
 * error - most startups have no news.
 */
object FeatureExtractor {

  private def mean(xs: Seq[Int]): Double = xs.sum.toDouble / xs.length

  def extractSentimentFeatures(
      permalink: String,
      scoredNews: List[ScoredNewsRecord],
      cutoff: LocalDate
  ): SentimentFeatures = {
    // Sorted chronologically; .get is safe because the filter keeps only dated items.
    val eligible = scoredNews
      .filter(_.news.publicationDate.exists(_.isBefore(cutoff)))
      .sortBy(_.news.publicationDate.get.toEpochDay)
    val n = eligible.length
    val scores = eligible.map(_.sentimentScore)

    if (n == 0)
      SentimentFeatures(permalink, 0, 0, 0, 0, 0.0, 0.0, 0.0, None, None, None, None)
    else {
      val positive = eligible.count(_.sentimentClass == SentimentClass.Positive)
      val negative = eligible.count(_.sentimentClass == SentimentClass.Negative)
      val average = mean(scores)
      val (earlier, later) = scores.splitAt(n / 2)
      // Lookback window is 24 months (NewsCollector); compare its two halves.
      val windowMidpoint = cutoff.minusMonths(12)
      val recentCount = eligible.count(_.news.publicationDate.exists(!_.isBefore(windowMidpoint)))

      SentimentFeatures(
        permalink = permalink,
        newsCount = n,
        positiveNewsCount = positive,
        negativeNewsCount = negative,
        neutralNewsCount = n - positive - negative,
        positiveRatio = positive.toDouble / n,
        negativeRatio = negative.toDouble / n,
        averageSentiment = average,
        recentSentiment = Some(scores.last.toDouble),
        // Trend and sample stdev need two points.
        sentimentTrend = if (n >= 2) Some(mean(later) - mean(earlier)) else None,
        sentimentVolatility =
          if (n >= 2) Some(math.sqrt(scores.map(s => math.pow(s - average, 2)).sum / (n - 1))) else None,
        newsVolumeTrend = Some((recentCount - (n - recentCount)).toDouble)
      )
    }
  }

  /** Self-check on the real Phase 7 headlines (values hand-verified in Phase 10). */
  def main(args: Array[String]): Unit = {
    val cutoff = StartupRecord.PredictionCutoff
    val features = List(
      "/organization/kabbage", "/organization/sifteo", "/organization/color-labs",
      "/organization/pinterest", "/organization/1-800-doctors" // real startup, zero accepted news
    ).map(p => p -> extractSentimentFeatures(p, SampleNews.forStartup(p), cutoff)).toMap

    features.values.foreach(println)

    val kabbage = features("/organization/kabbage")
    assert(kabbage.newsCount == 2 && kabbage.positiveRatio == 0.5 && kabbage.averageSentiment == 0.5 &&
      kabbage.sentimentTrend.contains(1.0), s"kabbage features changed: $kabbage")
    assert(features("/organization/color-labs").averageSentiment == -0.5)
    assert(features("/organization/pinterest").averageSentiment == 0.4)
    assert(features("/organization/1-800-doctors").newsCount == 0)
    println("Sentiment feature checks passed")
  }
}
