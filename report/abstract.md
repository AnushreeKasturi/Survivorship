# Evaluating the Predictive Value of Public News Sentiment for Startup Survival

*Using Scala and Apache Spark — B.Tech CSE End-Semester Big Data Project*

## Abstract

We investigate whether sentiment extracted from public news headlines
improves the prediction of startup survival beyond structured funding data.
We built a distributed pipeline on a Crunchbase snapshot of 66,368 startups:

- HDFS for storage and Hive for structured exploration.
- Functional Scala for news matching and lexicon-based sentiment scoring.
- Spark key/value RDDs for feature aggregation and joins.
- Spark MLlib for Logistic Regression and linear SVM classifiers.

A strict prediction cutoff (2013-01-01) and per-feature leakage guards
restrict the models to information available at prediction time, leaving
22,075 eligible startups (13.15% closed).

We ran a 2×2 experiment (financial vs. financial + sentiment features ×
Logistic Regression vs. SVM) with balanced class weights:

- Financial features alone achieved an AUC of 0.708 (LR) and 0.726 (SVM).
- Adding sentiment changed AUC by less than 0.0005 and altered only 2 of
  8,686 test predictions.

This null difference is uninformative rather than negative. Pre-cutoff news
was matched for only 3 startups, so the sentiment features were constant for
99.99% of the population. Our analysis also identified a residual leakage
path in one financial indicator, which likely inflates absolute performance.

The pipeline is verified end to end and reproducible. Answering the research
question requires batch news collection at scale, which we identify as the
primary direction for future work.

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
