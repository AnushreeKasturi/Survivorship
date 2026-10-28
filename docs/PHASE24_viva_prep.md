# Phase 24 — Viva Preparation

These are likely questions with short answers you can defend. Each answer
points to a real number or a real file. Everyone should know sections A,
B and G. Sections C–F map to the owners of those parts of the project.

---

## A. The big picture (everyone)

**Q: What is your research question, in one sentence?**
Does adding news-headline sentiment improve startup-survival prediction
over structured data alone?

**Q: What did you find?**
1. News **volume** adds nothing (ΔAUC ≈ 0, p = 0.80).
2. News **tone** adds about +0.01 AUC with both models. It is borderline:
   p = 0.047 for LR, and not significant after correcting for 8
   comparisons.
3. Along the way we found and removed a leak that inflated AUC by 0.044.
   The leak-free model gets AUC 0.679 on all 22,075 startups.

**Q: So sentiment doesn't work?**
At this coverage and matching precision, it carries at most a small
signal. Only 8.5% of startups had matched news, and only 29% of matched
headlines were about the company, so a large effect was never possible.
The effect we see is in the right direction for both models. "Small and
not yet conclusive" is the honest answer.

**Q: Why is that still a good project?**
- The pipeline is complete, tested and verified.
- We collected real data at scale despite rate limits.
- We used cross-validation and confidence intervals instead of a single
  number.
- We measured our own weaknesses: the leakage and the matching precision.
- We designed the experiment so that "no" or "inconclusive" were valid
  answers.

**Q: Why a 2×2 design, plus the extra feature sets?**
The 2×2 separates the feature effect from the model effect. Then the
nested Leak-free ⊂ +Coverage ⊂ +Sentiment sets separate *how much* news
there is from *what it says*. Coverage tracks funding (famous companies get
news), so without this split a gain could just mean "famous startups
survive".

## B. Data and leakage (everyone; M1 in depth)

**Q: What is the single biggest risk in this project?**
Temporal leakage. The labels date from about 2014, and news collected today
contains articles like "X shut down".

**Q: How do you prevent it?**
We use one cutoff, T = 2013-01-01, enforced everywhere:
- Eligibility: founded before T and first funded before T.
- News is filtered to before T at collection and again at aggregation.
  Hive confirms 0 items on or after 2013-01-01.
- The final feature set uses only fields known at T for every startup.

**Q: Tell me about the leak you found.**
- `fundingDataLeakageSafe` = 0 meant "the latest round was on or after T",
  i.e. the company was still raising money after the prediction date.
- It had the biggest coefficient (−1.26).
- Removing it (and the feature imputed alongside it) lowered AUC by 0.044
  for LR (95% CI [−0.054, −0.034]) and 0.028 for SVM, both p < 0.001.
- The lesson: a missing-value flag is a feature. If the *reason* a value
  is missing involves the future, the flag leaks.

**Q: What is in the leak-free feature set?**
Age at T, years since first funding, years from founding to first funding,
20 category flags and 10 country flags. There are no lifetime funding
totals, round counts or last-funding dates.

**Q: Couldn't category or country leak too?**
In principle, if Crunchbase edited them after 2013. They describe what a
company *is*, not how it fared, so the risk is much weaker. We list it as
a limitation.

**Q: Why exclude acquired and IPO startups?**
An acquisition can be a success or a disguised failure (an acqui-hire),
and the data can't tell which. IPOs are about 2% of rows and are easy
cases.

**Q: Numbers?**
66,368 raw rows → 59,272 with a valid status → 22,075 eligible (2,902
closed, 13.15%).

## C. News collection (M2 in depth)

**Q: Where does the news come from?**
Google News RSS, queried as `"<name>" before:2013-01-01 after:2011-01-01`.
Free APIs (NewsAPI, GNews) can't reach before 2013.

**Q: Why only 815 startups?**
Google rate-limits: it blocked us after 175 requests on the first run, and
the sustainable rate was about 125–480 startups per hour. So we:
- made the collector resumable (it saves progress after every startup);
- log failed requests as "failed", never as "no news";
- visit startups in a **balanced random order**.

**Q: Why balanced (half closed, half operating)?**
Any stopping point gives a random 1:1 sample. AUC doesn't depend on class
balance, so this gives far more statistical power per request than a
random sample, which would be only 13% closed. The comparisons are always
made on the same startups, so the design is fair to every feature set.

**Q: How good is the name matching?**
We audited 100 random accepted headlines:
- 29% are about the company;
- 38% mention its product or platform (e.g. "Craigslist killer jailed");
- 33% are a different entity ("Workspace", "Mowgli", "curated by").

**Q: What did you fix in the matcher?**
Each fix came from a real error in the collected data:
- Ordinary-word names ("Quorum") are now ambiguous, using a 63,875-word
  dictionary.
- Partial matches need a distinctive word ("Wireless Tech" no longer
  matches every "wireless" headline).
- Outlet suffixes are stripped: every "… - Seeking Alpha" headline had
  matched the startup *Seeking Alpha*.

