package com.startupsurvival

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.startupsurvival.models.{StartupRecord, FinancialFeatures}

/**
 * PHASE 11 - Financial (Group A) features.
 *
 * Leakage guard: fundingTotalUsd and fundingRounds are lifetime totals from a
 * ~2014 snapshot, not pre-cutoff figures. lastFundingAt is the latest funding
 * date and Phase 2 nulled it when it fell on/after the cutoff, so when it is
 * defined every round is guaranteed pre-cutoff and the totals are safe.
 * Otherwise they are set to None rather than leaked.
 *
 * Not computed: investor counts and largest round (no per-round data).
 */
object FinancialFeatureExtractor {

  private val DaysPerYear = 365.25

  def extractFinancialFeatures(startup: StartupRecord): FinancialFeatures = {
    val ageYears = ChronoUnit.DAYS.between(startup.foundedAt, startup.predictionCutoff) / DaysPerYear
    val leakageSafe = startup.lastFundingAt.isDefined
    val totalFundingUsd = startup.fundingTotalUsd.filter(_ => leakageSafe)
    val fundingRoundCount = startup.fundingRounds.filter(_ => leakageSafe)

    FinancialFeatures(
      permalink = startup.permalink,
      label = startup.label,
      startupAgeYears = ageYears,
      totalFundingUsd = totalFundingUsd,
      fundingRoundCount = fundingRoundCount,
      averageRoundSizeUsd = for {
        total  <- totalFundingUsd
        rounds <- fundingRoundCount if rounds > 0
      } yield total / rounds,
      timeSinceLastFundingDays = startup.lastFundingAt.map(ChronoUnit.DAYS.between(_, startup.predictionCutoff)),
      // Floor of one day avoids dividing by ~0 for startups founded right before the cutoff.
      fundingFrequencyPerYear = fundingRoundCount.map(_ / math.max(ageYears, 1.0 / DaysPerYear)),
      hasFundingAmountRecorded = startup.fundingTotalUsd.isDefined,
      fundingDataLeakageSafe = leakageSafe,
      categoryList = startup.categoryList,
      countryCode = startup.countryCode
    )
  }

  /** Self-check of the leakage guard on three hand-built records. */
  def main(args: Array[String]): Unit = {
    def startup(funding: Option[Double], rounds: Int, founded: String, last: Option[String]) =
      StartupRecord(
        permalink = "/organization/example", name = "Example", homepageUrl = None,
        categoryList = None, countryCode = None, stateCode = None, region = None, city = None,
        fundingTotalUsd = funding, fundingRounds = Some(rounds),
        foundedAt = LocalDate.parse(founded), firstFundingAt = LocalDate.parse(founded),
        lastFundingAt = last.map(LocalDate.parse), label = 1,
        predictionCutoff = StartupRecord.PredictionCutoff
      )

    val safe = extractFinancialFeatures(startup(Some(5e6), 3, "2008-01-01", Some("2012-06-01")))
    val unsafe = extractFinancialFeatures(startup(Some(5e7), 5, "2005-01-01", None))
    val noAmount = extractFinancialFeatures(startup(None, 1, "2011-01-01", Some("2011-06-01")))
    Seq(safe, unsafe, noAmount).foreach(println)

    assert(safe.totalFundingUsd.contains(5e6) && safe.fundingRoundCount.contains(3), "safe totals must be kept")
    assert(unsafe.totalFundingUsd.isEmpty && unsafe.fundingRoundCount.isEmpty && !unsafe.fundingDataLeakageSafe,
      "lifetime totals without a pre-cutoff lastFundingAt must be nulled")
    assert(!noAmount.hasFundingAmountRecorded && noAmount.fundingDataLeakageSafe,
      "missing-amount flag is independent of the leakage guard")
    println("Leakage guard checks passed")
  }
}
