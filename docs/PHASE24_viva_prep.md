# Phase 24 — Viva Preparation

These are likely questions with short answers you can defend, all tied to
real numbers or code. Every member should be able to answer sections A, B
and F. Sections C, D and E map to the owners of those parts of the project.

---

## A. The big picture (everyone)

**Q: What is your research question, in one sentence?**
Does adding news-headline sentiment improve startup-survival prediction over
funding data alone?

**Q: What did you find?**
1. Financial features give an AUC of about 0.71 (LR) and 0.73 (SVM).
2. Adding sentiment changed nothing measurable: 2 of 8,686 test predictions
   changed.
3. That is *inconclusive*, not "no", because only 3 of 22,075 startups had
   matched news.

**Q: So your project failed?**
No:
- The pipeline is complete and verified end to end on real data.
- The experimental design is sound.
- We report the coverage limitation honestly instead of overclaiming.
- We also found and documented a leakage path ourselves.

The missing step (batch news collection) is clearly identified, and the
same code runs unchanged once that data exists.

**Q: Why only 3 startups with news?**
Phase 7 was a live feasibility test on a handful of companies, to prove that
Google News RSS can return correctly dated pre-2013 articles. The batch
collection across thousands of startups (with rate limiting and
checkpointing) was not run before the deadline.

**Q: Why did you choose a 2×2 design?**
It isolates two effects separately:
- **Feature effect** (E3−E1 and E4−E2): does sentiment help, with the model
  held fixed?
- **Model effect** (E2−E1 and E4−E3): does the classifier matter, with the
  features held fixed?

## B. Data and leakage (everyone; M1 in depth)

**Q: What is the single biggest risk in this project?**
Temporal leakage. The labels date from about 2014. News collected today
includes articles like "X shut down", which would give away the label.

**Q: How did you prevent it?**
We used one cutoff, T = 2013-01-01, enforced at every stage:
- Eligibility: founded before T and first funded before T.
- `last_funding_at` is nulled if it is on or after T.
- Funding totals are used only when all rounds are provably before T.
- News is filtered to before T at collection *and* again at aggregation.

**Q: Is there any leakage left?** *(Expect this; say it before they do)*
Yes, and we found it ourselves:
- `fundingDataLeakageSafe` = 0 means the last funding round was after T.
  That reveals the startup was still raising money after the prediction
  date.
- It has the largest LR coefficient (−1.26).
- So the absolute AUC is probably optimistic. The sentiment comparison is
  still fair, because both feature sets contain the flag.
- Fix: drop the flag and rebuild the recency features from round-level
  dates.

**Q: Why exclude acquired startups?**
An acquisition can be a success (a strategic exit) or a disguised failure (an
acqui-hire), and the dataset cannot tell the two apart.

**Q: Why exclude IPO startups?**
They are about 2% of rows, and they are a structurally different
population of easy cases.

**Q: Why 2013-01-01 and not later?**
A later cutoff gives more rows, but less time for closures to be recorded
before the snapshot (right-censoring). That would make "operating" partly
mean "not dead *yet*".

**Q: How big is the dataset after cleaning?**
66,368 raw rows → 59,272 with a valid status → **22,075 eligible**.
Of those, 2,902 are closed and 19,173 are operating (1 : 6.61).

**Q: Why `permalink` and not `name` as the key?**
`permalink` has 0 duplicates. `name` has 330 case-insensitive duplicates.

**Q: What did you do about corrupted dates?**
Dates outside 1950–2016 (for example, the year 1015) are treated as missing,
not trusted: 239 `founded_at`, 3 `first_funding_at` and 2 `last_funding_at`
values.

## C. Scala and sentiment (M2 in depth)

**Q: How does your sentiment scorer work?**
1. Lowercase the headline.
2. Tokenise on non-letters.
3. Remove stopwords.
4. Count positive and negative lexicon hits with `foldLeft`.
5. Score = positive − negative, then classify as >0, <0 or 0.

Example: "raises major funding and expands" → [raises, funding, expands]
→ +3, Positive.

**Q: Why not VADER or a pretrained model?**
- Transparency: every score is explained by named matched terms.
- The syllabus requires showing immutable collections, `foldLeft` and
  pattern matching.
- Loughran and McDonald (2011) showed that general lexicons misread
  business text, so we built a startup-news-specific one.

**Q: What are its weaknesses?**
- No negation: "$41 million can't buy success" scores Neutral.
- No stemming: "acquire" is missing from the lexicon.
- No phrase or sarcasm handling.

**Q: How do you match a headline to the right company?**
`NameMatcher` normalises both strings, then scores:
- Short single-word names of 4 characters or fewer: capped at 0.3
  (ambiguous).
- Exact whole-word phrase match: 1.0.
- Partial multi-word overlap: 0.5–0.8.
- Otherwise: rejected.

It rejected "The tomato barons of the occupied Western Sahara" as a
"Kabbage" result.

