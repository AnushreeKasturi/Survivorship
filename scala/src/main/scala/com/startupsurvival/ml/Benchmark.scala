package com.startupsurvival.ml

import java.io.{File, PrintWriter}
import java.time.{Instant, LocalDate}
import org.apache.spark.ml.Transformer
import org.apache.spark.ml.classification.{GBTClassifier, RandomForestClassifier, LogisticRegression => SparkLogisticRegression}
import org.apache.spark.ml.linalg.{Vector => MLVector}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, udf}
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization.writePretty
import com.startupsurvival.models.SentimentFeatures
import com.startupsurvival.spark.{FeatureJoin, FeatureSets, Pipeline, StartupRDD}
import com.startupsurvival.web.{Server, Tracker}
import ExperimentRunner._

/**
 * The model benchmark: every candidate for the live structural model is
 * scored on the same stratified 5 folds (ExperimentRunner's), on all 22,075
 * startups with the leak-free features, and compared with the baseline by a
 * paired bootstrap of out-of-fold scores. A candidate only counts as an
 * improvement when its AUC gain's 95% interval is above zero.
 *
 * The eight case studies are reported as a sanity check, never as a target:
 * eight outcomes cannot distinguish models (3/8 has a 95% interval of ~9-76%).
 *
 *   sbt "runMain com.startupsurvival.ml.Benchmark file:///path/to/labeled_eligible_startups.csv"
 *   # writes results/benchmark.json
 */
object Benchmark {

  final case class Candidate(name: String, description: String, fit: DataFrame => Transformer)

  private def lr(reg: Double)(df: DataFrame): Transformer =
    new SparkLogisticRegression().setWeightCol("weight").setMaxIter(100).setRegParam(reg).fit(df)

  /** To try an enhancement, add it here and re-run; the baseline is the live model's configuration. */
  val Candidates: Seq[Candidate] = Seq(
    Candidate("lr", "Logistic regression, L2 0.01 (the live model's configuration)", lr(0.01)),
    Candidate("lr-l2-0.1", "Logistic regression, stronger L2 0.1", lr(0.1)),
    Candidate("gbt", "Gradient-boosted trees, 80 rounds, depth 4", df =>
      new GBTClassifier().setWeightCol("weight").setMaxIter(80).setMaxDepth(4).setStepSize(0.1).setSeed(Seed).fit(df)),
    Candidate("rf", "Random forest, 200 trees, depth 8", df =>
      new RandomForestClassifier().setWeightCol("weight").setNumTrees(200).setMaxDepth(8).setSeed(Seed).fit(df)),
    Candidate("rf-100", "Random forest, 100 trees, depth 8 (half the size to serve)", df =>
      new RandomForestClassifier().setWeightCol("weight").setNumTrees(100).setMaxDepth(8).setSeed(Seed).fit(df))
  )
  val Baseline = "lr"

  private val operating = udf((v: MLVector) => v(1))

  private def scores(model: Transformer, df: DataFrame): Array[(String, Int, Double)] = {
    import df.sparkSession.implicits._
    model.transform(df).select(col("permalink"), col("label").cast("int"), operating(col("probability")))
      .as[(String, Int, Double)].collect()
  }

  final case class FoldScores(oof: Array[(String, Int, Double)], thresholds: Seq[Double])

  /** Out-of-fold P(operating) for every startup, plus each fold's validation-tuned threshold. */
  def crossValidate(df: DataFrame, c: Candidate): FoldScores = {
    val perFold = (0 until Folds).map { fold =>
      val train = df.filter(col("fold") =!= fold)
      val Array(inner, validation) = train.randomSplit(Array(0.8, 0.2), seed = Seed + fold)
      val model = c.fit(Training.withBalancedWeights(inner)._1)
      val v = scores(model, validation)
      (scores(model, df.filter(col("fold") === fold)), tuneThreshold(v.map(_._3), v.map(_._2)))
    }
    FoldScores(perFold.flatMap(_._1).toArray, perFold.map(_._2))
  }

  def brier(s: Array[Double], l: Array[Int]): Double = s.indices.map(i => math.pow(s(i) - l(i), 2)).sum / s.length

