# 3. Methodology

## 3.1 Research question and design

> Does incorporating public news sentiment about a startup improve the
> predictive accuracy of a startup survival/closure classifier, compared to
> using structured funding/financial attributes alone?

The study is a controlled 2×2 factorial comparison. Two feature sets are
crossed with two linear classifiers, giving four experiments:

|                        | Logistic Regression | Binary SVM   |
|------------------------|---------------------|--------------|
| Financial only         | Experiment 1        | Experiment 2 |
| Financial + Sentiment  | Experiment 3        | Experiment 4 |

This isolates two effects:

- **Feature effect** — (E3 − E1) and (E4 − E2): does adding sentiment help,
  with the model held constant? This is the research question.
- **Model effect** — (E2 − E1) and (E4 − E3): does the choice of classifier
  matter, with the features held constant?

The design was fixed before any model was trained (Phase 0), so that
"yes", "no" and "inconclusive" were all acceptable outcomes.

## 3.2 Dataset

The primary dataset is a Crunchbase snapshot (Kaggle,
*big-startup-secsees-fail*), with 66,368 rows and company names, statuses
and funding dates. It was chosen over a larger 196k-row alternative because
it is large enough for a meaningful test set while remaining tractable on a
single-node cluster, and over a 923-row alternative because that would leave
roughly 180 test rows — too few to distinguish the four experiments.

### 3.2.1 The temporal-mismatch problem

The snapshot reflects company status as of ~2013–2014. News collected today
would contain a decade of *future* information (for example, articles
reporting that a company shut down), which would leak the label directly
into the features. Every design decision below exists to prevent this.

## 3.3 Label design and prediction cutoff (Phase 2)

**Prediction cutoff:** T = 2013-01-01. Only information dated strictly
before T may be used as a feature. An earlier cutoff was preferred over a
later one to reduce right-censoring: a later T leaves less time for
eventual closures to be recorded in the snapshot, biasing the "operating"
class toward startups that simply had not failed yet.

**Label mapping:**

| Raw status | Label | Reason |
|---|---|---|
| closed | 0 | Unambiguous failure |
| operating | 1 | Unambiguous survival |
| acquired | excluded | Acqui-hire (disguised failure) cannot be distinguished from a successful exit |
| ipo | excluded | Small (~2%), structurally different population of easy cases |
| null / other | excluded | No usable label |

**Date validity:** corrupted dates outside [1950-01-01, 2016-12-31] (for
example `1015-01-30`) were treated as missing rather than trusted
(239 `founded_at`, 3 `first_funding_at` and 2 `last_funding_at` values).

**Eligibility rule:** a startup is modelled only if its status is
closed/operating **and** both `founded_at` and `first_funding_at` are valid
and before T. `last_funding_at` is kept as a feature only if it is also
before T; otherwise that single field is nulled and the row retained
(6,911 rows, 31.3%).

**Resulting population** (from `results/phase2_report.txt`):

| Stage | Rows |
|---|---|
| Raw | 66,368 |
| Valid status (closed/operating) | 59,272 |
| Eligible (founded and first funded before T) | **22,075** |
| — closed (label 0) | 2,902 (13.15%) |
| — operating (label 1) | 19,173 (86.85%) |

The class imbalance is 1 : 6.61.

`permalink` is the unique key (0 duplicates); `name` was never used as a
key because it has 330 case-insensitive duplicates.

## 3.4 Architecture

```
Startup CSV ──► HDFS (/raw, /processed) ──► Hive (schema + SQL EDA)
                         │
                         ▼
News (Google News RSS) ─► Scala: clean → name-match → sentiment score → features
                         │
                         ▼
               Spark RDDs: Startup RDD (k,v) ⟕ News RDD (k,v)
               reduceByKey / groupByKey / leftOuterJoin / persist
                         │
                         ▼
               Feature sets A (financial) and B (financial + sentiment)
                         │
                         ▼
               Spark MLlib: Logistic Regression, LinearSVC  →  Evaluation
```

| Layer | Technology | Version |
|---|---|---|
| Storage | HDFS (single-node, pseudo-distributed, WSL2 Ubuntu 24.04) | Hadoop 3.3.6 |
| Warehouse | Apache Hive (Derby metastore) | 3.1.3 |
| Language | Scala | 2.12.18 |
| Compute / ML | Apache Spark core, SQL, MLlib | 3.5.1 |
| Runtime | OpenJDK | 1.8 |
| Plotting only | Python (pandas, matplotlib, seaborn) | 3.x |

## 3.5 Financial features (Phase 11) — Group A

| Feature | Definition |
|---|---|
| `startupAgeYears` | (T − founded_at) / 365.25 |
| `totalFundingUsd` | Lifetime funding total — **only if leakage-safe** (see below) |
| `fundingRoundCount` | Lifetime round count — **only if leakage-safe** |
| `averageRoundSizeUsd` | total / rounds |
| `timeSinceLastFundingDays` | T − last_funding_at |
| `fundingFrequencyPerYear` | rounds / age |
| `hasFundingAmountRecorded` | 1 if any funding amount was recorded |
| `fundingDataLeakageSafe` | 1 if `last_funding_at` < T |

