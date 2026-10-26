package com.startupsurvival.spark

import org.apache.spark.sql.{SparkSession, DataFrame, Row}
import org.apache.spark.sql.types._
import org.apache.spark.ml.linalg.{Vectors, SQLDataTypes}
import org.apache.spark.rdd.RDD
import com.startupsurvival.models.{FinancialFeatures, SentimentFeatures}
import com.startupsurvival.spark.FeatureJoin.CombinedFeatures

/**
 * PHASES 14/15 - Feature vectors for Spark ML.
 *
 * The financial+sentiment vector is the financial vector with sentiment
 * appended, so experiments 1 vs 3 and 2 vs 4 differ only by sentiment.
 *
 * Imputation: every None becomes 0.0, and each imputed field has a 0/1
 * indicator (hasFundingAmountRecorded, fundingDataLeakageSafe,
 * hasAnyCoverage) so the model can tell "unknown" from a real zero.
 */
object FeatureSets {

  val FinancialFeatureNames: Array[String] = Array(
    "startupAgeYears", "totalFundingUsd", "fundingRoundCount", "averageRoundSizeUsd",
    "timeSinceLastFundingDays", "fundingFrequencyPerYear", "hasFundingAmountRecorded",
    "fundingDataLeakageSafe"
  )

  val SentimentFeatureNames: Array[String] = Array(
    "newsCount", "positiveNewsCount", "negativeNewsCount", "neutralNewsCount",
    "positiveRatio", "negativeRatio", "averageSentiment", "recentSentiment",
    "sentimentTrend", "sentimentVolatility", "newsVolumeTrend", "hasAnyCoverage"
  )

  private def flag(b: Boolean): Double = if (b) 1.0 else 0.0

  /** Column order must match FinancialFeatureNames. */
  def financialArray(f: FinancialFeatures): Array[Double] = Array(
    f.startupAgeYears,
    f.totalFundingUsd.getOrElse(0.0),
    f.fundingRoundCount.getOrElse(0).toDouble,
    f.averageRoundSizeUsd.getOrElse(0.0),
    f.timeSinceLastFundingDays.getOrElse(0L).toDouble,
    f.fundingFrequencyPerYear.getOrElse(0.0),
    flag(f.hasFundingAmountRecorded),
    flag(f.fundingDataLeakageSafe)
  )

  /** Column order must match SentimentFeatureNames. */
  def sentimentArray(s: SentimentFeatures): Array[Double] = Array(
    s.newsCount.toDouble,
    s.positiveNewsCount.toDouble,
    s.negativeNewsCount.toDouble,
    s.neutralNewsCount.toDouble,
    s.positiveRatio,
    s.negativeRatio,
    s.averageSentiment,
    s.recentSentiment.getOrElse(0.0),
    s.sentimentTrend.getOrElse(0.0),
    s.sentimentVolatility.getOrElse(0.0),
    s.newsVolumeTrend.getOrElse(0.0),
    flag(s.newsCount > 0)
  )

  // PHASE 14b/15b - leakage-free features and the coverage/tone ablation.

  /** Top categories/countries for one-hot encoding. Built from frequencies
   * only (never labels), so fixing it on the full population leaks nothing. */
  final case class Vocab(categories: Array[String], countries: Array[String])

  private def categories(f: FinancialFeatures): List[String] =
    f.categoryList.toList.flatMap(_.split('|')).map(_.trim).filter(_.nonEmpty)

  def buildVocab(combinedRDD: RDD[(String, CombinedFeatures)], topCategories: Int = 20, topCountries: Int = 10): Vocab = {
    def top(values: RDD[String], k: Int): Array[String] =
      values.map(v => (v, 1)).reduceByKey(_ + _).sortBy(-_._2).keys.take(k)
    Vocab(
      top(combinedRDD.flatMap(cf => categories(cf._2.financial)), topCategories),
      top(combinedRDD.flatMap(_._2.financial.countryCode), topCountries)
    )
  }

  def safeFeatureNames(v: Vocab): Array[String] =
    Array("startupAgeYears", "yearsSinceFirstFunding", "yearsFoundedToFirstFunding") ++
      v.categories.map("category=" + _) ++ Array("category=other") ++
      v.countries.map("country=" + _) ++ Array("country=other")

