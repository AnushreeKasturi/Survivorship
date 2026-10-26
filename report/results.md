# 4. Results

Every number in this section comes from `results/experiment_results.csv`,
produced by `Evaluation.scala` on the real 22,075-startup dataset, and
reproduced by an independent re-run on 2026-10-26 (Phase 21; the metrics
and confusion matrices matched exactly, with differences only in the 16th
significant digit of floating-point values).

## 4.1 Pipeline verification

| Check (Phase 21) | Expected | Observed |
|---|---|---|
| Startups loaded from HDFS | 22,075 | 22,075 |
| Label split (closed / operating) | 2,902 / 19,173 | 2,902 / 19,173 |
| Financial feature records | 22,075 | 22,075 |
| Startups with sentiment features | 3 | 3 |
| Rows after `leftOuterJoin` | 22,075 | 22,075 |
| Rows with real news coverage / zero baseline | 3 / 22,072 | 3 / 22,072 |
| Feature vector width (A / B) | 8 / 20 | 8 / 20 |
| Train / test rows (each experiment) | sum to 22,075 | 17,732 / 4,343 |
| NaN coefficients | none | none |

**Training split:** 15,386 operating and 2,346 closed, giving balanced class
weights of 0.5762 (operating) and 3.7792 (closed).

**Test split:** 4,343 startups, of which 3,787 are operating and 556 are
closed (12.8%). A trivial model that always predicts "operating" would
therefore score **87.2% accuracy**, while having zero recall on closures.

## 4.2 Headline metrics

| Exp. | Model | Features | Accuracy | **AUC-ROC** | **F1 (closed)** | F1 (operating) | Balanced acc. |
|---|---|---|---|---|---|---|---|
| 1 | Logistic Regression | Financial | 0.5252 | 0.7081 | 0.3136 | 0.6371 | 0.6625 |
| 2 | Linear SVM | Financial | 0.4131 | 0.7256 | 0.2858 | 0.5019 | 0.6282 |
| 3 | Logistic Regression | Financial + Sentiment | 0.5252 | 0.7081 | 0.3131 | 0.6372 | 0.6618 |
| 4 | Linear SVM | Financial + Sentiment | 0.4131 | 0.7261 | 0.2858 | 0.5019 | 0.6282 |

Balanced accuracy = mean of the two class recalls.

### Per-class precision and recall

| Exp. | Precision (closed) | Recall (closed) | Precision (operating) | Recall (operating) |
|---|---|---|---|---|
| 1 | 0.1924 | 0.8471 | 0.9551 | 0.4780 |
| 2 | 0.1693 | 0.9173 | 0.9654 | 0.3391 |
| 3 | 0.1922 | 0.8453 | 0.9547 | 0.4782 |
| 4 | 0.1693 | 0.9173 | 0.9654 | 0.3391 |

### Confusion matrices (positive class = operating)

| Exp. | TP | TN | FP | FN |
|---|---|---|---|---|
| 1 | 1,810 | 471 | 85 | 1,977 |
| 2 | 1,284 | 510 | 46 | 2,503 |
| 3 | 1,811 | 470 | 86 | 1,976 |
| 4 | 1,284 | 510 | 46 | 2,503 |

Charts: `visualization/accuracy_auc_comparison.png`,
`f1_by_class_comparison.png`, `confusion_matrices.png`, `sentiment_lift.png`.

## 4.3 Feature effect — the research question

| Comparison | ΔAccuracy | ΔAUC | ΔF1 (closed) | Predictions changed |
|---|---|---|---|---|
| E3 − E1 (Logistic Regression) | 0.0000 | −0.00003 | −0.0005 | 2 of 4,343 |
| E4 − E2 (Linear SVM) | 0.0000 | +0.00046 | 0.0000 | 0 of 4,343 |

Adding the 12 sentiment features had **no measurable effect** with either
model. For Logistic Regression, exactly two test predictions changed (one
operating startup moved from FN to TP, and one closed startup moved from TN
to FP), which cancel out in accuracy. For the SVM, every test prediction is
identical, and AUC moves only in the fourth decimal place.

**This is not evidence that sentiment has no predictive value.** Only 3 of
22,075 startups (0.014%) have any matched pre-cutoff news. For the other
22,072, every sentiment feature is a constant zero baseline, so the two
feature sets are effectively identical. The experiment, as run, has no
statistical power to answer the research question (see §5.1).

## 4.4 Model effect

| Comparison | ΔAccuracy | ΔAUC | ΔRecall (closed) | ΔF1 (closed) |
|---|---|---|---|---|
| E2 − E1 (SVM vs LR, financial) | −0.1121 | +0.0174 | +0.0701 | −0.0278 |
| E4 − E3 (SVM vs LR, financial + sentiment) | −0.1121 | +0.0179 | +0.0719 | −0.0273 |

The model effect is consistent across both feature sets:

- The **SVM ranks slightly better** (AUC 0.726 vs 0.708), and catches more
  closures (recall 0.917 vs 0.847).
- But at its default decision threshold it labels far more operating
  startups as closed (2,503 FN vs 1,977). That gives it lower accuracy, F1 on
  both classes and balanced accuracy.
- **Logistic Regression is the better-balanced classifier at the default
  threshold.** The SVM's higher AUC suggests that it might match or overtake
  LR with a tuned threshold, which was not done here.

## 4.5 Learned coefficients (Logistic Regression, Experiment 1)

From `results/model_coefficients.json`. Spark reports coefficients on the
original feature scale, so magnitudes are only comparable for features with
similar units. The 0/1 indicator features are directly comparable with each
other.

| Feature | Coefficient | Reading (positive = more likely operating) |
|---|---|---|
| `fundingDataLeakageSafe` | −1.2607 | Startups whose last recorded round was *before* the cutoff are less likely to be operating (see §5.2: this flag carries post-cutoff information) |
| `hasFundingAmountRecorded` | −0.2522 | |
| `fundingRoundCount` | −0.1290 per round | |
| `startupAgeYears` | +0.0384 per year | Older startups (as of 2013) are slightly more likely to still be operating |
| `fundingFrequencyPerYear` | +0.0096 | |
| `timeSinceLastFundingDays` | −0.00045 per day | Longer since the last round → more likely closed (≈ −0.17 per year) |
| `totalFundingUsd`, `averageRoundSizeUsd` | ≈ ±1e−9 per USD | Negligible per dollar |
| Intercept | +1.5750 | |

In Experiment 3, the sentiment coefficients are large (for example,
`positiveRatio` +2.74 and `averageSentiment` +1.85), but they are fitted to
at most three non-zero training rows and should not be interpreted.

## 4.6 Summary

1. **Feature effect (research question): inconclusive.** Sentiment features
   changed 2 of 8,686 test predictions across the two models, because only
   3 startups had news coverage.
2. **Model effect: real and consistent.** At the default threshold, LR gives
   better-balanced predictions, while the SVM gives slightly better ranking
   (AUC) and higher closure recall.
3. **Financial features alone carry moderate signal** (AUC ≈ 0.71–0.73),
   well above chance (0.5), although part of that signal comes from the
   leakage issue described in §5.2.
