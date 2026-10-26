# HDFS — Layout, Commands and Concepts (Phases 4 and 13b)

All output below is from the project's real single-node cluster
(Hadoop 3.3.6, pseudo-distributed, WSL2 Ubuntu 24.04), captured on
2026-10-26.

## Cluster configuration

| Setting | Value | Meaning |
|---|---|---|
| `fs.defaultFS` | `hdfs://localhost:9000` | NameNode RPC address that every `hdfs://` path in the Scala code resolves against |
| `dfs.blocksize` | 134,217,728 (128 MB) | Files are split into blocks of this size |
| `dfs.replication` | 1 | One copy of each block — the only sensible value with one DataNode |
| Capacity | 1006.85 GB configured, 27.59 MB used | |

Daemons (`jps`): **NameNode**, **DataNode**, **SecondaryNameNode**.

## Directory layout

```
/startup-survival
├── raw/
│   ├── startups/big_startup_secsees_dataset.csv        10,942,450 B   Phase 1 raw snapshot
│   └── news/
│       ├── headlines/raw_news.tsv                                     Phase 13b raw headlines
│       └── log/collection_log.tsv                                     Phase 13b per-startup outcome
├── processed/
│   ├── startups/labeled_eligible_startups.csv           3,777,637 B   Phase 2 eligible, labeled
│   ├── news/                                                          (reserved)
│   └── features/                                                      (reserved)
└── output/
    ├── predictions/                                                   (reserved)
    └── evaluation/                                                    (reserved)
```

Each news file has its own directory because a Hive external table
(`hive/news_analysis.sql`) reads *every* file in its `LOCATION`.

## Commands used

```bash
# start / check the daemons
start-dfs.sh
jps                                     # NameNode, DataNode, SecondaryNameNode
hdfs dfsadmin -safemode wait            # NameNode leaves safe mode once blocks are reported

# create the tree (Phase 4)
hdfs dfs -mkdir -p /startup-survival/{raw/startups,processed/startups,processed/news,processed/features,output/predictions,output/evaluation}

# upload (Phases 4 and 13b)
hdfs dfs -put data/raw/big_startup_secsees_dataset.csv       /startup-survival/raw/startups/
hdfs dfs -put data/processed/labeled_eligible_startups.csv   /startup-survival/processed/startups/
hdfs dfs -mkdir -p /startup-survival/raw/news/headlines /startup-survival/raw/news/log
hdfs dfs -put -f data/news/raw_news.tsv        /startup-survival/raw/news/headlines/
hdfs dfs -put -f data/news/collection_log.tsv  /startup-survival/raw/news/log/

# inspect
hdfs dfs -ls -R /startup-survival
hdfs dfs -cat /startup-survival/processed/startups/labeled_eligible_startups.csv | head -3
hdfs fsck /startup-survival/processed/startups/labeled_eligible_startups.csv -files -blocks -locations
hdfs dfsadmin -report
```

## Block and replication check (real `fsck` output)

```
/startup-survival/processed/startups/labeled_eligible_startups.csv 3777637 bytes,
  replicated: replication=1, 1 block(s):  OK
0. BP-2021888464-127.0.1.1-1789561945882:blk_1073741826_1002 len=3777637 Live_repl=1
   [DatanodeInfoWithStorage[127.0.0.1:9866,...,DISK]]

Status: HEALTHY
 Total blocks (validated):  1 (avg. block size 3777637 B)
 Minimally replicated blocks: 1 (100.0 %)
 Under-replicated blocks:     0 (0.0 %)
 Missing blocks:              0
 Corrupt blocks:              0
```

## How the concepts show up in this project

- **NameNode** holds only metadata: the namespace above, and the mapping
  from each file to its blocks (here `blk_1073741826`) and from each block
  to its DataNode. When Spark's `sc.textFile(...)` opens
  `hdfs://localhost:9000/...`, it asks the NameNode for block locations,
  then reads the bytes directly from the DataNode.
- **DataNode** stores the block contents (`127.0.0.1:9866`) and reports
  its blocks to the NameNode; until those reports arrive the NameNode stays
  in safe mode, which is why scripts run `dfsadmin -safemode wait`.
- **Blocks.** The 3.8 MB eligible file is far below the 128 MB block size,
  so it occupies one block, and one block means one input split. Spark
  still creates at least 2 partitions by default (`minPartitions`) by
  splitting the file at byte offsets within that block.
- **Replication** is 1 because there is one DataNode; extra replicas
  would have nowhere to go (`fsck` reports 0 under-replicated blocks). On a
  multi-node cluster, the default of 3 lets a lost DataNode's blocks be
  re-replicated from the survivors.
- **SecondaryNameNode** periodically merges the NameNode's edit log into
  its fsimage checkpoint. It is not a hot standby.
- **Write-once.** HDFS files cannot be edited in place, so updated news
  files are replaced with `-put -f`, not appended to.
