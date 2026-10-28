package com.startupsurvival.ml

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.ml.classification.{LinearSVC, LogisticRegression => SparkLogisticRegression}
import org.apache.spark.ml.linalg.{Vector => MLVector}
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.rdd.RDD
import java.io.{File, PrintWriter}
import scala.util.Random
import com.startupsurvival.spark.{Aggregation, FeatureJoin, FeatureSets, NewsRDD, Pipeline, StartupRDD}
import com.startupsurvival.spark.FeatureJoin.CombinedFeatures

/**
 * PHASE 18b - Cross-validated experiments with significance testing
 *
 * Phase 18 evaluated each experiment on ONE 80/20 split at the default
 * decision threshold, and Phase 21 showed two problems that a single split
 * cannot address: (1) differences in the 3rd-4th decimal place cannot be
 * told apart from split noise, and (2) the default threshold made the SVM
 * look much worse than its AUC implied. This runner fixes both:
 *
 *  - STRATIFIED 5-FOLD CROSS-VALIDATION. Every startup is in exactly one
 *    test fold, each fold has the population's closed/operating ratio,
 *    and every experiment uses the SAME folds, so comparisons are paired.
 *
 *  - THRESHOLD TUNED WITHOUT TOUCHING THE TEST FOLD. Each training fold is
 *    split again 80/20 (inner train / validation). The model is fit on the
 *    inner-train part with balanced class weights (Training, computed
 *    on inner-train only); the threshold that maximises closed-class F1 on
 *    the validation part is then applied, unchanged, to the test fold.
 *
 *  - PAIRED BOOTSTRAP. For each nested feature-set comparison (e.g. Safe
 *    vs Safe+Sentiment, same model), out-of-fold scores are resampled
 *    2,000 times over startups; the 95% interval of the AUC difference
 *    says whether the added feature group helps beyond resampling noise.
 *
 * Two populations:
 *  - FULL (all 22,075 eligible startups): original Phase 14 financial set
 *    vs the leakage-free Phase 14b set - quantifies the leakage.
 *  - COHORT (startups whose news query succeeded in Phase 13b): Safe vs
 *    Safe+Coverage vs Safe+Sentiment - the research question, plus the
 *    coverage-vs-tone ablation. Restricted to the cohort because only for
 *    these startups does newsCount = 0 mean "no coverage found" rather
 *    than "never searched".
 */
object ExperimentRunner {

  val Folds = 5
  val Seed = 42L
  val BootstrapResamples = 2000

  final case class FoldResult(population: String, featureSet: String, model: String, fold: Int,
      testCount: Int, testClosed: Int, auc: Double, prAucClosed: Double, threshold: Double,
      precisionClosed: Double, recallClosed: Double, f1Closed: Double, balancedAccuracy: Double)

  // ------------------------------------------------------------------ metrics

  /** Indices sorted by ascending score - computed once per experiment. */
  def scoreOrder(scores: Array[Double]): Array[Int] = scores.indices.sortBy(scores(_)).toArray

  /** Weighted Mann-Whitney AUC-ROC (positive class = label 1, operating):
   * the probability that a random operating startup scores above a random
   * closed one, ties counting one half. `weights` are row multiplicities,
   * so a bootstrap resample is a weight vector over the SAME sort order -
   * one O(n) pass per resample instead of a re-sort. */
  def weightedAuc(order: Array[Int], scores: Array[Double], labels: Array[Int], weights: Array[Int]): Double = {
    var cumNeg, totalPos, totalNeg, area = 0.0
    var i = 0
    while (i < order.length) {
      var j = i
      var pos, neg = 0.0
      while (j < order.length && scores(order(j)) == scores(order(i))) {
        val w = weights(order(j))
        if (labels(order(j)) == 1) pos += w else neg += w
        j += 1
      }
      area += pos * (cumNeg + 0.5 * neg)
      cumNeg += neg; totalPos += pos; totalNeg += neg
      i = j
    }
    area / (totalPos * totalNeg)
  }

  def aucRoc(scores: Array[Double], labels: Array[Int]): Double =
    weightedAuc(scoreOrder(scores), scores, labels, Array.fill(scores.length)(1))

