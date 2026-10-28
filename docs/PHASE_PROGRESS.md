# Phase Progress Tracker

| # | Phase | Owner | Status |
|---|-------|-------|--------|
| 0 | Scope & feasibility | All | Complete |
| 1 | Dataset selection & inspection | M1 | Complete — verified on real data |
| 2 | Label design & prediction cutoff | M1 | Complete — verified on real data (22,075 rows) |
| 3 | Schema & architecture design | All | Complete |
| 4 | Hadoop / HDFS / Hive setup | M1 | Complete — verified on real cluster |
| 5 | Scala + Spark environment | M2, M3 | Complete — verified compile + HDFS read |
| 6 | Startup data preprocessing | M1, M2 | Complete — verified on real data, exact match (22,075 / 2,902 / 19,173) |
| 7 | News collection (+ archival feasibility spike) | M2 | Complete — live RSS test verified working, dates accurate |
| 8 | News cleaning & name matching | M2 | Complete — NameMatcher.scala compiled and verified against real Phase 7 data |
| 9 | Scala sentiment scoring | M2 | Complete — matches brief's worked example exactly, verified against real Phase 7 data |
| 10 | Sentiment feature engineering | M2 | Complete — FeatureExtractor.scala verified with hand-checked math on real multi-phase data |
| 11 | Financial feature engineering | M1, M3 | Complete — leakage guard verified to block leaky amounts; its indicator flag was later found to leak post-cutoff events (fixed in 11b) |
| 11b | Leak-free financial features | M1, M3 | Complete — age, time since first funding, founding-to-funding gap, category, country; no lifetime funding fields (docs/PHASE18b_cross_validated_experiments.md) |
| 12 | Spark RDD pipeline | M3 | Complete — verified on real Spark (22075 confirmed via real run) |
| 13 | Feature join | M3 | Complete — real data (Kabbage/Color Labs/Pinterest/1-800-DOCTORS), 2 real bugs found & fixed, see docs/PHASE13_feature_join.md; batch-scale news added in 13b |
| 13b | Batch news collection | M2 | Complete — BatchNewsCollector.scala (resumable, balanced case-control order), NameMatcher refinements, Hive news tables (docs/PHASE13b_batch_news_collection.md) |
| 14 | Financial-only feature set | M3 | Complete — 22,075 rows, width 8, verified in Phase 21 |
| 15 | Financial + sentiment feature set | M3 | Complete — 22,075 rows, width 20, verified in Phase 21 |
| 16 | Logistic Regression | M3 | Complete — real run, balanced class weights, 17,732 / 4,343 split |
| 17 | Binary SVM | M3 | Complete — real run, LinearSVC, same split and weights |
| 18 | Evaluate 4 experiments | M3, M4 | Complete — results/experiment_results.csv, reproduced exactly in Phase 21 |
| 18b | Cross-validated experiments | M3, M4 | Complete — ExperimentRunner.scala: stratified 5-fold CV, tuned thresholds, paired bootstrap CIs, coverage/tone ablation |
| 19 | Visualisations | M4 | Complete — 4 PNGs in visualization/, generated from the real CSV |
| 19b | CV visualisations | M4 | Complete — visualization/plot_cv_results.py (leakage, ablation, forest plot, coefficients) |
| 20 | Demonstration interface | M4 | Complete — Astro + GSAP site (web/) served by the Scala API server (web/Server.scala) |
| 21 | Final testing | All | Complete — checklist executed 2026-10-26; plus 31 ScalaTest unit tests (`sbt test`) |
| 22 | Report | M4 | Draft complete — report/abstract.md, methodology.md, results.md, discussion.md, references.md. Needs team review |
| 23 | Presentation | M4 | Draft complete — report/presentation.pptx. Needs team review |
| 24 | Viva preparation | All | Complete — docs/PHASE24_viva_prep.md |

**Rule:** do not begin a phase until the previous one is verified working.

**Headline result:** see report/results.md — Phase 18b supersedes the Phase 18 single-split numbers.
