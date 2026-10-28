package com.startupsurvival

import java.time.LocalDate
import scala.io.Source
import scala.util.Try
import com.startupsurvival.models.StartupRecord

/**
 * PHASE 6 - Parses Phase 2's labeled_eligible_startups.csv into StartupRecords.
 *
 * Plain Scala, not Spark: Phase 12 reuses these pure functions inside RDDs.
 * The CSV parser is quote-aware on purpose - Phase 4 found a naive comma
 * split corrupting names like "Foo, Inc".
 */
object DataCleaner {

  // Fields are accumulated reversed (O(1) prepend) and flipped once at the end.
  private case class ParseState(fields: List[String], current: List[Char], inQuotes: Boolean)

  def parseCsvLine(line: String): List[String] = {
    val end = line.foldLeft(ParseState(Nil, Nil, inQuotes = false)) { (state, ch) =>
      (ch, state.inQuotes) match {
        case ('"', _)     => state.copy(inQuotes = !state.inQuotes)
        case (',', false) => state.copy(fields = state.current.reverse.mkString :: state.fields, current = Nil)
        case (c, _)       => state.copy(current = c :: state.current)
      }
    }
    (end.current.reverse.mkString :: end.fields).reverse
  }

  /** "-" is the dataset's missing-funding sentinel (~19% of rows, Phase 1). */
  def parseFundingUsd(raw: String): Option[Double] = raw.trim match {
    case "" | "-" => None
    case s        => Try(s.toDouble).toOption
  }

  def parseOptionalString(raw: String): Option[String] = raw.trim match {
    case "" | "NULL" | "null" => None
    case s                    => Some(s)
  }

  def parseOptionalInt(raw: String): Option[Int] = Try(raw.trim.toInt).toOption

  def parseDate(raw: String): Option[LocalDate] = Try(LocalDate.parse(raw.trim)).toOption

  /** None if the column count is wrong or any required field fails to parse. */
  def rowToStartupRecord(fields: List[String]): Option[StartupRecord] =
    fields match {
      case permalink :: name :: homepageUrl :: categoryList :: fundingTotalUsd ::
          countryCode :: stateCode :: region :: city :: fundingRounds ::
          foundedAt :: firstFundingAt :: lastFundingAt :: label ::
          predictionCutoff :: Nil =>
        for {
          founded      <- parseDate(foundedAt)
          firstFunding <- parseDate(firstFundingAt)
          cutoff       <- parseDate(predictionCutoff)
          lbl          <- parseOptionalInt(label)
        } yield StartupRecord(
          permalink = permalink,
          name = name,
          homepageUrl = parseOptionalString(homepageUrl),
          categoryList = parseOptionalString(categoryList),
          countryCode = parseOptionalString(countryCode),
          stateCode = parseOptionalString(stateCode),
          region = parseOptionalString(region),
          city = parseOptionalString(city),
          fundingTotalUsd = parseFundingUsd(fundingTotalUsd),
          fundingRounds = parseOptionalInt(fundingRounds),
          foundedAt = founded,
          firstFundingAt = firstFunding,
          lastFundingAt = parseDate(lastFundingAt),
          label = lbl,
          predictionCutoff = cutoff
        )
      case _ => None
    }

  def loadStartups(path: String): List[StartupRecord] = {
    val source = Source.fromFile(path)
    try source.getLines().drop(1).map(parseCsvLine).flatMap(rowToStartupRecord).toList
    finally source.close()
  }

  def summarize(records: List[StartupRecord]): Unit = {
    val total = records.length
    val (closed, operating) = records.partition(_.label == 0)
    val missingFunding = records.count(_.fundingTotalUsd.isEmpty)
    val knownFunding = records.flatMap(_.fundingTotalUsd)
    val avgFunding = if (knownFunding.isEmpty) 0.0 else knownFunding.sum / knownFunding.length

    println(s"Loaded $total startup records")
    println(s"  closed    (label 0): ${closed.length}")
    println(s"  operating (label 1): ${operating.length}")
    println(f"  missing funding_total_usd: $missingFunding (${missingFunding.toDouble / total * 100}%.1f%%)")
    println(f"  average funding (where known): $$${avgFunding}%,.0f")
  }

  def main(args: Array[String]): Unit = {
    val path = args.headOption.getOrElse("../data/processed/labeled_eligible_startups.csv")
    println(s"Loading startups from: $path")
    summarize(loadStartups(path))
  }
}
