# 4. Results

Every number in this section comes from files written by the pipeline:
`results/experiment_results.csv` (Phase 18), `results/cv_summary.csv`,
`results/cv_folds.csv`, `results/significance.csv`,
`results/cohort_lr_coefficients.csv` (Phase 18b), and
`results/name_match_audit.csv`, plus the run logs.

## 4.1 Pipeline verification

| Check | Expected | Observed |
|---|---|---|
| Startups loaded from HDFS | 22,075 | 22,075 |
| Label split (closed / operating) | 2,902 / 19,173 | 2,902 / 19,173 |
| Rows after `leftOuterJoin` | 22,075 | 22,075 |
| Phase 18 baseline re-run | identical to committed CSV | identical (all metrics and confusion matrices) |
| Phase 10 regression fixture (sentiment sums) | color-labs −2, kabbage 1, pinterest 2 | −2, 1, 2 |
| Own AUC vs Spark `BinaryClassificationMetrics` | equal | 0.723336 = 0.723336 |
| Unit tests (`sbt test`) | all pass | 31 / 31 |
| News items dated on/after 2013-01-01 | 0 | 0 (all items 2011-01-01 to 2012-12-31) |

## 4.2 News collection (Phase 13b)

| Quantity | Value |
|---|---|
| Startups queried successfully (cohort) | **815** (408 closed, 407 operating) |
| Failed queries | 2 transient failures, both retried successfully |
| Startups with ≥ 1 raw pre-cutoff item | 222 (27.2%): closed 96 (23.5%), operating 126 (31.0%) — from Hive |
| Startups hitting Google's 100-item cap | 14 |
| Unique raw headlines (after de-duplication) | 3,122 (2011: 1,236; 2012: 1,893) |
| Accepted by NameMatcher (confidence ≥ 0.6) | 378 (12.1%) |
| Startups with ≥ 1 accepted headline | **69 (8.5%)** |
| Of which closed / operating | 25 of 408 (6.1%) / 44 of 407 (10.8%) |
| Sentiment of accepted headlines | 32 positive, 16 negative, 330 neutral (87% neutral) |

Name-match confidence of the raw headlines: 1,605 rejected (0.0), 1,139
ambiguous (0.3), 51 generic phrases (0.6), 18 partial (0.65–0.7), 309
distinctive exact matches (1.0).

**Coverage is associated with survival.** Operating startups were more
likely than closed ones to have matched pre-cutoff news (10.8% vs 6.1%;
two-proportion z = 2.40, p = 0.016).

## 4.3 Name-matching precision audit

A seeded random sample of 100 accepted headlines (from 30 startups) was
judged by hand:

| Judgement | Count | Example |
|---|---|---|
| About the company (y) | **29** | "Kabam announces acquisition of Wild Shadow Studios" |
| Mentions the company's product or platform, not company news (p) | 38 | "Teen convicted in fake Craigslist ad killings in Ohio" |
| Different entity or meaning (n) | 33 | "Workspace: St. Johns River Ferry Capt. Wayne Fenner" (startup *Workspace*) |

Strict precision is therefore **29%**, and lenient precision (y + p) is
67%. The audit is weighted by headline, so prolific names dominate it:
Craigslist alone contributes 29 of the 100 sampled headlines, all of them
"p", most of them crime reports. Among confidence-1.0 matches the split is
22 / 38 / 23. Generic dictionary names that are not in the SCOWL list as
single words ("Workspace", "curated.by", "Mowgli") are the main source of
"n".

## 4.4 Leakage check — full population (22,075 startups, 5-fold CV)

| Feature set | Model | AUC (mean ± sd) | PR-AUC closed | 
|---|---|---|---|
| Financial (original) | Logistic Regression | 0.724 ± 0.011 | 0.249 |
| Financial (original) | Linear SVM | 0.727 ± 0.010 | 0.255 |
| Financial (leak-free) | Logistic Regression | **0.679 ± 0.005** | 0.238 |
| Financial (leak-free) | Linear SVM | **0.679 ± 0.006** | 0.232 |

PR-AUC chance level = closed prevalence = 0.131.

| Comparison (paired bootstrap, 2,000 resamples) | ΔAUC | 95% CI | p |
|---|---|---|---|
| Original → leak-free, LR | **−0.044** | [−0.054, −0.034] | < 0.001 |
| Original → leak-free, SVM | **−0.028** | [−0.039, −0.017] | < 0.001 |

About 0.03–0.04 of the original AUC came from post-cutoff information.
Without it, the models still clearly beat chance (AUC 0.68), using only
age, funding timing, category and country.

## 4.5 Research question — news cohort (815 startups, 5-fold CV)

| Feature set | LR AUC (mean ± sd) | SVM AUC (mean ± sd) | LR PR-AUC closed |
|---|---|---|---|
| Leak-free | 0.639 ± 0.031 | 0.625 ± 0.033 | 0.659 |
| Leak-free + Coverage | 0.639 ± 0.025 | 0.625 ± 0.030 | 0.664 |
| Leak-free + Sentiment | **0.650 ± 0.021** | **0.634 ± 0.024** | 0.670 |

PR-AUC chance level in the balanced cohort = 0.50.