  /** Average precision for the CLOSED class (label 0), ranking by lowest
   * label-1 score first. Baseline = closed prevalence (~0.13). */
  def averagePrecisionClosed(scores: Array[Double], labels: Array[Int]): Double = {
    val order = scores.indices.sortBy(scores(_))
    val totalClosed = labels.count(_ == 0)
    var hits = 0
    var sum = 0.0
    order.zipWithIndex.foreach { case (i, rank) =>
      if (labels(i) == 0) { hits += 1; sum += hits.toDouble / (rank + 1) }
    }
    if (totalClosed == 0) 0.0 else sum / totalClosed
  }

  /** Predict CLOSED when score <= threshold. */
  def confusion(scores: Array[Double], labels: Array[Int], t: Double): (Int, Int, Int, Int) = {
    var tpC, fpC, fnC, tnC = 0 // from the closed-class point of view
    scores.indices.foreach { i =>
      val predClosed = scores(i) <= t
      (predClosed, labels(i) == 0) match {
        case (true, true)   => tpC += 1
        case (true, false)  => fpC += 1
        case (false, true)  => fnC += 1
        case (false, false) => tnC += 1
      }
    }
    (tpC, fpC, fnC, tnC)
  }

  def f1Closed(scores: Array[Double], labels: Array[Int], t: Double): Double = {
    val (tp, fp, fn, _) = confusion(scores, labels, t)
    if (tp == 0) 0.0 else 2.0 * tp / (2.0 * tp + fp + fn)
  }

  def balancedAccuracy(scores: Array[Double], labels: Array[Int], t: Double): Double = {
    val (tp, fp, fn, tn) = confusion(scores, labels, t)
    val recallClosed = if (tp + fn == 0) 0.0 else tp.toDouble / (tp + fn)
    val recallOperating = if (tn + fp == 0) 0.0 else tn.toDouble / (tn + fp)
    (recallClosed + recallOperating) / 2
  }

  /** Threshold maximising balanced accuracy (equivalently Youden's J) over
   * 200 score quantiles. First version maximised closed-class F1, which
   * degenerates on the balanced news cohort: labelling EVERY startup closed
   * already scores F1 = 0.667 there, and the tuned thresholds drifted to
   * ~97% closed-recall with balanced accuracy ~0.52. Balanced accuracy is
   * prevalence-independent, matching the balanced class weights. */
  def tuneThreshold(scores: Array[Double], labels: Array[Int]): Double = {
    val sorted = scores.sorted
    val candidates = (1 until 200).map(q => sorted((q * (sorted.length - 1)) / 200)).distinct
    candidates.maxBy(t => balancedAccuracy(scores, labels, t))
  }

  // ------------------------------------------------------------------ folds

  /** Stratified fold assignment: within each label, a seeded shuffle of
   * permalink-sorted keys dealt round-robin into 5 folds. Computed on the
   * driver (22k small keys) and shared by every experiment. */
  def assignFolds(keys: Array[(String, Int)]): Map[String, Int] =
    keys.groupBy(_._2).toSeq.sortBy(_._1).flatMap { case (_, group) =>
      new Random(Seed).shuffle(group.map(_._1).sorted.toSeq).zipWithIndex.map { case (p, i) => (p, i % Folds) }
    }.toMap

  private val scoreOf = udf((v: MLVector) => v(1))

  /** Fit on inner-train, tune threshold on validation, score the test fold. */
  def runFold(spark: SparkSession, df: DataFrame, fold: Int, modelName: String)
      : (Array[(String, Int, Double)], Double) = {
    import spark.implicits._
    val train = df.filter(col("fold") =!= fold)
    val test = df.filter(col("fold") === fold)
    val Array(innerTrain, validation) = train.randomSplit(Array(0.8, 0.2), seed = Seed + fold)
    val (weighted, _) = Training.withBalancedWeights(innerTrain)

    val fitted = modelName match {
      case "Logistic Regression" =>
        new SparkLogisticRegression().setWeightCol("weight").setMaxIter(100).setRegParam(0.01)
          .setElasticNetParam(0.0).fit(weighted)
      case "Linear SVM" =>
        new LinearSVC().setWeightCol("weight").setMaxIter(100).setRegParam(0.01).fit(weighted)
    }
    def scored(d: DataFrame): Array[(String, Int, Double)] =
      fitted.transform(d).select(col("permalink"), col("label").cast("int"), scoreOf(col("rawPrediction")))
        .as[(String, Int, Double)].collect()

    val v = scored(validation)
    val threshold = tuneThreshold(v.map(_._3), v.map(_._2))
    (scored(test), threshold)
  }

