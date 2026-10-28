package com.startupsurvival.models

import java.time.LocalDate

final case class StartupRecord(
    permalink: String,
    name: String,
    homepageUrl: Option[String],
    categoryList: Option[String],
    countryCode: Option[String],
    stateCode: Option[String],
    region: Option[String],
    city: Option[String],
    fundingTotalUsd: Option[Double],
    fundingRounds: Option[Int],
    foundedAt: LocalDate,
    firstFundingAt: LocalDate,
    lastFundingAt: Option[LocalDate],
    label: Int,
    predictionCutoff: LocalDate
) {
  require(label == 0 || label == 1, s"label must be 0 or 1, got $label")
  require(!foundedAt.isAfter(predictionCutoff.minusDays(1)),
    s"foundedAt must be strictly before predictionCutoff (leakage guard)")
}

object StartupRecord {
  /** Retrospective cutoff T (Phase 2): features before T, label after. */
  val PredictionCutoff: LocalDate = LocalDate.of(2013, 1, 1)
}
