package com.startupsurvival.ml

import org.apache.spark.ml.classification.{LogisticRegression => SparkLogisticRegression}

/** PHASE 16 - Spark ML logistic regression for Experiments 1 and 3. */
object LogisticRegression {

  // Pure L2: the features are hand-engineered, so there is nothing for L1 to prune.
  private def estimator =
    new SparkLogisticRegression()
      .setWeightCol("weight")
      .setMaxIter(100)
      .setRegParam(0.01)
      .setElasticNetParam(0.0)

  def trainAndPredict(split: Training.Split): Training.Trained =
    Training.fit(estimator, split)(m => (m.intercept, m.coefficients))

  def main(args: Array[String]): Unit =
    Training.demo("LogisticRegression-Phase16", args, "probability")(trainAndPredict)
}
