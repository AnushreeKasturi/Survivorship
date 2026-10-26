-- PHASE 13b - Hive view of the batch-collected news
--
-- The same HDFS files Spark reads in NewsRDD.load / loadCohort, exposed as
-- external tables so coverage can be explored with SQL. Raw headlines are
-- stored before name matching, so these counts are of RAW (unmatched)
-- pre-cutoff items; matched counts come from NewsRDD's output.
--
-- Run:  hive -f hive/news_analysis.sql

USE startup_survival;

DROP TABLE IF EXISTS news_raw;
DROP TABLE IF EXISTS news_collection_log;

CREATE EXTERNAL TABLE news_raw (
    permalink STRING,
    company_name STRING,
    publication_date DATE,
    source STRING,
    title STRING
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
STORED AS TEXTFILE
LOCATION '/startup-survival/raw/news/headlines/';

CREATE EXTERNAL TABLE news_collection_log (
    permalink STRING,
    status STRING,
    raw_item_count INT,
    retrieved_on DATE
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
STORED AS TEXTFILE
LOCATION '/startup-survival/raw/news/log/';

-- 1. Collection outcome
SELECT status, COUNT(*) AS startups
FROM news_collection_log
GROUP BY status;

-- 2. Cohort composition and raw coverage by label
SELECT s.label,
       COUNT(*)                                          AS startups_queried,
       SUM(CASE WHEN l.raw_item_count > 0 THEN 1 ELSE 0 END) AS with_any_raw_item,
       ROUND(AVG(l.raw_item_count), 2)                   AS avg_raw_items
FROM news_collection_log l
JOIN startups_eligible s ON s.permalink = l.permalink
WHERE l.status = 'ok'
GROUP BY s.label
ORDER BY s.label;

-- 3. Leakage sanity check: must return 0 rows
SELECT COUNT(*) AS post_cutoff_items
FROM news_raw
WHERE publication_date >= DATE '2013-01-01';

-- 4. Headlines per year of publication
SELECT YEAR(publication_date) AS yr, COUNT(*) AS items
FROM news_raw
GROUP BY YEAR(publication_date)
ORDER BY yr;

-- 5. Most frequent sources
SELECT source, COUNT(*) AS items
FROM news_raw
GROUP BY source
ORDER BY items DESC
LIMIT 15;
