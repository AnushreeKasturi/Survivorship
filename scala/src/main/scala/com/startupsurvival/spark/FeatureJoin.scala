package com.startupsurvival.spark

import java.time.LocalDate
import org.apache.spark.rdd.RDD
import com.startupsurvival.FeatureExtractor
import com.startupsurvival.models.{FinancialFeatures, SentimentFeatures}

/**
 * PHASE 13 - Joins financial and sentiment features per startup.
 *
 * leftOuterJoin, not join(): most startups have no news, and an inner join
 * would drop them and break the financial-only experiments. Missing
 * sentiment becomes the same zero baseline FeatureExtractor returns for an
 * empty list. Persisted because Phases 14 and 15 both read it.
 */
object FeatureJoin {

  final case class CombinedFeatures(
      permalink: String,
      label: Int,
      financial: FinancialFeatures,
      sentiment: SentimentFeatures
  )

  def join(
      financialRDD: RDD[(String, FinancialFeatures)],
      sentimentRDD: RDD[(String, SentimentFeatures)],
      cutoff: LocalDate
  ): RDD[(String, CombinedFeatures)] =
    financialRDD
      .leftOuterJoin(sentimentRDD)
      .map { case (permalink, (financial, sentimentOpt)) =>
        val sentiment = sentimentOpt.getOrElse(FeatureExtractor.extractSentimentFeatures(permalink, Nil, cutoff))
        (permalink, CombinedFeatures(permalink, financial.label, financial, sentiment))
      }
      .persist()

  def main(args: Array[String]): Unit = {
    val spark = Pipeline.session("FeatureJoin-Phase13")
    val combinedRDD = Pipeline.combined(spark, Pipeline.inputPath(args))

    val total = combinedRDD.count()
    val withNews = combinedRDD.filter(_._2.sentiment.newsCount > 0).count()
    println(s"Combined features: $total startups (expect 22075 - leftOuterJoin keeps everyone)")
    println(s"With real news coverage: $withNews, zero-baseline sentiment: ${total - withNews}")

    val samples = Seq("/organization/kabbage", "/organization/color-labs", "/organization/pinterest", "/organization/1-800-doctors")
    combinedRDD.filter { case (permalink, _) => samples.contains(permalink) }.collect().sortBy(_._1).foreach { case (permalink, cf) =>
      println(f"$permalink: label=${cf.label}, ageYears=${cf.financial.startupAgeYears}%.1f, " +
        s"leakageSafe=${cf.financial.fundingDataLeakageSafe}, newsCount=${cf.sentiment.newsCount}, " +
        s"avgSentiment=${cf.sentiment.averageSentiment}")
    }

    spark.stop()
  }
}