  def runExperiment(spark: SparkSession, population: String, featureSet: String, modelName: String,
      df: DataFrame): (Seq[FoldResult], Array[(String, Int, Double)]) = {
    val perFold = (0 until Folds).map { fold =>
      val (test, t) = runFold(spark, df, fold, modelName)
      val s = test.map(_._3); val l = test.map(_._2)
      val (tp, fp, fn, tn) = confusion(s, l, t)
      val precision = if (tp + fp == 0) 0.0 else tp.toDouble / (tp + fp)
      val recall = if (tp + fn == 0) 0.0 else tp.toDouble / (tp + fn)
      val specificity = if (tn + fp == 0) 0.0 else tn.toDouble / (tn + fp)
      val r = FoldResult(population, featureSet, modelName, fold, test.length, l.count(_ == 0),
        aucRoc(s, l), averagePrecisionClosed(s, l), t, precision, recall, f1Closed(s, l, t),
        (recall + specificity) / 2)
      (r, test)
    }
    val results = perFold.map(_._1)
    val mean = results.map(_.auc).sum / Folds
    println(f"  $population%-7s $featureSet%-22s $modelName%-20s AUC=$mean%.4f " +
      f"F1closed=${results.map(_.f1Closed).sum / Folds}%.4f")
    (results, perFold.flatMap(_._2).toArray)
  }

  // ------------------------------------------------------------- bootstrap

  /** Paired bootstrap over startups: resample the SAME rows for both
   * experiments, so the interval reflects only the feature-set difference. */
  def pairedBootstrap(a: Array[(String, Int, Double)], b: Array[(String, Int, Double)])
      : (Double, Double, Double, Double) = {
    val bMap = b.map(r => r._1 -> r._3).toMap
    val rows = a.map(r => (r._2, r._3, bMap(r._1)))
    val labels = rows.map(_._1); val sa = rows.map(_._2); val sb = rows.map(_._3)
    val orderA = scoreOrder(sa); val orderB = scoreOrder(sb)
    val ones = Array.fill(rows.length)(1)
    val observed = weightedAuc(orderB, sb, labels, ones) - weightedAuc(orderA, sa, labels, ones)
    val rnd = new Random(Seed)
    val deltas = (1 to BootstrapResamples).map { _ =>
      val w = new Array[Int](rows.length)
      (0 until rows.length).foreach(_ => w(rnd.nextInt(rows.length)) += 1)
      weightedAuc(orderB, sb, labels, w) - weightedAuc(orderA, sa, labels, w)
    }.sorted
    val lo = deltas((0.025 * BootstrapResamples).toInt)
    val hi = deltas((0.975 * BootstrapResamples).toInt - 1)
    // two-sided bootstrap p-value for H0: delta = 0
    val p = math.min(1.0, 2 * math.min(deltas.count(_ <= 0), deltas.count(_ >= 0)).toDouble / BootstrapResamples)
    (observed, lo, hi, p)
  }

  // ------------------------------------------------------------------ main

