# 5. Discussion

## 5.1 Why the sentiment comparison is inconclusive

The central result is that adding sentiment changed almost nothing (§4.3).
The cause is coverage, not the sentiment method:

- Batch news collection across the eligible population was planned
  (Phase 13, "Next") but not run. Sentiment exists for only **3 startups**:
  Kabbage, Color Labs and Pinterest.
- For the other 22,072 startups, all 12 sentiment features are a constant
  zero baseline. A constant column carries no information, and Spark's
  standardisation leaves it contributing only to the intercept.
- With at most three informative rows, spread randomly between training and
  test sets, no classifier could learn a sentiment effect, and no evaluation
  could detect one.

The correct conclusion is therefore **"not tested at sufficient scale"**,
not "sentiment does not help". The pipeline itself is shown to work end to
end: collection, matching, scoring, feature extraction, the full-population
`leftOuterJoin` and both feature sets run correctly on real data. Only the
volume of collected news is missing.

### Expected confounding even at full scale

Phase 7's live tests found that news coverage tracks funding size: Pinterest
($1.3B) returned 100 pre-cutoff items, while smaller startups returned far
fewer. Even with full collection, `newsCount` and `hasAnyCoverage` may act
partly as **proxies for funding size** rather than as independent sentiment
signal. A fair test should compare (a) coverage-only features against
(b) coverage plus tone features, so that the effect of *what the news says*
can be separated from the effect of *whether there is news*.

## 5.2 A leakage issue found during analysis

`fundingDataLeakageSafe` was designed as the transparency flag for the Phase
11 leakage guard, and it is set to 1 when `last_funding_at` is before the
cutoff. But the *value of the flag itself* depends on post-cutoff
information:

- Flag = 0 for 6,911 startups (31.3%), almost all because their latest
  round happened **on or after 2013-01-01**. That means the company was still active and raising
  money after the prediction date, which is strongly associated with being
  "operating" in the 2014 snapshot.
- `timeSinceLastFundingDays` is imputed as 0 for exactly these rows, so it
  carries the same information.

This is consistent with the learned model. `fundingDataLeakageSafe` has the
largest coefficient (−1.26), meaning that startups whose funding *stopped*
before 2013 are predicted to be less likely to survive. The guard
successfully prevents post-cutoff *amounts* from leaking, but the indicator
reveals that post-cutoff *events* exist.

**Impact:** the financial-only AUC of ≈ 0.71–0.73 is probably optimistic.
Because both feature sets share this flag, the comparison between them is
still internally fair, but the absolute performance should be quoted with
this caveat.

**Fix (future work):** remove `fundingDataLeakageSafe`, and replace the
post-cutoff-dependent fields with features computable from information
available at T only. For example, compute "time since last pre-cutoff
round" from per-round dates (which needs the round-level Crunchbase
`investments` table identified in Phase 1). Then re-run Experiments 1–4.

## 5.3 Interpreting the model effect

The SVM achieves a higher AUC but lower accuracy and F1. These are not in
conflict:

- AUC measures *ranking* across all thresholds.
- Accuracy and F1 measure one fixed threshold (margin = 0 for the SVM, and
  probability = 0.5 for LR).

With balanced class weights, the SVM's hinge loss pushes its boundary toward
the majority class, so it flags 66% of operating startups as closed. Its
ranking is still slightly better. Threshold tuning on a validation split
(for example, choosing the threshold that maximises closed-class F1) would
give a fairer comparison of the two models. It was deliberately not tuned
on the test set, which would have leaked test information into model
selection.

**Low accuracy is expected here, not a defect.** Without class weighting,
both models collapsed to "always operating" (≈ 87% accuracy and near-zero
closure recall; see `ClassWeights.scala`). Weighting trades raw accuracy for
the ability to identify closures, which is the outcome of interest.

## 5.4 Limitations

1. **News coverage:** only 3 of 22,075 startups (§5.1).
2. **Post-cutoff information in two financial features** (§5.2).
3. **Label noise:** "operating" in a ~2014 snapshot may include startups that
   had failed but were not yet recorded (right-censoring). The 2013 cutoff
   reduces this risk but does not remove it.
4. **Excluded statuses:** acquired and IPO startups (7,096 rows) are excluded,
   so the results do not generalise to exit outcomes.
5. **Sentiment lexicon:** the lexicon is word-level only, with no negation
   handling ("$41 million can't buy success" scores Neutral), no stemming
   ("acquire" is not in the lexicon), and no phrase or sarcasm detection.
6. **Name matching:** the ≤ 4-character ambiguity guard misses long generic
   names. "Pinterest" matched a listicle that used the word as a common noun.
7. **Missing planned features:** investor counts, largest round size,
   category and country were not used.
8. **A single train/test split** (seed 42) with no confidence intervals.
   Differences in the third decimal place between experiments should not be
   read as significant.
9. **Single-node cluster:** HDFS and Spark ran pseudo-distributed on one WSL2
   machine (`local[*]`), so the work demonstrates the distributed programming
   model but not multi-node scaling.

## 5.5 Future work

1. **Batch news collection** across a large sample of the 22,075 startups
   (with rate limiting and checkpointing), followed by a re-run of
   Experiments 1–4. This is the single step that would make the research
   question answerable.
2. **Remove the leaky indicator** and rebuild the recency features from
   round-level data (§5.2).
3. **Threshold tuning** on a held-out validation split, plus k-fold
   cross-validation with confidence intervals.
4. **A coverage-versus-tone ablation** to separate the effect of news volume
   from the effect of sentiment (§5.1).
5. **Improved sentiment scoring:** negation scope, stemming, or a
   domain-adapted lexicon or model, compared against the current
   transparent baseline.
6. **Categorical features** (industry, country) through one-hot encoding.

# 6. Conclusion

This project built and verified a complete Big Data pipeline (HDFS, Hive,
functional Scala, Spark RDDs and MLlib) for testing whether news sentiment
improves startup-survival prediction, with careful controls on temporal
leakage throughout. On the real 22,075-startup dataset:

- Financial features give moderate predictive signal (AUC ≈ 0.71–0.73).
  This is probably inflated by one leaky indicator, which we identified
  during analysis.
- Logistic Regression gives better-balanced predictions at the default
  threshold, while the linear SVM ranks slightly better.
- The research question itself remains **open**. With news matched for only
  3 startups, the sentiment features could not influence the models
  (2 of 8,686 test predictions changed). The infrastructure to answer the
  question is in place, and the next step is batch news collection.
