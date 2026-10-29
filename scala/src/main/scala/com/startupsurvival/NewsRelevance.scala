package com.startupsurvival

/**
 * Decides whether a name-matched headline is news about the company as a
 * business, before its tone is allowed to count.
 *
 * Name matching alone let through toy-figure listings for "Figma", reviews of
 * individual Quibi shows and SEO "statistics" pages. A headline is kept only
 * when it reports a business event (money, funding, customers, hiring or
 * layoffs, deals, legal trouble, a launch, a shutdown), isn't one of the
 * consumer formats below, and doesn't come from a social or user-posted
 * site. Like NameMatcher, it prefers precision: a missed headline costs less
 * than a wrong one.
 *
 * Used by the live tools and case studies. The research pipeline's batch news
 * predates it; re-running ExperimentRunner with it is the next step.
 */
object NewsRelevance {

  final case class Verdict(keep: Boolean, reason: String)

  private val BusinessEvent = ("(?i)\\b(rais(e|es|ed|ing)|fund(s|ing|ed)?|round|seed|series [a-f]|valu(ation|ed)|" +
    "invest(s|ed|ment|or|ors)?|backed|backers|acqui(re|res|red|sition|sitions)|merger|ipo|go(es|ing)? public|" +
    "revenue|profit(s|able)?|loss(es)?|sales|customers?|users|subscribers|growth|" +
    "launch(es|ed)?|expand(s|ed)?|expansion|partner(s|ship|ships)?|deal|contract|" +
    "ceo|cfo|cto|coo|chief|founders?|co-?founders?|executives?|hires?|hiring|appoint(s|ed)?|" +
    "layoffs?|lays off|cuts|shut(s|ting)? down|shutdown|clos(es|ing|ure)|bankrupt(cy)?|insolven(t|cy)|" +
    "lawsuit|sues?|sued|settle(s|ment)?|regulators?|startup|start-up|unicorn|company|firm)\\b").r
  private val Money = "(?i)([$€£₹]\\s?\\d|\\b\\d+(\\.\\d+)?\\s?(million|billion|mn|bn|crore|lakh)\\b)".r

  private val NonNews = ("(?i)\\b(reviews?|preview(ed)?|unboxing|figures?|figurines?|toys?|plush(ies)?|nendoroid|" +
    "on sale|pre-?orders?|best \\w+|top \\d+|\\d+ (things|ways|tips|reasons|facts)|how to|guide|tutorial|tips|" +
    "statistics|stats|alternatives?|vs\\.?|versus|coupons?|promo codes?|lyrics|recipes?|quiz|trailer|episode)\\b").r

  private val SocialSites = Set("reddit", "quora", "youtube", "medium", "facebook", "instagram", "tiktok",
    "x", "twitter", "linkedin", "tumblr", "substack")

  def judge(title: String, source: String = ""): Verdict = {
    val site = source.toLowerCase.replaceAll("^www\\.|\\.com$", "").trim
    if (SocialSites.exists(s => site == s || site.startsWith(s + ".") || site.startsWith(s + " ")))
      Verdict(keep = false, "from a social or user-posted site")
    else NonNews.findFirstIn(title) match {
      case Some(term) => Verdict(keep = false, s"""a listing, review or how-to (“${term.toLowerCase}”), not company news""")
      case None if BusinessEvent.findFirstIn(title).isDefined || Money.findFirstIn(title).isDefined =>
        Verdict(keep = true, "business news")
      case None => Verdict(keep = false, "no business event in the headline")
    }
  }
}
