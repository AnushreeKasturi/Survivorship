# Phase 12 — Spark RDD Pipeline

**Status:** Code written for `StartupRDD.scala`, `NewsRDD.scala`, and
`Aggregation.scala`. Verified against a hand-built stub of Spark's RDD API
(catches real syntax/type errors) but **not yet run against real Spark** —
my development environment has no Spark installation or internet access to
fetch one. This is your first real run — report the output back so we can
verify it together.

---

## A structural fix made during this phase

The original Phase 0 project layout proposed a top-level `spark/` folder
as a sibling to `scala/`. In practice, `sbt compile` (set up in Phase 5)
only looks inside the single sbt project's source tree
(`scala/src/main/scala/`), so files placed in a sibling folder would never
actually compile. All three files were moved to
`scala/src/main/scala/com/startupsurvival/spark/`, matching their
`package com.startupsurvival.spark` declaration. The old `spark/` folder
now just holds a short README pointing to the real location.

## Verification method, given no local Spark access

A minimal stub of the Spark RDD/SparkSession API — matching only the
methods actually used (`textFile`, `parallelize`, `map`, `filter`,
`flatMap`, `mapValues`, `reduceByKey`, `groupByKey`, `join`, `persist`,
`count`, `take`, `collect`) — was hand-written and all three files
compiled cleanly against it. This confirms the code is structurally and
type-correct against Spark's real API shape, but **cannot confirm runtime
behavior** (actual HDFS connectivity, actual distributed execution,
actual shuffle behavior) — only a real run on your machine can do that.

## What each file does

### `StartupRDD.scala`

Loads the real 22,075-row HDFS file into `RDD[(String, StartupRecord)]`,
reusing Phase 6's `DataCleaner` parsing functions directly — the same
quote-aware CSV parser verified against real data in Phase 6, now running
through Spark instead of a single JVM. Demonstrates `textFile` (RDD
creation from HDFS), `filter`/`map`/`flatMap` (all lazy), `persist()`
(since the RDD is reused across `count()`, `take()`, and the financial-
features step), `reduceByKey` (a shuffle-causing, stage-boundary
transformation, used to count labels distributedly rather than collecting
everything to the driver first), and `mapValues` (running Phase 11's
`FinancialFeatureExtractor` — including its leakage guard — distributed
across the RDD).

### `NewsRDD.scala`

**Important scope note:** a full-scale batch news collection across all
22,075 startups has not been run yet — Phase 7 was a manual spike against
3 companies. This file demonstrates the RDD operations against the same
small **real** dataset already verified in Phase 10 (the actual Kabbage
and Sifteo headlines/dates/scores), not synthetic placeholders. It uses
`parallelize` (the other standard RDD creation method, distributing an
existing in-driver collection — contrast with `StartupRDD`'s `textFile`,
which creates an RDD from an external file), `reduceByKey` (summing
sentiment scores per startup), and `groupByKey` — used deliberately, with
the justification stated directly in-code: Phase 10's trend/volatility
features need the full list of a startup's records, not just a running
sum, which `reduceByKey` cannot reconstruct. The file also runs a **real
join preview** against the actual full `StartupRDD` from HDFS — genuine
proof the join mechanism works, at small scale, ahead of Phase 13's
full-scale version.

### `Aggregation.scala`

Runs Phase 10's `FeatureExtractor.extractSentimentFeatures` — already
hand-verified in Phase 10 — distributed via `mapValues` over the grouped
News RDD. Direct distributed counterpart to `StartupRDD`'s financial-
features `mapValues` call; both feature extractors were written as pure
functions from the start specifically so this one-line distributed
wrapping would be possible without rewriting any logic.

## What to run

```bash
cd ~/startup-survival-prediction/scala
sbt clean compile
sbt "runMain com.startupsurvival.spark.StartupRDD"
```

### Expected output (predicted — verify against your real run)

```
Total startups loaded: 22075
label=0 -> 2902, label=1 -> 19173
```

These must match every prior phase's independent verification (Phase 2
Python, Phase 4 Hive, Phase 5 Spark DataFrame, Phase 6 plain-Scala
DataCleaner). **If this run produces a different number, stop and report
it exactly** — that would mean something about the distributed execution
path (partition handling, HDFS connectivity) is behaving differently from
the single-JVM paths already verified, and needs to be understood before
Phase 13 builds on top of it.

Then:

```bash
sbt "runMain com.startupsurvival.spark.NewsRDD"
sbt "runMain com.startupsurvival.spark.Aggregation"
```

`NewsRDD`'s output should match Phase 10's hand-verified numbers exactly
(Kabbage: newsCount=2, sentiment sum=1; Sifteo: newsCount=4, sentiment
sum=0) — this is the same real data, now running distributed. Any
difference here would indicate a genuine bug in the distributed path, not
just a different environment quirk.

## The real next action after this phase

Phase 13's join only has something meaningful to join once news data
exists at scale, not just for 2 example companies. **Before Phase 13**,
we need an actual batch collection run — `NewsCollector` (Phase 7) +
`NameMatcher` (Phase 8) + `SentimentScorer` (Phase 9) chained together
across a real subset of the 22,075 startups. This is flagged here
explicitly as the next concrete task, not silently assumed to already
exist.

## Next

**Batch news collection run** (bridging Phase 12 and 13), then
**Phase 13 — Feature join**, joining the resulting real `SentimentFeatures`
RDD against `StartupRDD`'s `FinancialFeatures` at full scale, with
`persist()` on the joined result since it feeds both the Financial-only
and Financial+Sentiment feature sets in Phases 14–15.

## RDD concepts demonstrated (viva notes)

Moved here from the old header comment in `StartupRDD.scala`.

- **Creation.** `sc.textFile()` reads the HDFS file as an `RDD[String]` (StartupRDD); `sc.parallelize()` distributes a driver-side collection (NewsRDD).
- **Transformations are lazy.** `filter`, `map`, `flatMap` and `mapValues` only record lineage. `mapValues` keeps the key, and the partitioner, untouched.
- **Actions run the job.** `count()`, `take(n)` and `collect()` execute the DAG. `collect()` is only used on small, already-aggregated results, never on the full 22,075 rows.
- **Partitions.** `textFile` splits roughly along HDFS blocks. `map`, `filter` and `flatMap` are narrow (one input partition per output partition); `reduceByKey`, `groupByKey` and `join` shuffle and form stage boundaries.
- **Lineage and fault tolerance.** Spark does not replicate RDD data. A lost partition is recomputed from its recorded lineage.
- **Persistence.** `persist()` keeps computed partitions after the first action, so later actions on the same RDD don't re-read and re-parse the file.
- **groupByKey vs reduceByKey.** `reduceByKey` pre-combines on each partition and is the default. `groupByKey` is used only where every record is needed together: sentiment trend and volatility need the full per-startup list.
