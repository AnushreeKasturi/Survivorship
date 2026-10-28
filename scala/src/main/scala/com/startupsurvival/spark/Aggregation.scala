package com.startupsurvival.spark

import org.apache.spark.rdd.RDD
import java.time.LocalDate
import com.startupsurvival.FeatureExtractor
import com.startupsurvival.models.{ScoredNewsRecord, SentimentFeatures}

/** PHASE 12 - Phase 10's pure feature extractor, run per startup across the cluster. */
object Aggregation {

  def toSentimentFeaturesRDD(
      groupedNewsRDD: RDD[(String, Iterable[ScoredNewsRecord])],
      cutoff: LocalDate
  ): RDD[(String, SentimentFeatures)] =
    groupedNewsRDD.map { case (permalink, records) =>
      (permalink, FeatureExtractor.extractSentimentFeatures(permalink, records.toList, cutoff))
    }

  def main(args: Array[String]): Unit = {
    val spark = Pipeline.session("Aggregation-Phase12")
    toSentimentFeaturesRDD(NewsRDD.load(spark).groupByKey(), Pipeline.Cutoff)
      .collect()
      .sortBy(_._1)
      .foreach { case (permalink, features) => println(s"$permalink:\n  $features") }
    println("Expect the same values as FeatureExtractor's plain-Scala run (Phase 10).")
    spark.stop()
  }
}
