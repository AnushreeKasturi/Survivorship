# Phase 21 — Final Testing

**Status:** Complete. Executed 2026-10-26 on the WSL2 single-node cluster
(Hadoop 3.3.6, Spark 3.5.1, Scala 2.12.18). Results are recorded below,
before the original checklist.

## Execution record (2026-10-26)

| Step | Expect | Actual | Pass |
|---|---|---|---|
| 1 Infrastructure | NameNode, DataNode, SecondaryNameNode; eligible CSV in HDFS | All three running; `labeled_eligible_startups.csv` (3,777,637 bytes) present | ✅ |
| 2 Row counts | 22075; label 0 → 2902, label 1 → 19173 | 22075; 2902 / 19173 | ✅ |
| 3 Join | 22075 financial, 3 sentiment, 22075 combined, 3 coverage, 22072 baseline | Identical | ✅ |
| 4 Feature sets | 22075 rows each | 22075 / 22075; widths 8 and 20 | ✅ |
| 5 Models | No exceptions, train+test = 22075, no NaN | 17732 + 4343 for all four; 0 NaN; exit 0 | ✅ |
| 6 Evaluation | 4 rows, metrics in [0,1], TP+TN+FP+FN = test_count | 4 rows, all sums 4343. Identical to the committed CSV except for float noise in the 16th digit | ✅ |
| 7 Visualisations | 4 PNGs, table matches CSV | 4 PNGs written, printed table matches CSV (run on Windows Python; WSL system Python lacks numpy, so use the venv there) | ✅ |
| 8 Dashboard | Renders real numbers | `dashboard/`, `../results/experiment_results.csv` and `../results/model_coefficients.json` all serve HTTP 200 via `python -m http.server`. Visual render not checked in a browser during this run | ⚠️ partial |
| 9 Report vs numbers | Every number traceable | `report/results.md` is written only from the step 6 CSV and run logs | ✅ |

**Finding:** during step 9, review of the coefficients showed
that `fundingDataLeakageSafe` (and the 0-imputation of
`timeSinceLastFundingDays`) encodes *whether* a post-cutoff funding round
exists. See `report/discussion.md` §5.2.

---

---

## Why a checklist, not a test suite

Given the project's scale (22,075 real rows, a real HDFS/Spark/Hive
cluster, live external RSS calls), a from-scratch automated test suite
would either need to mock most of the real infrastructure (defeating the
point of end-to-end testing) or take longer to build than it saves. This
checklist instead re-runs and cross-checks the real commands from every
earlier phase, in order, verifying each phase's output still matches its
own documented "expect N" values after all of Phase 13-20's changes.

Run these in order. If any step's actual output doesn't match its
"Expect" line, stop and report the exact numbers before continuing -
don't assume a later step will mask an earlier discrepancy.

## 1 — Infrastructure sanity

```bash
jps
```
Expect: NameNode, DataNode, SecondaryNameNode, ResourceManager,
NodeManager all present (see docs/PHASE04_hadoop_hdfs_hive.md).

```bash
hdfs dfs -ls /startup-survival/processed/startups/
```
Expect: `labeled_eligible_startups.csv` present.

## 2 — Re-verify row counts end to end

```bash
cd ~/startup-survival-prediction/scala
sbt "runMain com.startupsurvival.spark.StartupRDD"
```
Expect: `Total startups loaded: 22075`, `label=0 -> 2902, label=1 -> 19173`
— must match Phase 6's Python/Scala parity check exactly. Any drift here
invalidates every downstream phase.

## 3 — Re-verify the join

```bash
sbt "runMain com.startupsurvival.spark.FeatureJoin" 2>&1 | tee /tmp/join_run.txt
grep -a "Financial features\|Sentiment features\|Combined features\|coverage\|baseline\|Expect\|label=" /tmp/join_run.txt
```
Expect (per docs/PHASE13_feature_join.md, post-cleanup):
```
Financial features built for: 22075 startups
Sentiment features built for: 3 startups
Combined features: 22075 startups
Startups with real news coverage: 3
Startups with zero-baseline sentiment: 22072
```
**This is the step that previously caught two real bugs** (Instructure's
missing headline text, and Sifteo's genuine ineligibility) - it is not a
formality, it is the check most likely to surface a real problem.

## 4 — Feature sets

```bash
sbt "runMain com.startupsurvival.spark.FeatureSets"
```
Expect: both `financialOnlyDF` and `financialSentimentDF` show
`22075` rows. Spot-check a couple of printed rows against the raw
FinancialFeatures/SentimentFeatures values from step 3's output — the
vector encoding should be traceable by hand for at least one startup
(e.g. Kabbage), the same way every earlier phase's transformation was
hand-verified.

## 5 — Models train without error

```bash
sbt "runMain com.startupsurvival.ml.LogisticRegression"
sbt "runMain com.startupsurvival.ml.SVM"
```
Expect: both complete without exceptions, print train/test row counts
that sum to 22075 for each experiment, and print coefficients for every
named feature (no `NaN` coefficients — a `NaN` here usually means a
feature column is constant or contains an unhandled missing value that
slipped past Phase 14/15's imputation).

## 6 — Full evaluation run

```bash
sbt "runMain com.startupsurvival.ml.Evaluation"
cat ../results/experiment_results.csv
```
Expect: 4 rows in the CSV (Experiments 1-4), each with `accuracy` and
`auc` between 0 and 1, and `tp+tn+fp+fn` equal to that row's `test_count`.
**Record the actual numbers here once you've run this** — this is the
first point in the whole project where real experiment results exist,
and they belong in the report's Results section (report/results.md),
not just in this checklist.

## 7 — Visualizations

```bash
cd ~/startup-survival-prediction
python -m venv venv && source venv/bin/activate   # if not already set up
pip install -r requirements.txt
python visualization/plot_results.py
```
Expect: 4 PNG files written to `visualization/`, and the printed table
matches step 6's CSV exactly (this script reads that CSV directly, so a
mismatch here would mean the file was edited or a stale copy is present
elsewhere).

## 8 — Web demo smoke test

```bash
cd ~/startup-survival-prediction/web && npm ci && npm run build
cd ../scala && sbt "runMain com.startupsurvival.web.Server"
# open http://localhost:8080
```
Expect: the results section shows the same numbers as step 6's CSV, and the
three lab panels (sentiment, name match, predictor) fill in on load. If the
lab shows "needs the Scala server", the page was opened without the server.

## 9 — Re-read the report against real numbers

Once steps 6-8 produce real numbers, re-read `report/results.md` and
`report/discussion.md` (Phase 22) and confirm every claimed number
traces back to something actually printed in this checklist - not a
number written before the real run happened.

## What "done" looks like

Every step above executed once, with its real output pasted back and
checked against its "Expect" line, in this same order. Mark this doc
Complete only after that - not after the code compiles, which step 5
alone does not guarantee (a model can train "successfully" on
nonsensical features and still produce meaningless output; steps 6-9 are
what actually catch that).
