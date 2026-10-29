package com.startupsurvival.ml

import java.io.{File, PrintWriter}
import java.nio.file.Files
import org.apache.spark.ml.classification.RandomForestClassificationModel
import org.apache.spark.sql.SparkSession
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization.write
import com.startupsurvival.spark.FeatureSets

/**
 * Writes a Spark random forest as plain JSON the web server can evaluate
 * without Spark: each tree is nested arrays, [feature, threshold, left, right]
 * for a split (left when x(feature) <= threshold) and a bare number, the
 * leaf's P(operating), for a leaf. P(operating) is the mean over trees, which
 * is what RandomForestClassificationModel computes.
 *
 * Leaf class counts are private in Spark's API, so the model goes through its
 * public save format (Parquet, one row per node) and is rebuilt from there.
 */
object ModelExport {

  def forestTrees(spark: SparkSession, model: RandomForestClassificationModel): Seq[Any] = {
    val dir = Files.createTempDirectory("forest").resolve("model").toString
    model.write.save(dir)
    val nodes = spark.read.parquet(s"$dir/data").collect().map { r =>
      val n = r.getStruct(r.fieldIndex("nodeData"))
      (r.getInt(r.fieldIndex("treeID")), n.getInt(n.fieldIndex("id"))) -> n
    }.toMap
    def build(tree: Int, id: Int): Any = {
      val n = nodes((tree, id))
      val left = n.getInt(n.fieldIndex("leftChild"))
      if (left == -1) {
        val stats = n.getSeq[Double](n.fieldIndex("impurityStats"))
        if (stats.sum == 0) 0.5 else stats(1) / stats.sum
      } else {
        val split = n.getStruct(n.fieldIndex("split"))
        require(split.getInt(split.fieldIndex("numCategories")) == -1, "categorical splits are not supported")
        List(split.getInt(split.fieldIndex("featureIndex")), split.getSeq[Double](split.fieldIndex("leftCategoriesOrThreshold")).head,
          build(tree, left), build(tree, n.getInt(n.fieldIndex("rightChild"))))
      }
    }
    model.trees.indices.map(t => build(t, 0))
  }

  def writeForest(spark: SparkSession, model: RandomForestClassificationModel, featureNames: Array[String],
      vocab: FeatureSets.Vocab, threshold: Double, note: String, out: File): Unit = {
    val json = Map(
      "generatedNote" -> note,
      "cutoff" -> "2013-01-01",
      "categories" -> vocab.categories, "countries" -> vocab.countries,
      "features" -> featureNames,
      "threshold" -> threshold,
      "trees" -> forestTrees(spark, model)
    )
    val w = new PrintWriter(out, "UTF-8")
    try w.println(write(json)(DefaultFormats)) finally w.close()
    println(s"Wrote ${out.getPath} (${model.trees.length} trees, threshold $threshold)")
  }

  /** The exported JSON must reproduce Spark's own probabilities on real rows. */
  def verify(model: RandomForestClassificationModel, out: File, rows: org.apache.spark.sql.DataFrame): Unit = {
    import org.apache.spark.ml.linalg.Vector
    val forest = com.startupsurvival.web.Forest.load(out.getPath)
    val worst = model.transform(rows.limit(2000)).select("features", "probability").collect().map { r =>
      math.abs(forest.probability(r.getAs[Vector](0).toArray) - r.getAs[Vector](1)(1))
    }.max
    require(worst < 1e-9, s"exported forest differs from Spark by $worst")
    println(f"Verified: exported forest matches Spark on 2,000 startups (max difference $worst%.2e)")
  }
}
