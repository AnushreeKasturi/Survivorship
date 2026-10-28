package com.startupsurvival.ml

import org.apache.spark.ml.classification.LinearSVC

/**
 * PHASE 17 - Spark ML LinearSVC for Experiments 2 and 4.
 *
 * rawPrediction is a signed margin, not a probability, so only LR's
 * weights are exported for the live demo.
 */
object SVM {

  // Same regularisation as LogisticRegression for a like-for-like comparison.
  private def estimator =
    new LinearSVC()
      .setWeightCol("weight")
      .setMaxIter(100)
      .setRegParam(0.01)

  def trainAndPredict(split: Training.Split): Training.Trained =
    Training.fit(estimator, split)(m => (m.intercept, m.coefficients))

  def main(args: Array[String]): Unit =
    Training.demo("SVM-Phase17", args, "rawPrediction")(trainAndPredict)
}
