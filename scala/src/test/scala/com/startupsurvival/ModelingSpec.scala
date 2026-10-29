package com.startupsurvival

import java.time.LocalDate
import org.scalatest.funsuite.AnyFunSuite
import com.startupsurvival.models.StartupRecord
import com.startupsurvival.ml.ExperimentRunner

/** Phases 11/11b (financial features and the leakage guard), Phase 13b
 * (collection order) and Phase 18b (metrics, folds, bootstrap). */
class ModelingSpec extends AnyFunSuite {

  private val T = LocalDate.of(2013, 1, 1)

  private def startup(permalink: String = "/organization/a", label: Int = 1,
      funding: Option[Double] = Some(5000000.0), rounds: Option[Int] = Some(3),
      founded: String = "2010-01-01", firstFunding: String = "2011-01-01",
      lastFunding: Option[String] = Some("2012-06-01")) =
    StartupRecord(permalink, "A", None, Some("Software|Mobile"), Some("USA"), None, None, None,
      funding, rounds, LocalDate.parse(founded), LocalDate.parse(firstFunding),
      lastFunding.map(LocalDate.parse), label, T)

  // -------------------------------------------- Phase 11 / 11b features

  test("Phase 11 case A: leakage-safe funding is used") {
    val f = FinancialFeatureExtractor.extractFinancialFeatures(startup())
    assert(f.totalFundingUsd.contains(5000000.0))
    assert(math.abs(f.averageRoundSizeUsd.get - 5000000.0 / 3) < 1e-6)
  }

  test("Phase 11 case B: post-cutoff funding totals are withheld") {
    val f = FinancialFeatureExtractor.extractFinancialFeatures(startup(funding = Some(5e7), lastFunding = None))
    assert(f.totalFundingUsd.isEmpty && f.fundingRoundCount.isEmpty && !f.fundingDataLeakageSafe)
  }

  test("Phase 11b fields depend only on founding and first-funding dates") {
    val safe = FinancialFeatureExtractor.extractFinancialFeatures(startup())
    val unsafe = FinancialFeatureExtractor.extractFinancialFeatures(startup(funding = Some(5e7), rounds = Some(9), lastFunding = None))
    // identical known-at-cutoff inputs => identical Phase 11b values, whatever happened after T
    assert(safe.yearsSinceFirstFunding == unsafe.yearsSinceFirstFunding)
    assert(safe.yearsFoundedToFirstFunding == unsafe.yearsFoundedToFirstFunding)
    assert(math.abs(safe.yearsSinceFirstFunding - 2.0) < 0.01)
    assert(math.abs(safe.yearsFoundedToFirstFunding - 1.0) < 0.01)
  }

  test("StartupRecord rejects a founding date on/after the cutoff") {
    assertThrows[IllegalArgumentException](startup(founded = "2013-01-01"))
  }

  // ------------------------------------------- Phase 13b collection order

  test("collection order is deterministic and every prefix is class-balanced") {
    val pop = (1 to 40).map(i => startup(permalink = f"/organization/c$i%02d", label = 0)).toList ++
      (1 to 200).map(i => startup(permalink = f"/organization/o$i%03d", label = 1)).toList
    val a = BatchNewsCollector.collectionOrder(pop)
    val b = BatchNewsCollector.collectionOrder(pop.reverse)
    assert(a.map(_.permalink) == b.map(_.permalink))
    Seq(10, 50, 80).foreach { n =>
      val closed = a.take(n).count(_.label == 0)
      assert(math.abs(closed - n / 2.0) <= 1)
    }
    assert(a.length == pop.length && a.map(_.permalink).toSet == pop.map(_.permalink).toSet)
  }

  test("TSV cleaning removes tabs/newlines and decodes entities") {
    assert(BatchNewsCollector.clean("A &amp; B\tC\nD") == "A & B C D")
  }

  // ----------------------------------------------- Phase 18b metrics

  import ExperimentRunner._

  test("AUC: perfect, inverted, and all-tied rankings") {
    val labels = Array(0, 0, 1, 1)
    assert(aucRoc(Array(0.1, 0.2, 0.8, 0.9), labels) == 1.0)
    assert(aucRoc(Array(0.9, 0.8, 0.2, 0.1), labels) == 0.0)
    assert(aucRoc(Array(0.5, 0.5, 0.5, 0.5), labels) == 0.5)
  }

  test("AUC matches a hand-computed Mann-Whitney value") {
    val s = Array(0.3, 0.35, 0.4, 0.8, 0.2)
    val l = Array(0, 1, 1, 1, 0)
    // positives {0.35,0.4,0.8} vs negatives {0.3,0.2}: all 6 pairs ordered correctly
    assert(aucRoc(s, l) == 1.0)
    val s2 = Array(0.5, 0.1, 0.4, 0.6)
    val l2 = Array(0, 1, 1, 0)
    // positives {0.1,0.4} vs negatives {0.5,0.6}: 0 of 4 correct
    assert(aucRoc(s2, l2) == 0.0)
    val s3 = Array(0.5, 0.1, 0.7, 0.6)
    // positives {0.1,0.7} vs negatives {0.5,0.6}: 2 of 4 correct
    assert(aucRoc(s3, l2) == 0.5)
  }

  test("weighted AUC equals AUC on the explicitly duplicated rows") {
    val s = Array(0.1, 0.4, 0.35, 0.8)
    val l = Array(0, 0, 1, 1)
    val w = Array(2, 1, 3, 1)
    val dupS = s.indices.flatMap(i => Seq.fill(w(i))(s(i))).toArray
    val dupL = l.indices.flatMap(i => Seq.fill(w(i))(l(i))).toArray
    assert(math.abs(weightedAuc(scoreOrder(s), s, l, w) - aucRoc(dupS, dupL)) < 1e-12)
  }

