package com.startupsurvival.spark

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.startupsurvival.models.StartupRecord
import com.startupsurvival.spark.FeatureJoin.CombinedFeatures

/** Phases 12-15 wired together, shared by every Spark entry point. */
object Pipeline {

  val Cutoff = StartupRecord.PredictionCutoff

  def session(appName: String): SparkSession = {
    val spark = SparkSession.builder().appName(appName).master("local[*]").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    spark
  }

  /** Every entry point takes an optional input path; HDFS is the default. */
  def inputPath(args: Array[String]): String = args.headOption.getOrElse(StartupRDD.HdfsPath)

  def combined(spark: SparkSession, path: String): RDD[(String, CombinedFeatures)] = {
    val financialRDD = StartupRDD.extractFinancialFeaturesRDD(StartupRDD.load(spark, path))
    val sentimentRDD = Aggregation.toSentimentFeaturesRDD(NewsRDD.loadDemoSample(spark).groupByKey(), Cutoff)
    FeatureJoin.join(financialRDD, sentimentRDD, Cutoff)
  }

  /** (financial-only, financial+sentiment) - Phases 14 and 15. */
  def featureSets(spark: SparkSession, path: String): (DataFrame, DataFrame) = {
    val combinedRDD = combined(spark, path)
    (FeatureSets.financialOnly(spark, combinedRDD), FeatureSets.financialPlusSentiment(spark, combinedRDD))
  }
}