**Q: How does the sentiment scorer work?**
Lowercase → tokenise → remove stopwords → count lexicon hits with
`foldLeft` → score = positive − negative. Example: "raises major funding
and expands" scores +3. Its weaknesses: 87% of headlines score neutral,
and there is no negation or stemming.

## D. Spark and Hadoop (M1/M3 in depth)

**Q: Which RDD operations did you use, and why?**
- `textFile` from HDFS for the startups and the collected headlines;
  `parallelize` for the verified demo sample.
- `map`, `filter`, `flatMap` and `mapValues` for parsing, matching and
  scoring.
- `reduceByKey` for label counts and headline de-duplication.
- `groupByKey` where the full per-startup list is needed (trend and
  volatility).
- `leftOuterJoin`, so all 22,075 startups are kept.
- `persist` on RDDs that are reused.

**Q: `reduceByKey` vs `groupByKey`?**
`reduceByKey` combines on each partition before the shuffle, so less data
moves. We use `groupByKey` only where we genuinely need every record.

**Q: Lazy evaluation and lineage?**
Transformations only build a DAG, and actions trigger it. If a partition is
lost, Spark recomputes it from the lineage. That is RDD fault tolerance.

**Q: Explain your HDFS setup.**
Single node, block size 128 MB, replication 1 (one DataNode). The
eligible file is 3.8 MB, so it is one block (`fsck` shows
`blk_1073741826`, HEALTHY). See `hadoop/hdfs_commands.md`.

**Q: Where is Hive used?**
`hive/schema.sql` defines the startup table. `hive/news_analysis.sql`
defines external tables over the same HDFS news files Spark reads, and
runs coverage-by-label, the leakage check and per-year counts.

## E. ML and evaluation (M3/M4 in depth)

**Q: Why AUC as the main metric?**
It ignores both class balance and threshold. That makes it fair across
the full population (13% closed) and the balanced cohort (50%).

**Q: How did you evaluate?**
- Stratified 5-fold cross-validation, with the same folds for every
  experiment.
- In each fold, 80% of the training data fits the model and 20% picks the
  threshold, so the test fold is never touched.
- Balanced class weights, computed on training data only.
- Paired bootstrap (2,000 resamples of startups) for every "does X help"
  comparison.

**Q: Why tune the threshold for balanced accuracy and not F1?**
We started with closed-class F1 and noticed that, on a 50/50 cohort,
predicting everyone closed already scores F1 = 0.667, so the tuning drifted
to useless thresholds. Balanced accuracy doesn't depend on class balance.
AUC is unaffected by the threshold either way.

**Q: How do you know your metrics are correct?**
Our AUC equals Spark's `BinaryClassificationMetrics` to 6 decimals
(0.723336), and 31 unit tests check it against hand-computed values,
including ties and weighted resamples.

**Q: Is p = 0.047 significant?**
Nominally yes, but we ran 8 cohort comparisons; Bonferroni needs
p < 0.006. We report it as borderline. The consistent direction across
both models is the stronger part of the evidence.

**Q: Why does `negativeNewsCount` have a positive coefficient?**
Matching noise. Craigslist is operating, but its matched headlines are
crime stories ("Craigslist killer"), which score negative. It is a good
example of why precision limits the sentiment signal.

**Q: LR or SVM?**
They are almost identical on leak-free features (0.679 vs 0.679 on the
full population; 0.650 vs 0.634 on the cohort). LR also gives
probabilities, so the web demo uses it.

## F. Testing and reproducibility (everyone)

**Q: How did you test the code?**
- 31 ScalaTest unit tests: matching, scoring, features, leakage guard,
  sampling order, metrics, folds and bootstrap.
- A Phase 10 regression fixture.
- The Phase 21 end-to-end checklist.
- The original Phase 18 baseline re-runs bit-for-bit.

**Q: Can someone reproduce this?**
Yes; the steps are in the README:
1. Collection is resumable with a fixed seed.
2. Upload the data to HDFS.
3. `sbt test`, then `ExperimentRunner`.
4. Run the plotting scripts.

## G. "What would you do with more time?" (everyone)

1. Collect more news (the collector resumes where it stopped).
2. Improve matching for generic names, using context such as the company
   domain or founder name.
3. Use a better sentiment model (negation, or a domain-tuned model) on the
   same folds.
4. Use round-level funding data to restore pre-cutoff funding amounts
   safely.
5. Run one pre-registered test of "tone beyond coverage" on a new, larger
   cohort.

## Numbers to memorise

| What | Value |
|---|---|
| Raw → eligible | 66,368 → 22,075 (2,902 closed) |
| Cutoff | 2013-01-01 |
| Leakage effect (LR) | ΔAUC −0.044, 95% CI [−0.054, −0.034] |
| Leak-free AUC, full population | 0.679 (LR and SVM) |
| News cohort | 815 (408 closed); 69 with matched news (8.5%) |
| Coverage: closed vs operating | 6.1% vs 10.8% (p = 0.016) |
| Matching precision (strict / lenient) | 29% / 67% |
| Headlines neutral | 87% |
| Volume effect | ΔAUC +0.001, p = 0.80 |
| Tone beyond volume (LR / SVM) | +0.010 (p = 0.047) / +0.006 (p = 0.16) |
| Unit tests | 31, all passing |