**Leakage guard.** The dataset's funding total and round count are
*lifetime* figures that can include post-cutoff rounds. Because
`last_funding_at` is the date of the *latest* round, if it is before T then
every round is before T. The totals are therefore used only when
`last_funding_at` < T and are otherwise set to missing, with
`fundingDataLeakageSafe` exposing the decision to the model.

Investor counts and largest-round size were planned but are not available in
the primary dataset; they were omitted rather than approximated.

### 3.5.1 Leak-free financial set (Phase 11b/14b)

Reviewing the first model's coefficients (§5.2) showed that the guard
withholds post-cutoff *amounts* but not post-cutoff *events*. The flag
`fundingDataLeakageSafe = 0` means the startup's latest round was on or
after T, which means it was still raising money after the prediction date;
`timeSinceLastFundingDays` is imputed for exactly those rows and inherits
the problem. We therefore built a second financial set, in which every
feature is computed **identically for every startup from information
dated before T**:

| Feature | Definition |
|---|---|
| `startupAgeYears` | (T − founded_at) / 365.25 |
| `yearsSinceFirstFunding` | (T − first_funding_at) / 365.25 |
| `yearsFoundedToFirstFunding` | (first_funding_at − founded_at) / 365.25 |
| `category=<c>` (20 + other) | multi-hot over the 20 most frequent categories |
| `country=<c>` (10 + other) | one-hot over the 10 most frequent countries |

All lifetime funding fields, and all indicators derived from them, are
excluded. The category and country vocabularies are fixed from frequencies
alone, never labels. The original set is kept only to measure the leakage.

## 3.6 News collection and sentiment (Phases 7–10) — Group B

**Collection.** Free news APIs (NewsAPI, GNews) cannot reach pre-2013
articles. `NewsCollector.scala` instead queries Google News RSS with
`before:`/`after:` operators for a 24-month window (2011-01-01 to
2013-01-01), and re-applies the `publicationDate < T` filter client-side,
because the upstream operators cannot be relied upon. A live test confirmed
that every item returned for three test companies fell inside the window.

**Name matching.** `NameMatcher.scala` scores whether a headline is actually
about the company, after normalising case, punctuation and corporate
suffixes, using whole-word matching:

1. Ambiguous short names (single word, ≤ 4 characters): confidence capped at 0.3
2. Exact whole-phrase match: 1.0
3. Partial multi-word match (≥ half the tokens): 0.5–0.8
4. Otherwise rejected (0.0)

This correctly rejected, for example, *"The tomato barons of the occupied
Western Sahara"* returned for "Kabbage".

**Batch collection (Phase 13b).** `BatchNewsCollector.scala` runs the
collector over the eligible population with checkpointing (resumable after
any interruption), stores raw headlines *before* matching (so matching can
change without re-collecting), and logs failed requests as `failed`.
A failed request is retried, never treated as "no news". Google News
rate-limited the first run after 175 requests, which limited the
sustainable rate to roughly 125–480 startups per hour, so the full
population was out of reach. We therefore used **balanced case-control
sampling**: closed and operating startups are drawn alternately from
separate seeded shuffles, so any stopping point gives a 1:1 random sample
of each class. AUC-ROC is independent of class prevalence, so this design
leaves the primary metric unbiased while maximising its precision for a
fixed request budget.

**Matching refinements found on the batch data.** Each fix below is backed
by a real failure in the collected data:

| Problem | Real example | Fix |
|---|---|---|
| Ordinary words as names | "Quorum": 43 items, almost all about quorums | Single-word dictionary names (SCOWL list, 63,875 words) capped at 0.3 |
| Generic partial matches | "Wireless Tech" matching any "wireless" headline | Partial matches need a non-dictionary token |
| Outlet suffix in titles | Every "… - Seeking Alpha" headline matched startup *Seeking Alpha* | Strip " - <outlet>" before matching and scoring |

Only headlines with confidence ≥ 0.6 become features.

**Sentiment scoring.** `SentimentScorer.scala` implements a transparent
lexicon method using only Scala collection primitives (immutable `Set`s,
`foldLeft`, pattern matching): lowercase → tokenise on non-letters → remove
stopwords → count positive and negative lexicon hits → score =
positive − negative → classify (>0 Positive, <0 Negative, 0 Neutral). The
lexicon is an original, startup-news-specific list, motivated by Loughran
and McDonald's finding that general-purpose lexicons misclassify business
text [7]. Every score is explainable by the matched terms; for example,
*"Startup X raises major funding and expands into new markets"* →
[raises, funding, expands] → +3, Positive.

**Sentiment features** (`FeatureExtractor.scala`), computed only over
matched, pre-cutoff articles:

| Feature | Definition |
|---|---|
| `newsCount` | number of eligible articles |
| `positive/negative/neutralNewsCount` | counts by class |
| `positiveRatio`, `negativeRatio` | class count / newsCount |
| `averageSentiment` | mean score |
| `recentSentiment` | score of the most recent article |
| `sentimentTrend` | mean(later half) − mean(earlier half); needs ≥ 2 articles |
| `sentimentVolatility` | sample standard deviation; needs ≥ 2 articles |
| `newsVolumeTrend` | articles in the final 12 months − in the earlier 12 months |
| `hasAnyCoverage` | 1 if newsCount > 0 |

