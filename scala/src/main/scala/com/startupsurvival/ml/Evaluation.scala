package com.startupsurvival.ml

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import org.apache.spark.ml.linalg.{Vector => MLVector}
import org.apache.spark.mllib.evaluation.{BinaryClassificationMetrics, MulticlassMetrics}
import com.startupsurvival.spark.{FeatureSets, Pipeline}

/**
 * PHASE 18 - Runs all four experiments and writes results/.
 *
 * Uses the RDD-based MulticlassMetrics: one object gives every metric plus
 * the confusion matrix, which the DataFrame evaluators do not expose.
 * Label 1 ("operating") is the positive class. With a ~13/87 split, accuracy
 * alone is weak - label 0 F1 and AUC carry the research question.
 */
object Evaluation {

  final case class Metrics(
      experimentName: String,
      modelType: String,
      featureSet: String,
      testCount: Long,
      accuracy: Double,
      precisionLabel1: Double,
      recallLabel1: Double,
      f1Label1: Double,
      precisionLabel0: Double,
      recallLabel0: Double,
      f1Label0: Double,
      auc: Double,
      truePositive: Double,
      trueNegative: Double,
      falsePositive: Double,
      falseNegative: Double
  )

  def evaluate(trained: Training.Trained, experimentName: String, modelType: String, featureSet: String): Metrics = {
    val predictions = trained.predictions
    import predictions.sparkSession.implicits._

    val multiclass = new MulticlassMetrics(predictions.select("prediction", "label").as[(Double, Double)].rdd)
    val confusion = multiclass.confusionMatrix // rows = actual, cols = predicted, ordered by label value

    // AUC needs a continuous score; rawPrediction(1) is the positive-class score for both models.
    val scoreAndLabels = predictions.select("rawPrediction", "label").rdd.map { row =>
      (row.getAs[MLVector](0)(1), row.getDouble(1))
    }

    Metrics(
      experimentName, modelType, featureSet, trained.split.testCount,
      multiclass.accuracy,
      multiclass.precision(1.0), multiclass.recall(1.0), multiclass.fMeasure(1.0),
      multiclass.precision(0.0), multiclass.recall(0.0), multiclass.fMeasure(0.0),
      new BinaryClassificationMetrics(scoreAndLabels).areaUnderROC(),
      truePositive = confusion(1, 1),
      trueNegative = confusion(0, 0),
      falsePositive = confusion(0, 1),
      falseNegative = confusion(1, 0)
    )
  }

  def printMetrics(m: Metrics): Unit = {
    println(s"--- ${m.experimentName} (${m.modelType}, ${m.featureSet}) ---")
    println(f"Test set size: ${m.testCount}  Accuracy: ${m.accuracy}%.4f  AUC-ROC: ${m.auc}%.4f")
    println(f"Label=1 (operating) - Precision: ${m.precisionLabel1}%.4f  Recall: ${m.recallLabel1}%.4f  F1: ${m.f1Label1}%.4f")
    println(f"Label=0 (closed)    - Precision: ${m.precisionLabel0}%.4f  Recall: ${m.recallLabel0}%.4f  F1: ${m.f1Label0}%.4f")
    println(f"Confusion matrix: TP=${m.truePositive}%.0f  TN=${m.trueNegative}%.0f  FP=${m.falsePositive}%.0f  FN=${m.falseNegative}%.0f\n")
  }

  /** Paths are relative to scala/, where sbt runs. */
  private def writeFile(path: String, content: String): Unit = {
    val file = Paths.get(path)
    Option(file.getParent).foreach(Files.createDirectories(_))
    Files.write(file, content.getBytes(UTF_8))
    println(s"Wrote $path")
  }

  def toCsv(all: Seq[Metrics]): String =
    ("experiment,model,feature_set,test_count,accuracy,auc,precision_label1,recall_label1,f1_label1," +
      "precision_label0,recall_label0,f1_label0,tp,tn,fp,fn") +: all.map { m =>
      Seq(
        m.experimentName, m.modelType, m.featureSet, m.testCount,
        m.accuracy, m.auc, m.precisionLabel1, m.recallLabel1, m.f1Label1,
        m.precisionLabel0, m.recallLabel0, m.f1Label0,
        m.truePositive, m.trueNegative, m.falsePositive, m.falseNegative
      ).mkString(",")
    } mkString("", "\n", "\n")

  /** LR weights only: the live demo needs a probability, which LinearSVC does not give. */
  def coefficientsJson(financialOnly: Training.Trained, financialSentiment: Training.Trained): String = {
    def modelJson(t: Training.Trained, names: Array[String]): String =
      names.zip(t.coefficients)
        .map { case (name, c) => s"""{"name":"$name","coefficient":$c}""" }
        .mkString(s"""{"intercept":${t.intercept},"features":[""", ",", "]}")

    s"""{
       |  "generatedNote": "Real trained Logistic Regression coefficients from Phase 16, exported by Phase 18's Evaluation.scala. Used by the Phase 20 web demo live predictor - not a separate or fabricated model.",
       |  "financialOnly": ${modelJson(financialOnly, FeatureSets.FinancialFeatureNames)},
       |  "financialSentiment": ${modelJson(financialSentiment, FeatureSets.FinancialFeatureNames ++ FeatureSets.SentimentFeatureNames)}
       |}
       |""".stripMargin
  }

  def main(args: Array[String]): Unit = {
    val spark = Pipeline.session("Evaluation-Phase18")
    val (financialOnlyDF, financialSentimentDF) = Pipeline.featureSets(spark, Pipeline.inputPath(args))

    // Each feature set is split once and shared by both models.
    val financial = Training.split(financialOnlyDF)
    val financialSentiment = Training.split(financialSentimentDF)

    val exp1 = LogisticRegression.trainAndPredict(financial)
    val exp3 = LogisticRegression.trainAndPredict(financialSentiment)
    val all = Seq(
      evaluate(exp1, "Experiment 1", "Logistic Regression", "Financial-only"),
      evaluate(SVM.trainAndPredict(financial), "Experiment 2", "Binary SVM", "Financial-only"),
      evaluate(exp3, "Experiment 3", "Logistic Regression", "Financial+Sentiment"),
      evaluate(SVM.trainAndPredict(financialSentiment), "Experiment 4", "Binary SVM", "Financial+Sentiment")
    )
    all.foreach(printMetrics)

    println(f"${"Experiment"}%-14s ${"Model"}%-22s ${"Features"}%-22s ${"Accuracy"}%-10s ${"AUC"}%-8s ${"F1(label=0)"}%-12s")
    all.foreach { m =>
      println(f"${m.experimentName}%-14s ${m.modelType}%-22s ${m.featureSet}%-22s ${m.accuracy}%-10.4f ${m.auc}%-8.4f ${m.f1Label0}%-12.4f")
    }
    println("Research question: compare 1 vs 3 and 2 vs 4 on F1(label=0) and AUC.")

    writeFile("../results/experiment_results.csv", toCsv(all))
    writeFile("../results/model_coefficients.json", coefficientsJson(exp1, exp3))

    spark.stop()
  }
}
