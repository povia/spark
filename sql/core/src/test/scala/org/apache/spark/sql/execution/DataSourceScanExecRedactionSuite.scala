/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution

import java.io.File

import scala.collection.mutable

import org.apache.hadoop.fs.Path

import org.apache.spark.SparkConf
import org.apache.spark.scheduler.{SparkListener, SparkListenerTaskEnd}
import org.apache.spark.sql.{DataFrame, QueryTest}
import org.apache.spark.sql.execution.datasources.v2.BatchScanExec
import org.apache.spark.sql.execution.datasources.v2.orc.OrcScan
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Test suite base for testing the redaction of DataSourceScanExec/BatchScanExec.
 */
abstract class DataSourceScanRedactionTest extends QueryTest with SharedSparkSession {

  override protected def sparkConf: SparkConf = super.sparkConf
    .set("spark.redaction.string.regex", "file:/[^\\]\\s]+")

  final protected def isIncluded(queryExecution: QueryExecution, msg: String): Boolean = {
    queryExecution.toString.contains(msg) ||
      queryExecution.simpleString.contains(msg) ||
      queryExecution.stringWithStats.contains(msg)
  }

  protected def getRootPath(df: DataFrame): Path

  test("treeString is redacted") {
    withTempDir { dir =>
      val basePath = dir.getCanonicalPath
      spark.range(0, 10).toDF("a").write.orc(new Path(basePath, "foo=1").toString)
      val df = spark.read.orc(basePath)

      val rootPath = getRootPath(df)
      assert(rootPath.toString.contains(dir.toURI.getPath.stripSuffix("/")))

      assert(!df.queryExecution.sparkPlan.treeString(verbose = true).contains(rootPath.getName))
      assert(!df.queryExecution.executedPlan.treeString(verbose = true).contains(rootPath.getName))
      assert(!df.queryExecution.toString.contains(rootPath.getName))
      assert(!df.queryExecution.simpleString.contains(rootPath.getName))

      val replacement = "*********"
      assert(df.queryExecution.sparkPlan.treeString(verbose = true).contains(replacement))
      assert(df.queryExecution.executedPlan.treeString(verbose = true).contains(replacement))
      assert(df.queryExecution.toString.contains(replacement))
      assert(df.queryExecution.simpleString.contains(replacement))
    }
  }
}

/**
 * Suite that tests the redaction of DataSourceScanExec
 */
class DataSourceScanExecRedactionSuite extends DataSourceScanRedactionTest {
  override protected def sparkConf: SparkConf = super.sparkConf
    .set(SQLConf.USE_V1_SOURCE_LIST.key, "orc")

  override protected def getRootPath(df: DataFrame): Path =
    df.queryExecution.sparkPlan.find(_.isInstanceOf[FileSourceScanExec]).get
      .asInstanceOf[FileSourceScanExec].relation.location.rootPaths.head

  test("explain is redacted using SQLConf") {
    withTempDir { dir =>
      val basePath = dir.getCanonicalPath
      spark.range(0, 10).toDF("a").write.orc(new Path(basePath, "foo=1").toString)
      val df = spark.read.orc(basePath)
      val replacement = "*********"

      // Respect SparkConf and replace file:/
      assert(isIncluded(df.queryExecution, replacement))

      assert(isIncluded(df.queryExecution, "FileScan"))
      assert(!isIncluded(df.queryExecution, "file:/"))

      withSQLConf(SQLConf.SQL_STRING_REDACTION_PATTERN.key -> "(?i)FileScan") {
        // Respect SQLConf and replace FileScan
        assert(isIncluded(df.queryExecution, replacement))

        assert(!isIncluded(df.queryExecution, "FileScan"))
        assert(isIncluded(df.queryExecution, "file:/"))
      }
    }
  }

  test("FileSourceScanExec metadata") {
    withTempPath { path =>
      val dir = path.getCanonicalPath
      spark.range(0, 10).write.orc(dir)
      val df = spark.read.orc(dir)

      assert(isIncluded(df.queryExecution, "Format"))
      assert(isIncluded(df.queryExecution, "ReadSchema"))
      assert(isIncluded(df.queryExecution, "Batched"))
      assert(isIncluded(df.queryExecution, "PartitionFilters"))
      assert(isIncluded(df.queryExecution, "PushedFilters"))
      assert(isIncluded(df.queryExecution, "DataFilters"))
      assert(isIncluded(df.queryExecution, "Location"))
    }
  }

