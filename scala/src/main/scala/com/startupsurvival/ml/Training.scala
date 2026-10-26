package com.startupsurvival.ml

import org.apache.spark.ml.{Estimator, Model}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, lit, when}
import com.startupsurvival.spark.{FeatureSets, Pipeline}

/** Train/test split and balanced class weights shared by Phases 16-18. */
object Training {

  /** One fixed split for all four experiments, so they are compared on the same startups. */
  val RandomSeed: Long = 42L
  val TrainFraction: Double = 0.8

  final case class Split(train: DataFrame, test: DataFrame, trainCount: Long, testCount: Long)

  final case class Trained(intercept: Double, coefficients: Array[Double], predictions: DataFrame, split: Split)

  /**
   * Adds a `weight` column of balanced class weights, total / (2 * classCount).
   *
   * Phase 18's first run showed unweighted models predicting "operating" for
   * almost everything (label 0 recall 0.0018) - with a ~13/87 class split that
   * already scores ~87% accuracy. Balanced weights make both classes contribute
   * equally to the loss. Callers pass training rows only, so held-out labels
   * never influence training. Returns the row count too.
   */
  def withBalancedWeights(train: DataFrame): (DataFrame, Long) = {
    val counts = train.groupBy("label").count().collect().map(r => r.getDouble(0) -> r.getLong(1)).toMap
    val positiveCount = counts.getOrElse(1.0, 0L)
    val negativeCount = counts.getOrElse(0.0, 0L)
    require(positiveCount > 0 && negativeCount > 0,
      s"one class is empty in the training data (label=1: $positiveCount, label=0: $negativeCount)")

    val total = positiveCount + negativeCount
    val weightForPositive = total.toDouble / (2.0 * positiveCount)
    val weightForNegative = total.toDouble / (2.0 * negativeCount)
    println(f"  Class weights: label=1 (n=$positiveCount) -> $weightForPositive%.4f, label=0 (n=$negativeCount) -> $weightForNegative%.4f")

    (train.withColumn("weight", when(col("label") === 1.0, lit(weightForPositive)).otherwise(lit(weightForNegative))), total)
  }

  /** Splits, then weights the training half only. */
  def split(featureDF: DataFrame): Split = {
    val Array(train, test) = featureDF.randomSplit(Array(TrainFraction, 1 - TrainFraction), seed = RandomSeed)
    val (weighted, trainCount) = withBalancedWeights(train)
    Split(weighted.persist(), test.persist(), trainCount, test.count())
  }

  /** Fits on the training half and predicts the held-out half only. */
  def fit[M <: Model[M]](estimator: Estimator[M], split: Split)(weightsOf: M => (Double, Vector)): Trained = {
    val model = estimator.fit(split.train)
    val (intercept, coefficients) = weightsOf(model)
    Trained(intercept, coefficients.toArray, model.transform(split.test), split)
  }

  /** Shared main for LogisticRegression and SVM: train on both feature sets and print the weights. */
  def demo(appName: String, args: Array[String], scoreColumn: String)(train: Split => Trained): Unit = {
    val spark = Pipeline.session(appName)
    val (financialOnlyDF, financialSentimentDF) = Pipeline.featureSets(spark, Pipeline.inputPath(args))

    for ((featureSet, df, names) <- Seq(
      ("Financial-only", financialOnlyDF, FeatureSets.FinancialFeatureNames),
      ("Financial+Sentiment", financialSentimentDF, FeatureSets.FinancialFeatureNames ++ FeatureSets.SentimentFeatureNames)
    )) {
      println(s"\n=== $appName: $featureSet ===")
      val result = train(split(df))
      println(s"Train rows: ${result.split.trainCount}, test rows: ${result.split.testCount}")
      println(s"Intercept: ${result.intercept}")
      names.zip(result.coefficients).foreach { case (name, c) => println(f"  $name%-28s $c%+.6f") }
      result.predictions.select("permalink", "label", "prediction", scoreColumn).show(10, truncate = false)
    }

    spark.stop()
  }
}