## 3.7 Spark pipeline and join (Phases 12–13)

- `StartupRDD` loads the eligible CSV from HDFS with `textFile`, parses it
  with the quote-aware parser from Phase 6, keys records by `permalink`,
  counts labels with `reduceByKey` and derives financial features with
  `mapValues`.
- `NewsRDD` reads the batch-collected headlines from HDFS with `textFile`,
  de-duplicates `(permalink, title)` pairs with `reduceByKey`, and applies
  name matching and sentiment scoring as `flatMap` transformations.
  `groupByKey` then collects each startup's articles for feature
  aggregation. It is used deliberately, because trend and volatility need
  the full chronological list. The hand-verified 3-company sample remains
  available through `parallelize` as a regression fixture.
- `FeatureJoin` uses **`leftOuterJoin`** (not an inner join), so every one
  of the 22,075 startups is kept. Startups without matched news receive the
  zero-baseline sentiment features defined in Phase 10, rather than being
  dropped. An inner join would have discarded almost the whole population
  and biased it toward well-funded companies.
- The joined RDD is `persist()`ed because it feeds both feature sets.

## 3.8 Feature vectors (Phases 14–15)

Both sets are dense MLlib vectors. Set B is exactly Set A with the 12
sentiment features appended, so any difference between E1/E3 and E2/E4 is
attributable to sentiment alone. Missing values are imputed as 0 and always
paired with an explicit indicator (`fundingDataLeakageSafe`,
`hasFundingAmountRecorded`, `hasAnyCoverage`), so that the model can tell
"unknown" from a genuine zero.

## 3.9 Models (Phases 16–17)

| Setting | Logistic Regression | LinearSVC |
|---|---|---|
| Library | `org.apache.spark.ml.classification` | same |
| Train / test split | 80 / 20, `randomSplit`, seed 42 | identical split |
| Max iterations | 100 | 100 |
| Regularisation | L2, λ = 0.01 | L2, λ = 0.01 |
| Standardisation | MLlib default (on) | MLlib default (on) |
| Class weights | balanced | balanced |

**Class weighting.** The first real run showed both models collapsing to
"always predict operating": the SVM had TN = 0 and the LR had label-0
recall of 0.0018 (1 of 557). Balanced weights,
w_c = N / (2 · N_c), were then computed **from the training split only**,
so that test-set label frequencies cannot influence training.

## 3.10 Evaluation

### 3.10.1 Baseline evaluation (Phase 18)

`Evaluation.scala` uses Spark's `MulticlassMetrics` and
`BinaryClassificationMetrics` to report, per experiment, accuracy,
AUC-ROC (from the raw margin), precision/recall/F1 for **both** classes,
and the confusion matrix (label 1 = positive class), on a single 80/20
split at the default threshold.

Because a trivial "always operating" model scores ~87% accuracy, **AUC and
the closed-class (label 0) F1 are the primary metrics**, and accuracy is
reported only for completeness.

### 3.10.2 Final protocol (Phase 18b)

`ExperimentRunner.scala` replaces the single split:

1. **Stratified 5-fold cross-validation.** Within each label, a seeded
   shuffle is dealt round-robin into folds. The same folds are used for
   every feature set and model, so all comparisons are paired.
2. **Threshold chosen without the test fold.** The training folds are split
   80/20 into inner-train and validation. Balanced class weights come from
   inner-train only, the model is fit on inner-train, and the threshold
   that maximises balanced accuracy on validation is fixed before the test
   fold is scored.
3. **Metrics:** AUC-ROC (primary), PR-AUC for the closed class, and
   closed-class precision, recall and F1 plus balanced accuracy at the
   tuned threshold, as mean ± sd over folds.
4. **Significance:** out-of-fold scores are pooled per experiment. For each
   nested comparison, 2,000 paired bootstrap resamples of startups give a
   95% CI and a two-sided p-value for ΔAUC.
5. **Verification:** the AUC implementation is checked against Spark's
   `BinaryClassificationMetrics` at run time, and against hand-computed
   values in the unit tests.

**Populations and feature sets.**

| Population | Feature sets compared | Question |
|---|---|---|
| Full (22,075) | Original financial vs leak-free financial | How much of the original AUC came from leakage? |
| News cohort (§3.6) | Leak-free ⊂ + Coverage ⊂ + Sentiment | Does news *volume* help? Does news *tone* help beyond volume? |

Coverage = `log1pNewsCount` and `hasAnyCoverage`. Sentiment = coverage
plus the 10 tone features of §3.6. Because each set is a strict superset of
the previous one, every difference is attributable to the added group.

**Quality assurance.** 31 ScalaTest unit tests cover name matching, scoring,
feature extraction against Phase 10's hand-verified values, the leakage
guard, the collection order, the metrics, the fold assignment and the
bootstrap. A seeded random sample of 100 accepted headlines was audited by
hand to estimate matching precision.