  val CoverageFeatureNames: Array[String] = Array("log1pNewsCount", "hasAnyCoverage")

  /** SentimentFeatureNames minus the two coverage fields. */
  val ToneFeatureNames: Array[String] = SentimentFeatureNames.slice(1, 11)

  /**
   * Phase 14b - only fields known at the cutoff for every startup (see the
   * Phase 11b note in FinancialFeatureExtractor): no lifetime funding totals,
   * round counts or last-funding date, nor any flag derived from them.
   * Categories are multi-hot, since a startup can list several.
   */
  def financialSafeArray(v: Vocab)(f: FinancialFeatures): Array[Double] = {
    val cats = categories(f).toSet
    val country = f.countryCode.getOrElse("")
    Array(f.startupAgeYears, f.yearsSinceFirstFunding, f.yearsFoundedToFirstFunding) ++
      v.categories.map(c => flag(cats.contains(c))) ++ Array(flag(cats.exists(c => !v.categories.contains(c)))) ++
      v.countries.map(c => flag(c == country)) ++ Array(flag(!v.countries.contains(country)))
  }

  /** How much news exists, not what it says. log1p tames the heavy tail (RSS returns up to 100). */
  def coverageArray(s: SentimentFeatures): Array[Double] =
    Array(math.log1p(s.newsCount.toDouble), flag(s.newsCount > 0))

  def toneArray(s: SentimentFeatures): Array[Double] = sentimentArray(s).slice(1, 11)

  /** Phase 18b's nested sets: each adds one group, isolating news volume, then tone. */
  def safe(v: Vocab)(cf: CombinedFeatures): Array[Double] = financialSafeArray(v)(cf.financial)
  def safeCoverage(v: Vocab)(cf: CombinedFeatures): Array[Double] = safe(v)(cf) ++ coverageArray(cf.sentiment)
  def safeSentiment(v: Vocab)(cf: CombinedFeatures): Array[Double] = safeCoverage(v)(cf) ++ toneArray(cf.sentiment)

  private val Schema = StructType(Seq(
    StructField("permalink", StringType, nullable = false),
    StructField("label", DoubleType, nullable = false),
    StructField("features", SQLDataTypes.VectorType, nullable = false)
  ))

  def toDataFrame(
      spark: SparkSession,
      combinedRDD: RDD[(String, CombinedFeatures)],
      featuresOf: CombinedFeatures => Array[Double]
  ): DataFrame = {
    val rows = combinedRDD.map { case (permalink, cf) => Row(permalink, cf.label.toDouble, Vectors.dense(featuresOf(cf))) }
    spark.createDataFrame(rows, Schema).persist()
  }

  /** Experiments 1 and 2. Full population. */
  def financialOnly(spark: SparkSession, combinedRDD: RDD[(String, CombinedFeatures)]): DataFrame =
    toDataFrame(spark, combinedRDD, cf => financialArray(cf.financial))

  /** Experiments 3 and 4. Also the full population: filtering to covered
   * startups would bias toward well-funded ones (coverage tracks funding). */
  def financialPlusSentiment(spark: SparkSession, combinedRDD: RDD[(String, CombinedFeatures)]): DataFrame =
    toDataFrame(spark, combinedRDD, cf => financialArray(cf.financial) ++ sentimentArray(cf.sentiment))

  def main(args: Array[String]): Unit = {
    val spark = Pipeline.session("FeatureSets-Phase14-15")
    val (financialOnlyDF, financialSentimentDF) = Pipeline.featureSets(spark, Pipeline.inputPath(args))

    for ((name, df, columns) <- Seq(
      ("PHASE 14: Financial-only", financialOnlyDF, FinancialFeatureNames),
      ("PHASE 15: Financial + sentiment", financialSentimentDF, FinancialFeatureNames ++ SentimentFeatureNames)
    )) {
      println(s"=== $name ===")
      println(s"Rows: ${df.count()} (expect 22075), vector width ${columns.length}: ${columns.mkString(", ")}")
      df.show(5, truncate = false)
    }

    spark.stop()
  }
}
