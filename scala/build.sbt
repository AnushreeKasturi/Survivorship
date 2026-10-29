name := "startup-survival-prediction"
version := "0.1"
scalaVersion := "2.12.18"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "3.5.1",
  "org.apache.spark" %% "spark-sql"  % "3.5.1",
  "org.apache.spark" %% "spark-mllib" % "3.5.1",
  "org.scalatest" %% "scalatest" % "3.2.17" % Test
)

// Flag deprecated APIs and unused imports, privates and locals, so dead code doesn't accumulate.
scalacOptions ++= Seq("-deprecation", "-Ywarn-unused:imports,privates,locals")

// Run in a forked JVM rather than sbt's background-job runner, which has
// a known issue staging dependency jars into a temp directory reliably
// for larger dependency trees like Spark's.
fork := true
