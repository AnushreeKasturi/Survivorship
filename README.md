# Evaluating the Predictive Value of Public News Sentiment for Startup Survival

[![CI](https://img.shields.io/github/actions/workflow/status/AnushreeKasturi/Survivorship/scala.yml?branch=main&label=CI&logo=githubactions&logoColor=white)](https://github.com/AnushreeKasturi/Survivorship/actions/workflows/scala.yml)
[![Watchlist refresh](https://img.shields.io/github/actions/workflow/status/AnushreeKasturi/Survivorship/watchlist.yml?branch=main&label=watchlist&logo=githubactions&logoColor=white)](https://github.com/AnushreeKasturi/Survivorship/actions/workflows/watchlist.yml)
[![Live demo](https://img.shields.io/badge/live%20demo-onrender.com-3b55e6?logo=render&logoColor=white)](https://startup-survival-demo.onrender.com)
[![License: MIT](https://img.shields.io/badge/license-MIT-0f9e92)](LICENSE)
[![Scala 2.12](https://img.shields.io/badge/Scala-2.12.18-DC322F?logo=scala&logoColor=white)](scala/build.sbt)
[![Spark 3.5](https://img.shields.io/badge/Apache%20Spark-3.5.1-E25A1C?logo=apachespark&logoColor=white)](scala/build.sbt)
[![Astro](https://img.shields.io/badge/Astro-7-BC52EE?logo=astro&logoColor=white)](web/package.json)

**Using Scala and Apache Spark**

B.Tech CSE — End-Semester Big Data Project

**Live: [startup-survival-demo.onrender.com](https://startup-survival-demo.onrender.com)**
(free instance; the first visit after a quiet spell takes about 30 seconds to wake it)

---

## Research question

> Does incorporating public news sentiment about a startup improve the
> predictive accuracy of a startup survival/closure classifier, compared to
> using structured funding/financial attributes alone?

The methodology is designed so that "yes", "no", and "it depends on the model"
are all valid outcomes. No result is assumed in advance.

## Experimental design (2×2)

|                        | Logistic Regression | Binary SVM   |
|------------------------|---------------------|--------------|
| Financial only         | Experiment 1        | Experiment 2 |
| Financial + Sentiment  | Experiment 3        | Experiment 4 |

Reported per experiment: Accuracy, Precision, Recall, F1, and a confusion
matrix (TP/TN/FP/FN). **All results come from actual execution — nothing in
this repository may contain fabricated numbers.**

That single-split design (Phase 18) was then replaced as the headline
evaluation by **stratified 5-fold cross-validation with paired bootstrap
intervals** (Phase 18b), after a leak was found in one financial feature.

## Findings

From `results/cv_summary.csv` and `results/significance.csv` (logistic regression):

| Question | Result |
|---|---|
| Does the original financial set leak post-cutoff information? | Yes: removing it costs 0.044 AUC (95% CI −0.054 to −0.034) |
| How well do leak-free structural features alone do? | AUC 0.679 on all 22,075 startups |
| Does the *volume* of news help? | No: +0.001 AUC (CI −0.010 to +0.012) |
| Does the *tone* of news help? | A little: +0.010 AUC (CI +0.000 to +0.021, p = 0.047), not significant after multiple-comparison correction |

Details in `report/results.md` and `docs/PHASE18b_cross_validated_experiments.md`.

## Model benchmark

Every change to the live structural model is judged by `ml/Benchmark.scala`:
the same stratified 5 folds as `ExperimentRunner`, all 22,075 startups,
leak-free features only, and a paired bootstrap of out-of-fold scores against
the baseline. A candidate counts as an improvement only when its AUC gain's 95%
interval is above zero. From `results/benchmark.json`:

| Candidate | AUC | vs logistic regression | Case studies (8) |
|---|---|---|---|
| Logistic regression (baseline) | 0.679 | | 3 |
| Logistic regression, L2 0.1 | 0.678 | −0.001, worse | 3 |
| Gradient-boosted trees | 0.689 | +0.010 [+0.002, +0.017], better | 5 |
| Random forest, 200 trees | 0.698 | +0.019 [+0.012, +0.025], better | 5 |
| **Random forest, 100 trees (live)** | **0.699** | **+0.019 [+0.013, +0.026], better** | **5** |

The case studies are a sanity check only: eight outcomes can't tell models
apart. To try an enhancement, add a `Candidate` and run:

```bash
cd scala
sbt "runMain com.startupsurvival.ml.Benchmark file:///path/to/labeled_eligible_startups.csv"
# add --export=<candidate> to write results/model_structural.json for the server
```

The export writes the forest as plain JSON and checks that it reproduces
Spark's probabilities (max difference 4e-16 on 2,000 startups). The news model
is still logistic regression: its batch-collected headlines aren't in the repo.

## Technology stack

| Layer | Technology | Purpose |
|---|---|---|
| Storage | HDFS | Distributed raw/processed/output storage |
| Warehouse | Apache Hive | Structured startup table, SQL exploration |
| Processing | Scala | Cleaning, tokenisation, name matching, sentiment scoring |
| Distributed compute | Apache Spark (RDD API) | Key/value RDDs, joins, aggregation, persistence |
| ML | Spark MLlib | Logistic Regression, Binary SVM |
| Support | Python (pandas) | One-off EDA and plotting only |
| Web demo | Astro, GSAP, JDK `HttpServer` + json4s | Live site and JSON API over the same Scala code |
| Hosting | Docker on Render, Render Key Value | Public demo; visitor-added startups |

Versions are pinned in Phase 5. Python is confined to exploration and
visualisation; the graded pipeline is Scala + Spark.

## Repository layout

```
├── docs/                 Phase-by-phase design records + PHASE_PROGRESS.md
├── data/raw/             Datasets (gitignored; see data/raw/README.md)
├── scripts/              Phase 1-2 Python EDA and labelling
├── hadoop/, hive/        Phase 4 HDFS commands and Hive schema/queries
├── scala/src/main/scala/com/startupsurvival/
│   ├── DataCleaner, NewsCollector, NameMatcher,
│   │   SentimentScorer, FeatureExtractor,
│   │   FinancialFeatureExtractor          Phases 6-11b (plain Scala)
│   ├── BatchNewsCollector     Phase 13b resumable, balanced news collection
│   ├── spark/            Phases 12-15b (RDDs, join, feature sets; Pipeline wires them)
│   ├── ml/               Phases 16-18b (LR, SVM, evaluation, CV runner)
│   └── web/              Phase 20 API + static server (Server), watchlist and case studies (Tracker)
├── scala/src/test/scala/ ScalaTest unit tests (sbt test)
├── web/                  Phase 20 site (Astro + GSAP): overview, how it works, startups
├── startups/             Hand-checked case studies and watchlist, with sources
├── visualization/        Phase 19/19b plots
├── results/              Generated by Evaluation / ExperimentRunner - never hand-edited
└── report/               Phase 22 report, Phase 23 slides
```

## Web demo (Phase 20)

The site in `web/` explains the pipeline and runs the real Scala code live:
sentiment scoring, name matching, and survival prediction with the random
forest from `results/model_structural.json` (and the news model from
`results/model_coefficients_leakfree.json`). Results are baked in from
`results/` at build time. Four pages:

- **Overview:** the population, the pipeline, the predictor and the cross-validated results.
- **Startups:** eight startups with known endings and the live watchlist, as cards.
- **Archive:** every startup ever scored, with score history, plus a queue for visitor suggestions.
- **How it works:** the method end to end, from the 2013 cutoff to the benchmark and the scheduler.

The startups in `startups/` were checked against the public sources listed with
each one; dates known only to the year are set to 1 July. Case studies are
scored offline, as of two years after the first round (or six months before
closing), with news from the two years before that:

```bash
cd scala && sbt "runMain com.startupsurvival.web.Tracker"   # writes results/case_studies.json
```

The watchlist is scored live: every six hours the server reads the latest
GDELT bulk files into a news archive, then re-scores `startups/watchlist.json`
and visitor additions with news from Google News and that archive, with today
as the cutoff. Visitor suggestions wait in a queue that is scored every ten
minutes while the server is awake (`QUEUE_MINUTES`). Each score is kept as
history. `.github/workflows/watchlist.yml` wakes the free Render instance every
six hours. Set `KV_URL` (a `redis://` URL, such as a Render Key Value internal
URL) to keep suggestions, history and the news archive across restarts.
`docs/NEWS_AGGREGATION.md` covers how news is collected and where it should go.

Two live-only adjustments, never used in training:

- **Business news only:** a headline that names the company only counts if
  `NewsRelevance` judges it business news (an event such as funding, revenue,
  hiring, a deal, a lawsuit or a shutdown), not a review, listing, how-to or
  social post. The evidence on each card lists what was set aside and why.
- **Everyday-word names** (Ramp, Notion, Stripe): profiles can list `keywords`
  (a founder, a product). The news search is narrowed with them, and a
  headline must spell the name exactly as the company does.
- **Maturity warning:** the model learned from startups judged within a few
  years of their first round, so long-established companies score by
  extrapolation. `results/feature_ranges.json` (from `spark.FeatureRanges`)
  holds the training data's range: 90% were within 6.8 years of their first
  round and 99% within 13. The site flags companies past those points.

```bash
cd web && npm ci && npm run build       # Node 22.12+
cd ../scala && sbt "runMain com.startupsurvival.web.Server"
# http://localhost:8080   (HOST, PORT, SITE_DIR, MODEL_COEFFICIENTS, STRUCTURAL_MODEL, WATCHLIST, KV_URL, FEATURE_RANGES, QUEUE_MINUTES)
```

For front-end work, run `npm run dev` in `web/` with the Scala server still
running; `/api` is proxied to port 8080.

### Deploying to Render

The `Dockerfile` builds the site and the server into one image
(`docker build -t demo . && docker run -p 8080:8080 demo`). On Render, choose
New → Blueprint and select this repo; `render.yaml` sets up a free Docker web
service and asks for an optional `KV_URL`. Free instances sleep when idle, so
the first visit can take ~30 s; the watchlist workflow wakes it every six hours.
Merges to `main` deploy automatically.

## Running the pipeline

Every Spark entry point reads from HDFS by default and accepts an alternative
path as its first argument, for example:

```bash
cd scala
sbt "runMain com.startupsurvival.ml.Evaluation file:///path/to/labeled_eligible_startups.csv"
```

Reproducing the final results:

```bash
start-dfs.sh                                       # HDFS (see hadoop/hdfs_commands.md)
cd scala
sbt test                                           # unit tests
sbt "runMain com.startupsurvival.BatchNewsCollector ../data/processed/labeled_eligible_startups.csv ../data/news 5804"
#   upload data/news/*.tsv to HDFS as in docs/PHASE13b_batch_news_collection.md
sbt "runMain com.startupsurvival.ml.Evaluation"        # Phase 18 baseline (single split)
sbt "runMain com.startupsurvival.ml.ExperimentRunner"  # Phase 18b: 5-fold CV + bootstrap
cd ..
python visualization/plot_results.py && python visualization/plot_cv_results.py
```

The plain-Scala phases (`FinancialFeatureExtractor`, `NameMatcher`,
`SentimentScorer`, `FeatureExtractor`) double as self-checks: their `main`s
assert the hand-verified values, and CI runs them alongside `sbt test`.

## Getting started (Phase 1)

```bash
python3 -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

Download the dataset per `data/raw/README.md`, then:

```bash
python scripts/inspect_dataset.py data/raw/big_startup_secsees_dataset.csv \
    | tee results/phase1_inspection.txt
```

## Important design constraint

The chosen dataset is a Crunchbase snapshot from around **October 2013**,
while collectable news is current. Naively pairing the two would introduce
**more than a decade of future information** into the features — catastrophic data
leakage that would invalidate every result.

The project therefore adopts a **retrospective cutoff design**: prediction
cutoff `T = 2013-01-01`, features drawn only from before `T`, label taken from
the post-`T` snapshot. Full reasoning, the verified news-API limitations, and
the fallback plan are in `docs/PHASE01_dataset_analysis.md` §1.4–1.5.

**Read that section before writing any pipeline code.**

## Team

| Member | Area |
|---|---|
| 1 | Data engineering — dataset, HDFS, Hive, ingestion |
| 2 | Scala functional processing — cleaning, matching, sentiment, features |
| 3 | Spark + MLlib — RDDs, joins, feature vectors, LR, SVM |
| 4 | Evaluation, visualisation, web demo, report, presentation |

## Working rule

Phases are completed and verified in order. Do not begin a phase until the
previous one demonstrably works. Track state in `docs/PHASE_PROGRESS.md`.

## Data attribution

Primary dataset: *Startup Success/Fail Dataset from Crunchbase* (Kaggle, user
`yanmaksi`), published under the Community Data License Agreement – Sharing
v1.0. Raw data is not redistributed in this repository.

## License

The code is released under the [MIT License](LICENSE). The dataset keeps its
own CDLA-Sharing 1.0 licence (see above), and the bundled fonts (Instrument
Serif, Geist, Geist Mono, via Fontsource) are under the SIL Open Font License.
