package com.startupsurvival.spark

import java.io.{File, PrintWriter}

/**
 * PHASE 13b - Name-matching precision audit.
 *
 * Writes a seeded sample of 100 accepted headlines to
 * results/name_match_audit.csv with an empty `about_company` column, judged
 * by hand (y/n). The share of "y" estimates the matcher's precision. Seeded
 * rather than the first 100 rows, so it is reproducible and not dominated by
 * the startups with the most headlines.
 */
object MatchAudit {
  def main(args: Array[String]): Unit = {
    val spark = Pipeline.session("MatchAudit-Phase13b")

    val accepted = NewsRDD.accepted(NewsRDD.loadRaw(spark))
      .map { case (h, headline, confidence) => (h.permalink, h.companyName, confidence, headline) }
    val sample = accepted.takeSample(withReplacement = false, num = 100, seed = 7L).sortBy(_._1)

    def csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val out = new File("../results/name_match_audit.csv")
    val w = new PrintWriter(out)
    try {
      w.println("permalink,company,confidence,headline,about_company")
      sample.foreach { case (p, n, c, h) => w.println(Seq(csv(p), csv(n), c, csv(h), "").mkString(",")) }
    } finally w.close()
    println(s"Accepted headlines: ${accepted.count()}; wrote ${sample.length}-row audit sample to $out")
    spark.stop()
  }
}
