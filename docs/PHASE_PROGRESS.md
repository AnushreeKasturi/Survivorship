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
| 11 | Financial feature engineering | M1, M3 | Complete — leakage guard verified to actually block leaky values in a real test case |
| 12 | Spark RDD pipeline | M3 | Complete — verified on real Spark (22075 confirmed via real run) |
| 13 | Feature join | M3 | Complete — real data (Kabbage/Color Labs/Pinterest/1-800-DOCTORS), 2 real bugs found & fixed (Instructure unbacked text, Sifteo never-eligible), see docs/PHASE13_feature_join.md |
| 14 | Financial-only feature set | M3 | Code written (FeatureSets.scala), awaiting real Spark run |
| 15 | Financial + sentiment feature set | M3 | Code written (FeatureSets.scala), awaiting real Spark run |
| 16 | Logistic Regression | M3 | Code written (Spark MLlib, per Phase 0/3's fixed architecture), awaiting real run — see docs/PHASE16-18_ml_setup.md first |
| 17 | Binary SVM | M3 | Code written (Spark MLlib LinearSVC), awaiting real run |
| 18 | Evaluate 4 experiments | M3, M4 | Code written (Evaluation.scala — runs Phases 12-18 end to end, writes results/experiment_results.csv), awaiting real run |
| 19 | Visualisations | M4 | Code written (visualization/plot_results.py), reads real results CSV only — no placeholder data, awaiting Phase 18's real output |
| 20 | Demonstration interface | M4 | Complete — Astro + GSAP site (web/) served by the Scala API server (web/Server.scala); verified in a browser against the committed results |
| 21 | Final testing | All | Checklist written (docs/PHASE21_final_testing.md), not yet executed |
| 22 | Report | M4 | Not started — abstract + references already drafted; methodology/results/discussion need Phase 18's real numbers first |
| 23 | Presentation | M4 | Not started |
| 24 | Viva preparation | All | Not started |

**Rule:** do not begin a phase until the previous one is verified working.

**Note (Phases 14-21):** all code for these phases was written in one
pass and has NOT been run on real Spark/data yet - same "not yet
verified" status as every other phase before its first real run. Run
Phase 21's checklist in order and report back the real numbers before
treating any of 14-20 as Complete.
