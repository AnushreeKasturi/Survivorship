# 5. Discussion

## 5.1 Answering the research question

> Does incorporating public news sentiment about a startup improve the
> predictive accuracy of a survival classifier, compared with structured
> attributes alone?

**Our answer is "slightly, and not conclusively".** On a balanced cohort of
815 startups:

- **Whether a startup had news, and how much, adds nothing** (ΔAUC ≈ 0,
  p ≥ 0.57), even though covered startups were more likely to survive.
- **What the news said adds about +0.01 AUC** with both models. The effect
  is in the same direction for LR and SVM and for both nested comparisons.
  Only one test reaches p < 0.05 (LR, tone beyond coverage, p = 0.047), and
  none survive a Bonferroni correction for the eight cohort comparisons.

The honest summary is a small, consistent and *suggestive* signal from
tone that this sample cannot establish firmly. The design allowed "yes",
"no" and "inconclusive" as outcomes, and we report the one the data
supports.

## 5.2 Why the sentiment signal is weak

Three limits compound one another. Each is measured, not assumed:

1. **Coverage.** Only 69 of 815 startups (8.5%) have any accepted
   pre-2013 headline. For the other 91.5%, every news feature is the zero
   baseline, so at most 8.5% of rankings can change. Most 2011–2012
   startups were simply not covered by news indexed in Google News.
2. **Matching precision.** In the audit, only 29% of accepted headlines
   were news about the company. Another 38% mention its platform (most
   often crime reports involving Craigslist), and 33% concern a different
   entity altogether. The clearest symptom is in the coefficients:
   `negativeNewsCount` has a *positive* coefficient (+0.41), because an
   operating company, Craigslist, is surrounded by headlines like "Craigslist
   killer jailed", which the lexicon correctly scores as negative, but which
   say nothing about Craigslist's health as a business.
3. **Tone resolution.** 87% of accepted headlines score Neutral. The
   lexicon only fires on explicit business vocabulary (raises, shuts,
   layoffs), and most headlines are descriptive. It also has no negation
   handling, so "can't buy success" scores positive.

Under these conditions, a +0.01 AUC is roughly what would be expected even
if tone were genuinely informative: the signal is diluted by noise
(precision) and confined to a small minority of startups (coverage). A
null result here would not have shown that sentiment is useless, and a
borderline positive result cannot show that it is useful.

### Volume versus tone

Because coverage tracks funding and visibility (Phase 7), we tested the
two separately: Leak-free ⊂ + Coverage ⊂ + Sentiment. Volume alone
contributes nothing beyond the leak-free features, so the small
improvement comes from the tone features. That is the more interesting
direction for the research question. Without this nested design, any gain
could have been attributed to "famous companies survive".

## 5.3 The leakage we found, measured and removed

The original financial set had a leakage guard, but the guard's own flag
leaked. `fundingDataLeakageSafe = 0` meant "the latest round was on or after
2013-01-01", which means the company was still raising money after the
prediction date. It had the largest coefficient in the original model
(−1.26). Cross-validation on all 22,075 startups puts the inflation at
**0.044 AUC for LR and 0.028 for SVM (both p < 0.001)**.

The leak-free set uses only founding date, first-funding date, category
and country, and still reaches AUC 0.679. That is a real but modest signal,
and it is the honest baseline for the sentiment comparison. The general
lesson is worth stating: **a missing-value indicator is itself a feature**,
and if the *reason* a value is missing involves the future, the indicator
leaks even when the value itself is withheld.

## 5.4 Evaluation choices, and one we corrected

- **AUC-ROC is the primary metric** because it ignores both prevalence and
  threshold. That makes it comparable between the full population (13%
  closed) and the balanced cohort (50%), and suitable for case-control
  sampling.