  test("average precision for the closed class") {
    // closed startups ranked first => AP = 1
    assert(averagePrecisionClosed(Array(0.1, 0.2, 0.8, 0.9), Array(0, 0, 1, 1)) == 1.0)
    // ranking: 0.1(op) 0.2(closed) 0.8(closed) => precisions 1/2, 2/3 => AP = 7/12
    assert(math.abs(averagePrecisionClosed(Array(0.1, 0.2, 0.8), Array(1, 0, 0)) - 7.0 / 12) < 1e-12)
  }

  test("threshold tuning separates perfectly separable scores") {
    val s = Array(0.1, 0.2, 0.3, 0.7, 0.8, 0.9)
    val l = Array(0, 0, 0, 1, 1, 1)
    assert(f1Closed(s, l, tuneThreshold(s, l)) == 1.0)
  }

  test("stratified folds: every startup in exactly one fold, closed share balanced") {
    val keys = ((1 to 50).map(i => (s"c$i", 0)) ++ (1 to 450).map(i => (s"o$i", 1))).toArray
    val folds = assignFolds(keys)
    assert(folds.size == 500 && folds.values.toSet == Set(0, 1, 2, 3, 4))
    (0 until 5).foreach { f =>
      assert(keys.count { case (p, l) => l == 0 && folds(p) == f } == 10)
      assert(keys.count { case (p, _) => folds(p) == f } == 100)
    }
  }

  test("paired bootstrap of identical score sets gives a zero difference") {
    val rows = (1 to 200).map(i => (s"s$i", if (i % 4 == 0) 0 else 1, (i * 37 % 101).toDouble)).toArray
    val (d, lo, hi, p) = pairedBootstrap(rows, rows)
    assert(d == 0.0 && lo == 0.0 && hi == 0.0 && p == 1.0)
  }

  test("paired bootstrap detects a clearly better scorer") {
    val rnd = new scala.util.Random(1)
    val labels = (1 to 600).map(i => if (i % 3 == 0) 0 else 1)
    val noisy = labels.zipWithIndex.map { case (l, i) => (s"s$i", l, rnd.nextGaussian()) }.toArray
    val good = labels.zipWithIndex.map { case (l, i) => (s"s$i", l, l * 2.0 + rnd.nextGaussian()) }.toArray
    val (d, lo, _, p) = pairedBootstrap(noisy, good)
    assert(d > 0.3 && lo > 0 && p < 0.01)
  }

  // ------------------------------------------------- Phase 20 web demo

  test("web demo's leak-free models match FeatureSets and give probabilities") {
    val models = com.startupsurvival.web.Server.loadModels("../results/model_coefficients_leakfree.json")
    val req = com.startupsurvival.web.Server.PredictRequest("2019-01-01", "2020-01-01", Some(List("Software")), Some("USA"),
      Some(List(com.startupsurvival.web.Server.Headline("2020-02-01", "Acme raises funding"))))
    val r = com.startupsurvival.web.Server.predict(req, models)
    val p = r("financialOnly").asInstanceOf[Double]
    assert(p > 0 && p < 1)
    assert(r("financialSentiment").asInstanceOf[Option[Double]].exists(x => x > 0 && x < 1))
  }

  test("case studies are scored two years after first funding, or six months before closing") {
    import com.startupsurvival.web.Tracker.{Profile, caseStudyAsOf, validate}
    val quibi = Profile("Quibi", "2018-08-01", "2018-08-01", List("Video"), Some("USA"), Some("closed"), Some("2020-10-21"))
    assert(caseStudyAsOf(quibi) == LocalDate.of(2020, 4, 21))
    assert(caseStudyAsOf(quibi.copy(outcome = Some("operating"))) == LocalDate.of(2020, 8, 1))
    validate(quibi)
    assertThrows[IllegalArgumentException](validate(quibi.copy(name = "<script>")))
  }

  test("maturity warning levels") {
    val m = com.startupsurvival.web.Server.Maturity.Design
    assert(m.describe(2.0)("level") == "young")
    assert(m.describe(6.0)("level") == "older")
    assert(m.describe(15.0)("level") == "established")
  }

  test("structural forest: features match FeatureSets, scores are probabilities, threshold is exported") {
    val models = com.startupsurvival.web.Server.loadModels("../results/model_coefficients_leakfree.json", Some("../results/model_structural.json"))
    assert(models.structural.isDefined && models.structuralName.startsWith("random forest"))
    val x = com.startupsurvival.spark.FeatureSets.financialSafeArray(models.vocab)(
      FinancialFeatureExtractor.extractFinancialFeatures(startup()))
    val p = models.structuralProbability(x)
    assert(p > 0 && p < 1 && models.threshold > 0.3 && models.threshold < 0.7)
  }

  test("forest evaluation follows splits left when x <= threshold") {
    import com.startupsurvival.web.Forest
    val f = Forest(List("a"), Nil, Nil, 0.5, Vector(Forest.Split(0, 1.0, Forest.Leaf(0.2), Forest.Leaf(0.8)), Forest.Leaf(0.6)))
    assert(math.abs(f.probability(Array(1.0)) - 0.4) < 1e-12)
    assert(math.abs(f.probability(Array(1.5)) - 0.7) < 1e-12)
  }
}
