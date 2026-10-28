package com.startupsurvival

/**
 * PHASE 8 - Decides whether a headline is actually about a company.
 *
 * Google News search results contain noise (Phase 7 returned "The tomato
 * barons of the occupied Western Sahara" for "Kabbage"), so the name must
 * appear in the headline itself. Precision is favoured over recall.
 */
object NameMatcher {

  private val CorporateSuffixes: Set[String] =
    Set("inc", "llc", "ltd", "corp", "corporation", "co", "company",
        "group", "holdings", "plc", "gmbh")

  /** Single word of <= 4 chars: the same "very high risk" threshold as Phase 1. */
  def isAmbiguousName(originalName: String): Boolean = {
    val trimmed = originalName.trim
    !trimmed.exists(_.isWhitespace) && trimmed.nonEmpty && trimmed.length <= 4
  }

  /** Lowercase alphanumeric tokens joined by single spaces, suffixes removed. */
  def normalize(text: String): String =
    text.toLowerCase
      .split("[^a-z0-9]+")
      .filter(t => t.nonEmpty && !CorporateSuffixes.contains(t))
      .mkString(" ")

  // Both sides are normalized, so padding with spaces gives a whole-word match.
  private def containsWholeWord(haystack: String, needle: String): Boolean =
    needle.nonEmpty && s" $haystack ".contains(s" $needle ")

  final case class MatchResult(matched: Boolean, confidence: Double, reason: String)

  /**
   * Ambiguous short names are capped at 0.3; a full phrase match is 1.0;
   * multi-word names with >= half their tokens present get 0.5 + 0.3 * ratio.
   */
  def computeMatchConfidence(companyName: String, headline: String): MatchResult = {
    val normName = normalize(companyName)
    val normHeadline = normalize(headline)
    val nameTokens = normName.split(" ").filter(_.nonEmpty)

    if (nameTokens.isEmpty)
      MatchResult(matched = false, 0.0, "empty normalized name")
    else if (isAmbiguousName(companyName)) {
      if (containsWholeWord(normHeadline, normName))
        MatchResult(matched = true, 0.3, "ambiguous short name (<=4 chars, single word) - capped confidence")
      else
        MatchResult(matched = false, 0.0, "ambiguous short name - no match found")
    } else if (containsWholeWord(normHeadline, normName))
      MatchResult(matched = true, 1.0, "exact phrase match")
    else {
      val matchedCount = nameTokens.count(containsWholeWord(normHeadline, _))
      val ratio = matchedCount.toDouble / nameTokens.length
      val detail = s"($matchedCount/${nameTokens.length} tokens)"
      if (ratio >= 0.5) MatchResult(matched = true, 0.5 + 0.3 * ratio, s"partial token match $detail")
      else MatchResult(matched = false, 0.0, s"insufficient token overlap $detail")
    }
  }

  def filterMatches[A](companyName: String, candidates: List[A])(headlineOf: A => String): List[(A, MatchResult)] =
    candidates
      .map(item => (item, computeMatchConfidence(companyName, headlineOf(item))))
      .filter(_._2.matched)

  /** Self-check against the live Phase 7 Kabbage results. */
  def main(args: Array[String]): Unit = {
    val headlines = List(
      "Kabbage Raises Some Serious Cabbage for Small-Business Loans - WIRED",
      "Kabbage: The Merchant Cash Advance of the Online Business World - deBanked",
      "The tomato barons of the occupied Western Sahara - Western Sahara Resource Watch",
      "Microlender helps e-tailer keep the orders moving | Business - Las Vegas Review-Journal",
      "Funding roundup - week ending 08/19/11 - vator.tv"
    )
    headlines.foreach { h =>
      val r = computeMatchConfidence("Kabbage", h)
      println(s"""[${if (r.matched) "ACCEPT" else "REJECT"}] confidence=${r.confidence}  ${r.reason}\n         "$h"""")
    }
    val accepted = filterMatches("Kabbage", headlines)(identity).map(_._1)
    assert(accepted == headlines.take(2), s"expected only the two Kabbage headlines, got $accepted")
    println(s"Accepted ${accepted.length} of ${headlines.length} headlines (tomato barons rejected)")
  }
}