- **Thresholds.** Our first protocol tuned the decision threshold to
  maximise closed-class F1. On the balanced cohort this degenerates:
  predicting *every* startup closed already scores F1 = 0.667, and the tuned
  thresholds drifted to ~97% closed-recall with balanced accuracy ≈ 0.52.
  We switched the objective to balanced accuracy (Youden's J), which is
  prevalence-independent and consistent with balanced class weights. AUC
  results are unaffected by the change.
- **Paired comparisons.** Every comparison uses the same folds and
  resamples the same startups for both feature sets, so the confidence
  intervals reflect the feature difference, not split-to-split noise.
  Fold-level standard deviations (0.02–0.03 AUC in the cohort) are larger
  than the effects being tested, which is why the pairing matters.

## 5.5 Model comparison

With leak-free features, LR and SVM perform almost identically on the full
population (AUC 0.679 vs 0.679), and LR is slightly ahead on the cohort
(0.650 vs 0.634). The earlier impression that the SVM ranks better came
partly from the leaky features. LR has the practical advantage of
probability outputs, which is why the web demo uses it.

## 5.6 Limitations

1. **Small, sparse news cohort.** The cohort has 815 startups and 69 with
   coverage, limited by Google News rate limits (about 125–480 queries per
   hour). The tone coefficients are learned from those 69 startups and are
   unstable. In the exported web demo model, for example,
   `sentimentVolatility` carries a raw weight of −2.49, so two headlines
   with different scores swing an individual prediction sharply. The
   cross-validated AUC, which averages over startups, is the reliable
   summary; single predictions from the news model are not.
2. **Matching precision of 29% (strict)**, measured on 100 headlines judged
   by a single annotator; the sample is dominated by a few prolific names.
3. **Lexicon sentiment:** 87% of headlines are neutral, and there is no
   negation or stemming.
4. **Static attributes may have been edited after 2013.** Category and
   country could have been updated after the cutoff. They describe what a
   company is rather than how it fared, so we judge this much weaker than
   the removed funding leak.
5. **Label noise.** An "operating" status in a ~2014 snapshot can include
   startups that had failed but were not yet recorded. The early cutoff
   reduces this but cannot remove it.
6. **Excluded outcomes.** Acquired and IPO companies (7,096 rows) are
   excluded, so the results do not cover exits.
7. **Multiple comparisons.** The one p < 0.05 result is not robust to
   correction.
8. **Single-node cluster.** The work demonstrates the distributed
   programming model, not multi-node scaling.

## 5.7 Future work

1. **More coverage, better precision.** Continue the resumable collection
   (the remaining ~5,000 closed and operating startups in the
   balanced order), and require contextual cues for generic names
   (company domain, founder name, or an "acquires/raises/launches" verb
   next to the name).
2. **Better tone.** Use negation scope, stemming, or a domain-adapted
   sentence model, compared against the current transparent baseline on
   the same folds.
3. **Round-level funding data** (Crunchbase `investments.csv`) to restore
   pre-cutoff funding amounts without leakage.
4. **A pre-registered single comparison** (tone beyond coverage, LR) on a
   larger, independent cohort, to confirm or refute the borderline effect
   without a multiple-comparison penalty.

# 6. Conclusion

We built and verified a complete Big Data pipeline (HDFS, Hive, functional
Scala, Spark RDDs and MLlib) to test whether news sentiment improves
startup-survival prediction under strict temporal controls. We added
large-scale news collection, a leak-free feature set, cross-validated
evaluation with paired bootstrap confidence intervals, and 31 unit tests.

- **Leakage was the largest effect we found.** A missing-value flag in the
  original features carried post-cutoff information and inflated AUC by
  0.03–0.04. Without it, financial and structural attributes give AUC 0.68.
- **News volume does not help, and news tone helps a little.** Tone adds
  about +0.01 AUC with both models; this is borderline evidence that does
  not survive correction for multiple comparisons.
- **The bottleneck is data quality, not the pipeline.** Only 8.5% of
  startups have matched pre-2013 news, and only 29% of matched headlines
  are genuinely about the company.

The research question is therefore partly answered. Sentiment carries at
most a small signal at this coverage and matching precision. The pipeline
is resumable and fully reproducible, so it can test the question properly
as more and cleaner news is collected.