  def main(args: Array[String]): Unit = {
    val spark = Pipeline.session("Benchmark")
    val financial = StartupRDD.extractFinancialFeaturesRDD(StartupRDD.load(spark, Pipeline.inputPath(args))).persist()
    // No news here: the benchmark covers the structural model that decides the live verdicts.
    val combined = FeatureJoin.join(financial, spark.sparkContext.emptyRDD[(String, SentimentFeatures)], Pipeline.Cutoff)
    val vocab = FeatureSets.buildVocab(combined)
    val folds = assignFolds(combined.map { case (p, cf) => (p, cf.label) }.collect())
    val foldOf = udf((p: String) => folds(p))
    val df = FeatureSets.toDataFrame(spark, combined, FeatureSets.safe(vocab)).withColumn("fold", foldOf(col("permalink"))).persist()

    // Case studies, featurised exactly as the live server does at each one's as-of date.
    val cases = Tracker.loadProfiles(args.filterNot(_.startsWith("--")).lift(1).getOrElse("../startups/case_studies.json"))
    val caseRows = cases.map { p =>
      val asOf = Tracker.caseStudyAsOf(p)
      val f = Server.financialFeatures(LocalDate.parse(p.foundedAt), LocalDate.parse(p.firstFundingAt), p.categories, p.country, asOf)
      (p.name, if (p.outcome.contains("operating")) 1 else 0, FeatureSets.financialSafeArray(vocab)(f))
    }
    val caseDf = spark.createDataFrame(caseRows.map { case (n, l, x) =>
      org.apache.spark.sql.Row(n, l.toDouble, org.apache.spark.ml.linalg.Vectors.dense(x)) }.asJava,
      df.select("permalink", "label", "features").schema)

    val results = Candidates.map { c =>
      val cv = crossValidate(df, c)
      val s = cv.oof.map(_._3); val l = cv.oof.map(_._2)
      val perFold = cv.oof.groupBy(r => folds(r._1)).toSeq.sortBy(_._1).map { case (_, rs) => aucRoc(rs.map(_._3), rs.map(_._2)) }
      val mean = perFold.sum / perFold.size
      val sd = math.sqrt(perFold.map(a => (a - mean) * (a - mean)).sum / (perFold.size - 1))
      val t = cv.thresholds.sum / cv.thresholds.size
      val full = c.fit(Training.withBalancedWeights(df)._1)
      val caseScores = scores(full, caseDf)
      val called = (th: Double) => caseScores.count { case (_, label, p) => (p >= th) == (label == 1) }
      println(f"  ${c.name}%-10s AUC $mean%.4f ± $sd%.4f  BA@0.5 ${balancedAccuracy(s, l, 0.5)}%.4f  BA@tuned ${balancedAccuracy(s, l, t)}%.4f (t=$t%.3f)  cases ${called(0.5)}/8 @0.5, ${called(t)}/8 @tuned")
      (c, cv, Map[String, Any](
        "name" -> c.name, "description" -> c.description,
        "aucMean" -> mean, "aucSd" -> sd, "aucPooled" -> aucRoc(s, l),
        "prAucClosed" -> averagePrecisionClosed(s, l),
        "balancedAccuracyAt05" -> balancedAccuracy(s, l, 0.5),
        "balancedAccuracyTuned" -> balancedAccuracy(s, l, t), "tunedThreshold" -> t,
        "brier" -> brier(s, l),
        "caseStudies" -> Map("correctAt05" -> called(0.5), "correctAtTuned" -> called(t), "of" -> caseScores.length,
          "scores" -> caseScores.map { case (n, label, p) => Map("name" -> n, "operating" -> (label == 1), "p" -> p) })
      ))
    }

    val base = results.find(_._1.name == Baseline).get._2.oof
    val rows = results.map { case (c, cv, m) =>
      if (c.name == Baseline) m + ("verdict" -> "baseline")
      else {
        val (d, lo, hi, p) = pairedBootstrap(base, cv.oof)
        val verdict = if (lo > 0) "better" else if (hi < 0) "worse" else "no significant difference"
        println(f"  ${c.name}%-10s vs $Baseline: dAUC $d%+.4f  95%% CI [$lo%+.4f, $hi%+.4f]  p=$p%.3f  -> $verdict")
        m ++ Map("deltaAucVsBaseline" -> Map("delta" -> d, "ciLow" -> lo, "ciHigh" -> hi, "p" -> p), "verdict" -> verdict)
      }
    }

    // --export=<name> writes that candidate, fitted on every startup, for the live server.
    args.collectFirst { case a if a.startsWith("--export=") => a.stripPrefix("--export=") }.foreach { name =>
      val (c, cv, _) = results.find(_._1.name == name).getOrElse(sys.error(s"no candidate $name"))
      val model = c.fit(Training.withBalancedWeights(df)._1).asInstanceOf[org.apache.spark.ml.classification.RandomForestClassificationModel]
      ModelExport.writeForest(spark, model, FeatureSets.safeFeatureNames(vocab), vocab, cv.thresholds.sum / cv.thresholds.size,
        s"${c.description}; see results/benchmark.json", new File("../results/model_structural.json"))
      ModelExport.verify(model, new File("../results/model_structural.json"), df)
    }

    val out = new File(args.filterNot(_.startsWith("--")).lift(2).getOrElse("../results/benchmark.json"))
    val w = new PrintWriter(out, "UTF-8")
    try w.println(writePretty(Map(
      "generatedAt" -> Instant.now().toString,
      "population" -> Map("startups" -> df.count(), "closed" -> df.filter(col("label") === 0).count()),
      "protocol" -> "Stratified 5-fold CV (seed 42, ExperimentRunner folds), leak-free features, balanced class weights, threshold tuned on an inner validation split; paired bootstrap of out-of-fold scores vs the baseline (2,000 resamples).",
      "baseline" -> Baseline,
      "candidates" -> rows
    ))(DefaultFormats)) finally w.close()
    println(s"Wrote $out")
    spark.stop()
  }

  private implicit class AsJava[A](xs: Seq[A]) { def asJava: java.util.List[A] = scala.collection.JavaConverters.seqAsJavaList(xs) }
}
