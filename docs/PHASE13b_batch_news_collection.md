# Phase 13b — Batch News Collection

**Status:** Complete. See "Collection record" below for the final numbers.

---

## Why this phase exists

Phase 13 ended with sentiment features for only **3 of 22,075** startups
(Kabbage, Color Labs, Pinterest), and named batch collection as "the
genuinely necessary next step". Phase 21's re-run confirmed the
consequence: adding sentiment changed 2 of 8,686 test predictions, so
Experiments 3/4 could not test the research question at all. This phase
collects pre-cutoff headlines at scale with `BatchNewsCollector.scala`,
reusing Phase 7's verified URL builder, fetcher, RSS parser and cutoff
filter unchanged.

## Design

| Decision | Choice | Reason |
|---|---|---|
| Source | Google News RSS, `"<name>" before:2013-01-01 after:2011-01-01` | Only free source verified (Phase 7) to return correctly dated pre-2013 items |
| Order | Seeded (2013) random permutation, closed and operating **interleaved 1:1** | Any stopping point is a balanced random sample (see below) |
| Storage | `raw_news.tsv` **before** name matching; `collection_log.tsv` per startup | Matching can be changed and re-run in Spark without re-collecting |
| Failed request | Logged `failed`, retried next run, **never** counted as zero coverage | A failed search is not evidence of no news |
| Checkpointing | Append + flush per startup; resume skips `ok` rows | A crash or block loses at most one request |
| Rate | 6 s + jitter between requests; 1 / 5 / 10-minute cool-downs on HTTP 503 | Google rate-limits bursts (see below) |
| Leakage | Items dated on/after 2013-01-01 dropped at collection, again in `FeatureExtractor` | Phase 2 R4, enforced twice |

### Rate limiting — what actually happened

The first run (random order, 1.2 s between requests) was blocked with
HTTP 503 after **175 startups**; the collector stopped itself after three
consecutive failures, exactly as designed, with all 175 results saved
(archived in `data/news_run1_random_ratelimited/`, not used). Blocks then
recurred roughly every 100 requests and could outlast 16 minutes of
cool-downs, giving an effective rate of about 125 startups per hour. At
that rate the full population would take about a week, so the sampling
design was changed.

### Balanced case-control sampling

With a fixed request budget, a simple random sample would contain only
~13% closed startups. Instead the collector alternates a closed and an
operating startup, each drawn from its own seeded shuffle, so every
prefix of the collection order is a 1:1 random sample of each class.

- **AUC-ROC does not depend on class prevalence**, so the primary metric
  is unbiased under this design, and its variance is far smaller than
  with ~13% closed for the same number of requests.
- Precision and PR-AUC *do* depend on prevalence, so cohort PR-AUC is
  reported against the cohort's own 50% baseline, and is not compared
  with full-population numbers.
- The experiments that compare feature sets on the cohort only ever
  compare models on the *same* startups, so the design affects all of
  them equally.

## Name matching changes (found during batch collection)

| Problem | Example from real data | Fix |
|---|---|---|
| Ordinary English words as names | "Quorum" → 43 items, almost all about quorums | Single-word names in the SCOWL american-english list (63,875 lowercase words, Debian `wamerican` 2020.12.07) are treated as ambiguous (confidence 0.3, below the 0.6 acceptance threshold) |
| Partial match on a generic token | "Wireless Tech" matching any headline containing "wireless" | Partial matches must include at least one non-dictionary token |
| Generic multi-word phrase | "Wireless Tech" appearing as a phrase | Accepted, but at 0.6 rather than 1.0 |
| Outlet suffix in titles | Every "… - Seeking Alpha" headline matched the startup *Seeking Alpha* (39 false matches) | `NewsRDD.stripSourceSuffix` removes " - <outlet>" before matching and scoring |

The acceptance threshold (`NameMatcher.AcceptanceThreshold = 0.6`) is
now applied explicitly when building features; previously every
`matched = true` result was used, including ambiguous 0.3 matches.

**Trade-off:** Color Labs' "Color app finally gives up" headline is no
longer accepted ("color" is a dictionary word and "labs" is absent), so
precision is favoured over recall, consistent with Phase 8.

## Spark integration

- `NewsRDD.load` reads `hdfs:///startup-survival/raw/news/headlines/raw_news.tsv`
  with `textFile`, de-duplicates `(permalink, title)` with `reduceByKey`,
  strips outlet suffixes, applies `NameMatcher` and `SentimentScorer` as
  `flatMap` transformations, and returns the same
  `RDD[(permalink, ScoredNewsRecord)]` as before.
- `NewsRDD.loadCohort` reads `.../log/collection_log.tsv` and returns the
  permalinks whose query succeeded — the cohort for the sentiment
  experiments, where `newsCount = 0` genuinely means "no matched coverage".
- `hive/news_analysis.sql` exposes both files as Hive external tables.
- The hand-verified 3-company sample is kept as `NewsRDD.loadDemoSample`
  (the `parallelize` example) and still drives the Phase 12–18 demos, so
  the original Phase 18 baseline remains reproducible.

## What to run

```bash
cd ~/startup-survival-prediction/scala
sbt "runMain com.startupsurvival.BatchNewsCollector ../data/processed/labeled_eligible_startups.csv ../data/news 5804"
# resumable: re-running continues where it stopped

hdfs dfs -mkdir -p /startup-survival/raw/news/headlines /startup-survival/raw/news/log
hdfs dfs -put -f ../data/news/raw_news.tsv /startup-survival/raw/news/headlines/
hdfs dfs -put -f ../data/news/collection_log.tsv /startup-survival/raw/news/log/
sbt "runMain com.startupsurvival.spark.NewsRDD"
hive -f ../hive/news_analysis.sql
```

## Collection record

*(filled in from the frozen run — see below)*