| Comparison | Model | ΔAUC | 95% CI | p |
|---|---|---|---|---|
| + Coverage (news volume) | LR | +0.001 | [−0.010, +0.012] | 0.80 |
| + Coverage (news volume) | SVM | +0.003 | [−0.008, +0.014] | 0.57 |
| + Tone, beyond coverage | LR | **+0.010** | **[+0.0001, +0.021]** | **0.047** |
| + Tone, beyond coverage | SVM | +0.006 | [−0.003, +0.016] | 0.16 |
| + All sentiment features | LR | +0.011 | [−0.003, +0.026] | 0.13 |
| + All sentiment features | SVM | +0.010 | [−0.004, +0.023] | 0.19 |

- **News volume adds nothing measurable.** Coverage differs by class
  (§4.2), but it is present for only 8.5% of startups, which caps how much
  it can move a ranking.
- **News tone gives a small, consistently positive improvement**, about
  +0.01 AUC for both models. It reaches nominal significance only for LR's
  tone-beyond-coverage comparison (p = 0.047, CI just above zero). With 8
  cohort comparisons, that result does not survive a Bonferroni correction
  (α = 0.05/8 ≈ 0.006).
- The cohort's leak-free AUC (0.64) is lower than the full population's
  (0.68) because the models train on ~650 rather than ~17,700 startups per
  fold, and because the categories most predictive in the full population
  are thinly represented in a sample of 815.

## 4.6 Threshold-based metrics

Thresholds were tuned for balanced accuracy on each fold's inner
validation split, then applied unchanged to the test fold. Values are
means over 5 folds; "closed" is treated as the positive class.

| Population | Feature set | Model | Balanced acc. | Recall (closed) | Precision (closed) | F1 (closed) |
|---|---|---|---|---|---|---|
| Full | Financial (original) | LR | 0.675 ± 0.013 | 0.759 | 0.220 | 0.341 |
| Full | Financial (original) | SVM | 0.672 ± 0.009 | 0.704 | 0.229 | 0.345 |
| Full | Financial (leak-free) | LR | 0.633 ± 0.008 | 0.626 | 0.209 | 0.313 |
| Full | Financial (leak-free) | SVM | 0.632 ± 0.009 | 0.681 | 0.199 | 0.307 |
| Cohort | Leak-free | LR | 0.587 ± 0.051 | 0.507 | 0.606 | 0.545 |
| Cohort | Leak-free + Coverage | LR | 0.593 ± 0.021 | 0.558 | 0.604 | 0.573 |
| Cohort | Leak-free + Sentiment | LR | 0.581 ± 0.038 | 0.569 | 0.604 | 0.570 |
| Cohort | Leak-free | SVM | 0.587 ± 0.037 | 0.468 | 0.611 | 0.523 |
| Cohort | Leak-free + Coverage | SVM | 0.591 ± 0.028 | 0.449 | 0.625 | 0.521 |
| Cohort | Leak-free + Sentiment | SVM | 0.580 ± 0.028 | 0.463 | 0.607 | 0.519 |

- On the full population, the leak-free model finds about two-thirds of
  closures (recall 0.63–0.68) at precision ~0.20, against a base rate of
  0.13.
- Precision is not comparable between the two populations, because the
  cohort is 50% closed by design.
- At a single threshold, the cohort differences between feature sets
  (≤ 0.013 balanced accuracy) are well inside the fold-to-fold spread
  (sd 0.02–0.05). The threshold-free AUC comparison in §4.5 is the
  better-powered test.
- An earlier version of the protocol tuned thresholds for closed-class F1.
  On the balanced cohort this degenerated to predicting nearly everything
  closed (recall ≈ 0.97, balanced accuracy ≈ 0.52), so it was replaced
  (see §5.4). AUC values were unaffected.

## 4.7 What the model learned (standardised LR coefficients, cohort, leak-free + sentiment)

| Feature | Coefficient | Reading |
|---|---|---|
| `yearsSinceFirstFunding` | −0.45 | The longer since the first round, the more likely closed (more time in which to have failed by the snapshot) |
| `country=RUS` | −0.45 | |
| `negativeNewsCount` | **+0.41** | Counter-intuitive: driven by Craigslist (operating) with many crime headlines (§4.3) |
| `category=Curated Web` | −0.37 | |
| `hasAnyCoverage` | +0.34 | Having news → more likely operating (§4.2) |
| `negativeRatio` | −0.25 | The expected direction once volume is accounted for |
| `yearsFoundedToFirstFunding` | +0.21 | |

Coefficients are on standardised features, so their magnitudes are
comparable. Chart: `visualization/lr_coefficients.png`.

## 4.8 Baseline evaluation (Phase 18) — for comparison only

The original single-split evaluation (original features, news for 3
startups, default threshold) gave AUC 0.708 (LR) and 0.726 (SVM), with
sentiment changing 2 of 8,686 test predictions. It is superseded by
§4.4–4.6, which are cross-validated, leak-free, and use 815 startups with
collected news.

## 4.9 Summary

1. **Leakage was real and is now removed.** It inflated AUC by 0.03–0.04
   (p < 0.001). The leak-free full-population model reaches AUC 0.679.
2. **News volume does not improve prediction** (ΔAUC ≈ 0, p ≥ 0.57).
3. **News tone gives a small improvement**, about +0.01 AUC for both
   models. This is borderline evidence (p = 0.047 for one comparison, not
   significant after multiple-comparison correction).
4. **Matching precision (29% strict) is the main limit** on what tone can
   show. Most accepted headlines mention a company's platform or a
   different entity.