  test("SPARK-31793: FileSourceScanExec metadata should contain limited file paths") {
    withTempPath { path =>
      val dir = path.getCanonicalPath
      val partitionCol = "partitionCol"
      spark.range(10)
        .select("id", "id")
        .toDF("value", partitionCol)
        .write
        .partitionBy(partitionCol)
        .orc(dir)
      val paths = (0 to 9).map(i => new File(dir, s"$partitionCol=$i").getCanonicalPath)
      val plan = spark.read.orc(paths: _*).queryExecution.executedPlan
      val location = plan collectFirst {
        case f: FileSourceScanExec => f.metadata("Location")
      }
      assert(location.isDefined)
      // The location metadata should at least contain one path
      assert(location.get.contains(paths.head))

      // The location metadata should have bracket wrapping paths
      assert(location.get.indexOf('[') > -1)
      assert(location.get.indexOf(']') > -1)

      // extract paths in location metadata (removing classname, brackets, separators)
      val pathsInLocation = location.get.substring(
        location.get.indexOf('[') + 1, location.get.indexOf(']')).split(", ").toSeq

      // If the temp path length is less than (stop appending threshold - 1), say, 100 - 1 = 99,
      // location should include more than one paths. Otherwise location should include only one
      // path.
      // (Note we apply subtraction with 1 to count start bracket '['.)
      if (paths.head.length < 99) {
        assert(pathsInLocation.size >= 2)
      } else {
        assert(pathsInLocation.size == 1)
      }
    }
  }
}

/**
 * Suite that tests the redaction of BatchScanExec.
 */
class DataSourceV2ScanExecRedactionSuite extends DataSourceScanRedactionTest {

  override protected def sparkConf: SparkConf = super.sparkConf
    .set(SQLConf.USE_V1_SOURCE_LIST.key, "")

  override protected def getRootPath(df: DataFrame): Path =
    df.queryExecution.sparkPlan.find(_.isInstanceOf[BatchScanExec]).get
      .asInstanceOf[BatchScanExec].scan.asInstanceOf[OrcScan].fileIndex.rootPaths.head

  test("explain is redacted using SQLConf") {
    withTempDir { dir =>
      val basePath = dir.getCanonicalPath
      spark.range(0, 10).toDF("a").write.orc(new Path(basePath, "foo=1").toString)
      val df = spark.read.orc(basePath)
      val replacement = "*********"

      // Respect SparkConf and replace file:/
      assert(isIncluded(df.queryExecution, replacement))
      assert(isIncluded(df.queryExecution, "BatchScan"))
      assert(!isIncluded(df.queryExecution, "file:/"))

      withSQLConf(SQLConf.SQL_STRING_REDACTION_PATTERN.key -> "(?i)BatchScan") {
        // Respect SQLConf and replace FileScan
        assert(isIncluded(df.queryExecution, replacement))

        assert(!isIncluded(df.queryExecution, "BatchScan"))
        assert(isIncluded(df.queryExecution, "file:/"))
      }
    }
  }

  test("FileScan description") {
    Seq("json", "orc", "parquet").foreach { format =>
      withTempPath { path =>
        val dir = path.getCanonicalPath
        spark.range(0, 10).write.format(format).save(dir)
        val df = spark.read.format(format).load(dir)

        withClue(s"Source '$format':") {
          assert(isIncluded(df.queryExecution, "ReadSchema"))
          assert(isIncluded(df.queryExecution, "BatchScan"))
          if (Seq("orc", "parquet").contains(format)) {
            assert(isIncluded(df.queryExecution, "PushedFilters"))
          }
          assert(isIncluded(df.queryExecution, "Location"))
        }
      }
    }
  }

  test("SPARK-30362: test input metrics for DSV2") {
    withSQLConf(SQLConf.USE_V1_SOURCE_LIST.key -> "") {
      Seq("json", "orc", "parquet").foreach { format =>
        withTempPath { path =>
          val dir = path.getCanonicalPath
          spark.range(0, 10).write.format(format).save(dir)
          val df = spark.read.format(format).load(dir)
          val bytesReads = new mutable.ArrayBuffer[Long]()
          val recordsRead = new mutable.ArrayBuffer[Long]()
          val bytesReadListener = new SparkListener() {
            override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {
              bytesReads += taskEnd.taskMetrics.inputMetrics.bytesRead
              recordsRead += taskEnd.taskMetrics.inputMetrics.recordsRead
            }
          }
          sparkContext.addSparkListener(bytesReadListener)
          try {
            df.collect()
            sparkContext.listenerBus.waitUntilEmpty()
            assert(bytesReads.sum > 0)
            assert(recordsRead.sum == 10)
          } finally {
            sparkContext.removeSparkListener(bytesReadListener)
          }
        }
      }
    }
  }
}
