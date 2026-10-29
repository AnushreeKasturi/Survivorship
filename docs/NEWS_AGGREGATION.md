# News aggregation: what runs today, and where it should go

## Today

| Source | How | Used for |
|---|---|---|
| Google News RSS | Plain HTTP fetch of the public search feed, items pulled out with regexes (`NewsCollector`). No API, no key. | Training (2011–2012 news, `BatchNewsCollector`), case studies, live watchlist |
| GDELT 2.0 GKG bulk files | One zip every 15 minutes, ~1,500 articles each, streamed by `web/Gdelt.scala`; titles that name a tracked startup are kept by `web/NewsArchive.scala` in the key-value store | Live watchlist and visitor suggestions |

Google News RSS is a scraper of a public feed, not an API client. Its limits:
it returns at most ~100 items per query, it gives headlines only, its
archive depth varies by company, and it can rate-limit or change format at
any time. Matching is by name only, which is why everyday-word names need
keywords.

GDELT's search API (the DOC API) rate-limits shared hosts, and refused every
request from our development machine (HTTP 429). The bulk files are static
downloads with no such limit, and they come with more than the API: the
organisations each article mentions, a tone score, and the page title. The
archive grows forward from when the server first runs; it does not backfill.

## Where it should go

One ingestion layer, with one adapter per source, writing raw articles to
storage partitioned by date and source, and Spark jobs that match, score and
aggregate them.

### News sources

| Source | Cost | Coverage | Notes |
|---|---|---|---|
| **GDELT GKG bulk** | Free | 2015 to now, global, every 15 minutes | Already in use for live data. For a historical cohort, run the same reader as a Spark batch over the archived files. |
| **Common Crawl CC-NEWS** | Free | 2016 to now, full text (WARC) | Article bodies, not just headlines; a natural Spark job. |
| Google News RSS | Free | Uneven | Keep as a fallback and for spot checks. |
| Event Registry / NewsAPI.ai | Paid | Entity-tagged, deduplicated | Only if the free sources prove too noisy. |

### Finding small and mid-sized companies, and their outcomes

News alone can't tell us which small companies exist or what happened to
them. Company registries can, for free, and they give real survival labels:

| Registry | Country | What it gives |
|---|---|---|
| Company master data (MCA21, published on data.gov.in) | India | Incorporation date and status (active, struck off, under liquidation): a survival label for every registered company |
| Startup India (DPIIT-recognised startups) | India | Which companies are recognised startups, with sector and city |
| Udyam registration | India | MSME status and size class (micro, small, medium) |
| Companies House API | UK | Incorporation and dissolution dates, officers, filings |
| SEC Form D filings (EDGAR) | US | Small private fundraises: date, amount, industry |

These are candidate sources; check each one's licence, coverage and access
before building on it.

### Which news counts

Naming a company is not the same as being news about it. The live pages pass
every name-matched headline through `NewsRelevance`, which keeps only
headlines reporting a business event and drops reviews, product and toy
listings, how-tos, "statistics" pages and social sites. On the case studies it
removed all 30 "Figma" headlines (toy figures) and 76 of Quibi's 98 (reviews
of its shows). The news model's tone weights were learned before this filter
existed, so the next step is to re-run the batch news through it
(`NewsRDD.matchAndScore`) and retrain, then let the benchmark decide.

### Matching articles to companies

Name-only matching is the weakest link (29% strict precision in our audit).
With registry data, match on name **plus** website domain **plus** city, and
use GDELT's own extracted organisation names as a second signal. Deduplicate
by URL and by near-identical titles (MinHash) before scoring.

### A new cohort

None of these archives reach back to 2011, so the 2013 cohort can't use them.
A new cohort would be, for example: companies registered and first funded
before 1 January 2019, labelled by their registry status at the end of 2024,
with news from 2017–2018 from GDELT and CC-NEWS. The pipeline, the leakage
rules and the benchmark (`ml/Benchmark.scala`) carry over unchanged.

## Suggested order

1. **Done:** GDELT bulk files for the live watchlist.
2. A registry adapter (MCA or Companies House) to build the new cohort's population and labels.
3. GDELT and CC-NEWS batch readers in Spark for the cohort's news window.
4. Domain-and-location matching, then re-run the benchmark on the new cohort.
