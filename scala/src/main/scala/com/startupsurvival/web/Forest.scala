package com.startupsurvival.web

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import org.json4s._
import org.json4s.jackson.JsonMethods.parse

/** The structural random forest exported by ml.ModelExport, evaluated without Spark. */
final case class Forest(features: List[String], categories: List[String], countries: List[String],
    threshold: Double, trees: Vector[Forest.Node]) {
  /** P(operating): the mean of the trees' leaf probabilities, as Spark computes it. */
  def probability(x: Array[Double]): Double = {
    require(x.length == features.length, s"expected ${features.length} features, got ${x.length}")
    trees.iterator.map(Forest.leaf(_, x)).sum / trees.size
  }
}

object Forest {
  sealed trait Node
  final case class Split(feature: Int, threshold: Double, left: Node, right: Node) extends Node
  final case class Leaf(p: Double) extends Node

  @annotation.tailrec
  def leaf(n: Node, x: Array[Double]): Double = n match {
    case Leaf(p) => p
    case Split(f, t, l, r) => leaf(if (x(f) <= t) l else r, x)
  }

  private def num(v: JValue): Double = v match {
    case JDouble(d) => d
    case JInt(i) => i.toDouble
    case JDecimal(d) => d.toDouble
    case JLong(l) => l.toDouble
    case other => sys.error(s"not a number: $other")
  }

  private def node(v: JValue): Node = v match {
    case JArray(List(f, t, l, r)) => Split(num(f).toInt, num(t), node(l), node(r))
    case other => Leaf(num(other))
  }

  def load(path: String): Forest = {
    implicit val formats: Formats = DefaultFormats
    val j = parse(new String(Files.readAllBytes(Paths.get(path)), UTF_8))
    val trees = (j \ "trees") match {
      case JArray(ts) => ts.map(node).toVector
      case _ => sys.error("model has no trees")
    }
    Forest((j \ "features").extract[List[String]], (j \ "categories").extract[List[String]],
      (j \ "countries").extract[List[String]], (j \ "threshold").extract[Double], trees)
  }
}
