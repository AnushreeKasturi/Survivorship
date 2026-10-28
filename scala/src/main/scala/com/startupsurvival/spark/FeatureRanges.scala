package com.startupsurvival.spark

import java.io.{File, PrintWriter}
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization.writePretty

/**
 * Exports how old the training startups were at the cutoff, so the web demo
 * can warn when a company is outside that range (web/Server.scala, Maturity).
 *
 *   sbt "runMain com.startupsurvival.spark.FeatureRanges"   # writes results/feature_ranges.json
 */
object FeatureRanges {
  def main(args: Array[String]): Unit = {
    val spark = Pipeline.session("FeatureRanges")
    val features = StartupRDD.extractFinancialFeaturesRDD(StartupRDD.load(spark, Pipeline.inputPath(args))).values.persist()

    def quantiles(values: Array[Double]): Map[String, Double] = {
      val s = values.sorted
      def at(q: Double) = s(math.min(s.length - 1, (q * s.length).toInt))
      Map("p10" -> at(0.1), "p50" -> at(0.5), "p90" -> at(0.9), "p99" -> at(0.99), "max" -> s.last)
    }
    val ranges = Map(
      "note" -> s"Years at the 2013-01-01 cutoff across ${features.count()} training startups.",
      "startupAgeYears" -> quantiles(features.map(_.startupAgeYears).collect()),
      "yearsSinceFirstFunding" -> quantiles(features.map(_.yearsSinceFirstFunding).collect())
    )
    val out = new File(args.lift(1).getOrElse("../results/feature_ranges.json"))
    val w = new PrintWriter(out, "UTF-8")
    try w.println(writePretty(ranges)(DefaultFormats)) finally w.close()
    println(writePretty(ranges)(DefaultFormats))
    spark.stop()
  }
}