  def main(args: Array[String]): Unit = {
    val spark = Pipeline.session("ExperimentRunner-Phase18b")

    println("=== Building features (Phases 12-15b) ===")
    val financialRDD = StartupRDD.extractFinancialFeaturesRDD(StartupRDD.load(spark, Pipeline.inputPath(args)))
    val sentimentRDD = Aggregation.toSentimentFeaturesRDD(NewsRDD.load(spark).groupByKey(), Pipeline.Cutoff)
    val combinedRDD: RDD[(String, CombinedFeatures)] = FeatureJoin.join(financialRDD, sentimentRDD, Pipeline.Cutoff)
    val cohort = NewsRDD.loadCohort(spark).collect().toSet
    val vocab = FeatureSets.buildVocab(combinedRDD)
    println(s"Full population: ${combinedRDD.count()}; news cohort: ${cohort.size}")
    println(s"Top categories: ${vocab.categories.mkString(", ")}")
    println(s"Top countries: ${vocab.countries.mkString(", ")}")

    val folds = assignFolds(combinedRDD.map { case (p, cf) => (p, cf.label) }.collect())
    val foldOf = udf((p: String) => folds(p))
    def frame(rdd: RDD[(String, CombinedFeatures)], vec: CombinedFeatures => Array[Double]) =
      FeatureSets.toDataFrame(spark, rdd, vec).withColumn("fold", foldOf(col("permalink"))).persist()

    val cohortRDD = combinedRDD.filter { case (p, _) => cohort.contains(p) }.persist()
    val covered = cohortRDD.filter(_._2.sentiment.newsCount > 0)
    println(s"Cohort: ${cohortRDD.count()} startups, ${cohortRDD.filter(_._2.label == 0).count()} closed, " +
      s"${covered.count()} with >= 1 matched headline " +
      s"(closed: ${covered.filter(_._2.label == 0).count()})")

    val experiments: Seq[(String, String, DataFrame)] = Seq(
      ("full", "Financial (original)", frame(combinedRDD, cf => FeatureSets.financialArray(cf.financial))),
      ("full", "Financial (leak-free)", frame(combinedRDD, FeatureSets.safe(vocab))),
      ("cohort", "Leak-free", frame(cohortRDD, FeatureSets.safe(vocab))),
      ("cohort", "Leak-free + Coverage", frame(cohortRDD, FeatureSets.safeCoverage(vocab))),
      ("cohort", "Leak-free + Sentiment", frame(cohortRDD, FeatureSets.safeSentiment(vocab)))
    )
    val models = Seq("Logistic Regression", "Linear SVM")

    println("\n=== 5-fold cross-validation ===")
    val runs = for { (pop, fs, df) <- experiments; m <- models } yield {
      val (res, oof) = runExperiment(spark, pop, fs, m, df)
      ((pop, fs, m), (res, oof))
    }
    val byKey = runs.toMap

    // Cross-check our rank-based AUC against Spark's own implementation once.
    val (_, sampleOof) = runs.head._2
    val sparkAuc = new BinaryClassificationMetrics(
      spark.sparkContext.parallelize(sampleOof.map(r => (r._3, r._2.toDouble)))).areaUnderROC()
    println(f"\nAUC cross-check on pooled OOF scores: ours=${aucRoc(sampleOof.map(_._3), sampleOof.map(_._2))}%.6f spark=$sparkAuc%.6f")

    val comparisons = Seq(
      ("full", "Financial (original)", "Financial (leak-free)"),
      ("cohort", "Leak-free", "Leak-free + Coverage"),
      ("cohort", "Leak-free + Coverage", "Leak-free + Sentiment"),
      ("cohort", "Leak-free", "Leak-free + Sentiment")
    )
    println("\n=== Paired bootstrap on out-of-fold scores (Delta AUC = B - A) ===")
    val sig = for { (pop, a, b) <- comparisons; m <- models } yield {
      val (d, lo, hi, p) = pairedBootstrap(byKey((pop, a, m))._2, byKey((pop, b, m))._2)
      println(f"  $pop%-7s $m%-20s [$a] -> [$b]: dAUC=$d%+.4f  95%% CI [$lo%+.4f, $hi%+.4f]  p=$p%.3f")
      (pop, m, a, b, d, lo, hi, p)
    }

    // ---------------------------------------------------------------- outputs
    def write(path: String)(body: PrintWriter => Unit): Unit = {
      val f = new File(path); f.getParentFile.mkdirs()
      val w = new PrintWriter(f); try body(w) finally w.close()
      println(s"Wrote $path")
    }
    val all = runs.flatMap(_._2._1)
    write("../results/cv_folds.csv") { w =>
      w.println("population,feature_set,model,fold,test_count,test_closed,auc,pr_auc_closed,threshold,precision_closed,recall_closed,f1_closed,balanced_accuracy")
      all.foreach(r => w.println(Seq(r.population, r.featureSet, r.model, r.fold, r.testCount, r.testClosed,
        r.auc, r.prAucClosed, r.threshold, r.precisionClosed, r.recallClosed, r.f1Closed, r.balancedAccuracy).mkString(",")))
    }
    def ms(xs: Seq[Double]) = { val m = xs.sum / xs.size; (m, math.sqrt(xs.map(x => (x - m) * (x - m)).sum / (xs.size - 1))) }
    write("../results/cv_summary.csv") { w =>
      w.println("population,feature_set,model,n,n_closed,auc_mean,auc_sd,pr_auc_closed_mean,pr_auc_closed_sd,f1_closed_mean,f1_closed_sd,recall_closed_mean,precision_closed_mean,balanced_accuracy_mean,balanced_accuracy_sd")
      all.groupBy(r => (r.population, r.featureSet, r.model)).toSeq.sortBy(k => runs.indexWhere(_._1 == k._1)).foreach { case ((p, f, m), rs) =>
        val (a, asd) = ms(rs.map(_.auc)); val (pr, prsd) = ms(rs.map(_.prAucClosed))
        val (f1, f1sd) = ms(rs.map(_.f1Closed)); val (ba, basd) = ms(rs.map(_.balancedAccuracy))
        w.println(Seq(p, f, m, rs.map(_.testCount).sum, rs.map(_.testClosed).sum, a, asd, pr, prsd, f1, f1sd,
          ms(rs.map(_.recallClosed))._1, ms(rs.map(_.precisionClosed))._1, ba, basd).mkString(","))
      }
    }
    write("../results/significance.csv") { w =>
      w.println("population,model,feature_set_a,feature_set_b,delta_auc,ci_low,ci_high,p_value")
      sig.foreach { case (p, m, a, b, d, lo, hi, pv) => w.println(Seq(p, m, a, b, d, lo, hi, pv).mkString(",")) }
    }

    // Interpretable model: LR on the whole cohort, full Leak-free + Sentiment
    // set, standardised features so coefficient magnitudes are comparable.
    val names = FeatureSets.safeFeatureNames(vocab) ++ FeatureSets.CoverageFeatureNames ++ FeatureSets.ToneFeatureNames
    val cohortDf = experiments.last._3
    val scaler = new org.apache.spark.ml.feature.StandardScaler().setInputCol("features").setOutputCol("scaled")
      .setWithMean(true).setWithStd(true).fit(cohortDf)
    val lrAll = new SparkLogisticRegression().setFeaturesCol("scaled").setWeightCol("weight")
      .setMaxIter(100).setRegParam(0.01).fit(Training.withBalancedWeights(scaler.transform(cohortDf))._1)
    write("../results/cohort_lr_coefficients.csv") { w =>
      w.println("feature,standardised_coefficient")
      names.zip(lrAll.coefficients.toArray).sortBy(-_._2.abs).foreach { case (n, c) => w.println(s"$n,$c") }
    }

    // Phase 20b - leak-free models for the web demo's live predictor.
    // Spark returns LR coefficients on the ORIGINAL feature scale, so
    // web/Server.scala can apply them to raw inputs directly.
    def fitLr(df: DataFrame) = new SparkLogisticRegression().setWeightCol("weight").setMaxIter(100)
      .setRegParam(0.01).fit(Training.withBalancedWeights(df)._1)
    def json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    def modelJson(m: org.apache.spark.ml.classification.LogisticRegressionModel, featureNames: Array[String]) =
      s"""{"intercept":${m.intercept},"features":[${featureNames.zip(m.coefficients.toArray)
        .map { case (n, c) => s"""{"name":${json(n)},"coefficient":$c}""" }.mkString(",")}]}"""
    val financialModel = fitLr(experiments(1)._3)
    val sentimentModel = fitLr(cohortDf)
    write("../results/model_coefficients_leakfree.json") { w =>
      w.println(s"""{
        |  "generatedNote": "Leak-free Logistic Regression models from Phase 18b's ExperimentRunner (balanced class weights, L2 0.01). financialOnly: all 22,075 startups, leak-free financial features. financialSentiment: news cohort, leak-free + coverage + tone features.",
        |  "cutoff": "2013-01-01",
        |  "categories": [${vocab.categories.map(json).mkString(",")}],
        |  "countries": [${vocab.countries.map(json).mkString(",")}],
        |  "financialOnly": ${modelJson(financialModel, FeatureSets.safeFeatureNames(vocab))},
        |  "financialSentiment": ${modelJson(sentimentModel, names)}
        |}""".stripMargin)
    }
    spark.stop()
  }
}