**Q: Where are the Unit 2 concepts in your code?**
- Immutable `Set`/`List` lexicons.
- `foldLeft` in `countSentimentWords`.
- Pattern matching in `classify` and the fold body.
- Case classes (`StartupRecord`, `SentimentFeatures`).
- `Option` for genuinely missing values.
- `partition` in `DataCleaner`.

## D. Spark (M3 in depth)

**Q: Which RDD operations did you use, and why?**
- `textFile` (from HDFS) and `parallelize` to create RDDs.
- `map`, `filter`, `flatMap` and `mapValues` as lazy transformations.
- `reduceByKey` for distributed label counts. It is a shuffle and a stage
  boundary.
- `groupByKey` to collect each startup's articles.
- `leftOuterJoin` to join financial and news data.
- `persist` on the joined RDD, because it feeds both feature sets.

**Q: Why `leftOuterJoin` rather than `join`?**
An inner join keeps only startups that have news. That would be 3 rows, and
it would bias the data toward well-funded companies. A left outer join
keeps all 22,075 startups and gives the rest a zero-baseline sentiment
record.

**Q: What are lazy evaluation and lineage?**
- Transformations only build a DAG. Nothing runs until an action (`count`,
  `collect`, saving output).
- Lineage is that DAG. If a partition is lost, Spark recomputes it from the
  lineage instead of replicating the data. That is RDD fault tolerance.

**Q: Why persist?**
`combinedRDD` is reused by both feature sets. Without `persist`, Spark would
recompute the whole lineage (HDFS read → parse → features → join) twice.

**Q: `reduceByKey` vs `groupByKey`?**
- `reduceByKey` combines values on each partition *before* the shuffle, so
  less data moves.
- `groupByKey` ships every value. We use it only where we genuinely need
  the full list of articles per startup (to compute the trend and
  volatility).

**Q: Is this really distributed?**
The programming model is distributed. Execution is single-node: HDFS runs
pseudo-distributed and Spark runs `local[*]` on WSL2. The same code runs on
a cluster by changing the master URL.

## E. ML and evaluation (M3/M4 in depth)

**Q: Your accuracy is 41–52%. That's worse than a coin flip?**
- Accuracy is the wrong metric here. A model that always says "operating"
  gets 87.2%, and it is useless.
- Without class weights, both of our models did exactly that (SVM TN = 0).
- We use balanced weights, w_c = N / (2·N_c), computed on the training
  split only (0.576 for operating, 3.779 for closed). That trades accuracy
  for catching closures: closure recall is 0.85 (LR) and 0.92 (SVM).
- Coin flip is 50% only for balanced classes. The right comparison is
  AUC 0.71 vs 0.5, or balanced accuracy 0.66 vs 0.5.

**Q: Why compute class weights on the training split only?**
Using test-set label frequencies would leak test information into training.

**Q: The SVM has a higher AUC but lower accuracy. Explain.**
- AUC measures ranking over all thresholds. Accuracy measures one threshold
  (margin 0).
- The SVM ranks slightly better, but its default threshold flags 66% of
  operating startups as closed.
- Threshold tuning on a validation set would fix this. We did not tune on
  the test set, because that would be leakage.

**Q: Which model is better?**
- At the default threshold, LR: F1 on both classes and balanced accuracy
  (0.66 vs 0.63).
- On ranking, the SVM: AUC 0.726 vs 0.708.
- LR also gives probabilities, which is why the dashboard uses it.

**Q: Hyperparameters?**
Both models use 100 iterations, L2 regularisation with λ = 0.01,
standardisation on, an 80/20 `randomSplit` with seed 42, and the same split
for all four experiments.

**Q: Are the differences statistically significant?**
We used a single split with no confidence intervals. Differences in the
third or fourth decimal place (such as ΔAUC 0.0005) should be treated as
noise. k-fold cross-validation is future work.

**Q: Why are the sentiment coefficients so large (positiveRatio +2.74)?**
They are fitted to at most 3 non-zero rows, so they are meaningless. With
almost no data, the regulariser barely constrains them.

## F. "What would you do with more time?" (everyone)

1. Batch-collect news for thousands of startups, then re-run.
2. Remove the leaky flag and use round-level funding dates.
3. Tune thresholds on a validation split, and use k-fold cross-validation.
4. Run a coverage-only vs coverage-plus-tone ablation, to separate news
   volume (a funding proxy) from news tone.
5. Add negation and stemming to the scorer.

## Numbers to memorise

| What | Value |
|---|---|
| Raw rows → eligible | 66,368 → 22,075 |
| Closed / operating | 2,902 / 19,173 (13.15% closed) |
| Cutoff | 2013-01-01 |
| Train / test | 17,732 / 4,343 (556 closed in test) |
| Startups with news | 3 (Kabbage, Color Labs, Pinterest) |
| AUC: E1 / E2 / E3 / E4 | 0.708 / 0.726 / 0.708 / 0.726 |
| F1 (closed): LR / SVM | 0.314 / 0.286 |
| Recall (closed): LR / SVM | 0.847 / 0.917 |
| Always-"operating" accuracy | 87.2% |
| Predictions changed by sentiment | 2 (LR), 0 (SVM) |
