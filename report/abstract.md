# Evaluating the Predictive Value of Public News Sentiment for Startup Survival

*Using Scala and Apache Spark — B.Tech CSE End-Semester Big Data Project*

## Abstract

We investigate whether sentiment extracted from public news headlines
improves the prediction of startup survival beyond structured attributes.
We built a distributed pipeline on a Crunchbase snapshot of 66,368 startups:

- HDFS for storage and Hive for structured exploration.
- Functional Scala for news matching and lexicon-based sentiment scoring.
- Spark key/value RDDs for aggregation and joins.
- Spark MLlib for Logistic Regression and linear SVM classifiers.

A strict prediction cutoff (2013-01-01) leaves 22,075 eligible startups
(13.15% closed). We collected pre-cutoff Google News headlines for a
balanced random cohort of 815 startups with a resumable, rate-limit-aware
Scala collector, and evaluated every model with stratified 5-fold
cross-validation and paired bootstrap confidence intervals.

The analysis produced three findings:

- **Leakage.** One financial indicator carried post-cutoff information;
  removing it lowered AUC by 0.044 (95% CI [−0.054, −0.034]). Leak-free
  structural features still reach AUC 0.679.
- **News volume** did not improve prediction (ΔAUC ≈ 0, p = 0.80).
- **News tone** added about +0.01 AUC with both models. This is borderline
  (p = 0.047 for Logistic Regression) and not significant after
  multiple-comparison correction.

A manual audit explains the weak signal: only 8.5% of cohort startups had
matched news, and only 29% of matched headlines were about the company.
We conclude that sentiment carries at most a small signal at this coverage
and matching precision. The verified, reproducible pipeline (31 unit tests)
can test the question at larger scale.

**Keywords:** startup survival, sentiment analysis, Apache Spark, MLlib,
HDFS, Hive, Scala, data leakage, class imbalance

# 1. Introduction

Most startups fail, and predicting which ones will survive is of interest to
investors, founders and policymakers. Structured data such as funding
amounts, round counts and company age is widely available through
databases like Crunchbase. Public news coverage offers a second, unstructured
signal: the tone of what is written about a company may reflect momentum or
trouble before either shows up in funding records.

This project asks whether that unstructured signal adds **incremental**
predictive value. It is designed as a controlled comparison rather than as a
search for the best possible model: the same models are trained with and
without sentiment features, so that any difference can be attributed to
sentiment alone.

The project also has a systems goal: implementing the full workflow on the
Big Data stack covered by the course. That means distributed storage (HDFS),
warehousing (Hive), functional data processing (Scala immutable collections,
higher-order functions and pattern matching), distributed computation
(Spark RDDs, key/value joins, persistence) and distributed machine learning
(MLlib).

The main methodological challenge is **temporal leakage**. The dataset's
labels date from about 2014, and news collected today would reveal outcomes
directly. Every stage therefore enforces a single prediction cutoff, and
only information dated before it is allowed into the features.

# 2. Related work

- **Distributed data processing.** MapReduce [1] and HDFS [2] established the
  batch-processing model, and Hive [3] added SQL-style warehousing on top.
  Spark's Resilient Distributed Datasets [4] generalised this model with
  in-memory, lineage-based fault tolerance, and MLlib [5] provides
  distributed implementations of standard learners.
- **Lexicon-based sentiment analysis** counts matches against curated
  positive and negative word lists [6]. Loughran and McDonald [7] showed that
  general-purpose lexicons misclassify financial text, which motivates our
  domain-specific startup-news lexicon.
- **Models and imbalance.** Support-vector machines [8] and logistic
  regression are standard linear baselines. Class reweighting is a common
  remedy for imbalanced labels [9], and is necessary here given the
  1 : 6.6 closed-to-operating ratio.
