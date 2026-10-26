# Phase 18b — Leak-free Features, Cross-Validation and Significance

**Status:** Complete. Results in `results/cv_summary.csv`,
`results/cv_folds.csv`, `results/significance.csv`,
`results/cohort_lr_coefficients.csv`; charts from
`visualization/plot_cv_results.py`; write-up in `report/results.md`.

---

## What Phase 21 showed was wrong with Phase 18

| Problem | Evidence | Fix in this phase |
|---|---|---|
| Sentiment untestable | Only 3 startups had news | Phase 13b batch collection; experiments on the collected cohort |
| Leakage through an indicator | `fundingDataLeakageSafe` had the largest LR coefficient (−1.26); flag = 0 means "raised money after the cutoff" | Leak-free feature set (Phase 11b/14b) |
| One split, no uncertainty | ΔAUC values in the 4th decimal place | Stratified 5-fold CV + paired bootstrap CIs |
| Default threshold | SVM flagged 66% of operating startups as closed | Threshold tuned on an inner validation split |
| Coverage vs tone confounded | Coverage tracks funding (Phase 7) | Nested ablation: Leak-free ⊂ +Coverage ⊂ +Sentiment |

## Phase 11b / 14b — the leak-free feature set

Every feature must be computable **identically for every startup from
information dated before 2013-01-01**. The raw dataset's funding total,
round count and last-funding date are lifetime figures from the ~2014
snapshot, and *whether* they are usable reveals whether a round happened
after the cutoff, so all of them, and every indicator derived from them,
are excluded.

| Feature | Definition | Why it is safe |
|---|---|---|
| `startupAgeYears` | (T − founded_at) / 365.25 | founded_at < T for every eligible row (Phase 2 R4) |
| `yearsSinceFirstFunding` | (T − first_funding_at) / 365.25 | first_funding_at < T for every eligible row |
| `yearsFoundedToFirstFunding` | (first_funding_at − founded_at) / 365.25 | both dates < T |
| `category=<c>` × 20, `category=other` | multi-hot over the 20 most frequent categories | static attribute; vocabulary from frequencies only, never labels |
| `country=<c>` × 10, `country=other` | one-hot over the 10 most frequent countries | static attribute |

Known residual risk: Crunchbase categories and countries could have been
edited after 2013. They describe what a company *is*, not how it fared,
so this is judged much weaker than the funding-event leak, and it is
stated as a limitation.

## Nested news feature sets (Phase 15b)

| Set | Adds | Question it answers |
|---|---|---|
| Leak-free | — | Baseline on the cohort |
| Leak-free + Coverage | `log1pNewsCount`, `hasAnyCoverage` | Does *having* news (volume) help? |
| Leak-free + Sentiment | + 10 tone features (counts by class, ratios, average, recent, trend, volatility, volume trend) | Does *what the news says* help beyond volume? |

## Evaluation protocol (`ml/ExperimentRunner.scala`)

1. **Folds.** Stratified 5-fold: within each label, a seeded shuffle of
   permalink-sorted keys dealt round-robin. The same folds are used for
   every feature set and model, so comparisons are paired.
2. **Per fold.** The 4 training folds are split again 80/20 (inner
   train / validation). Balanced class weights are computed on inner-train
   only; the model (LR or LinearSVC, L2 λ = 0.01, 100 iterations) is fit on
   inner-train; the threshold maximising balanced accuracy on validation is
   fixed **before** the test fold is scored.
3. **Metrics.** AUC-ROC (primary), PR-AUC for the closed class,
   closed-class precision/recall/F1 and balanced accuracy at the tuned
   threshold; mean ± sd over folds.
4. **Significance.** Out-of-fold scores from all 5 folds are pooled per
   experiment. For each nested comparison, 2,000 paired bootstrap
   resamples of startups give the 95% CI of ΔAUC and a two-sided p-value.
5. **Correctness checks.** The rank-based AUC is cross-checked against
   Spark's `BinaryClassificationMetrics` on the same scores at run time,
   and is unit-tested (`ModelingSpec`) against hand-computed values,
   including ties and weighted resamples.

## Two populations

- **Full (22,075):** original Phase 14 financial set vs leak-free set,
  which quantifies how much of the original AUC came from leakage.
- **News cohort:** the balanced Phase 13b sample, compared across the
  three nested sets for the research question and the ablation.

## Outputs for the demo

`results/model_coefficients_leakfree.json` holds two leak-free LR models
(financial-only on all startups; leak-free + coverage + tone on the
cohort) with their category/country vocabulary. The dashboard predictor
looks each coefficient up by feature name.

## What to run

```bash
cd ~/startup-survival-prediction/scala
sbt test                                                    # 31 unit tests
sbt "runMain com.startupsurvival.ml.ExperimentRunner"       # ~15 min
cd .. && python visualization/plot_cv_results.py
```
