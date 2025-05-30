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

package org.graphframes.lib

import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

import org.apache.hadoop.fs.Path

import org.graphframes.{GraphFrame, Logging}
import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.storage.StorageLevel
import org.graphframes.WithAlgorithmChoice

/**
 * Connected Components algorithm.
 *
 * Computes the connected component membership of each vertex and returns a DataFrame of vertex
 * information with each vertex assigned a component ID.
 *
 * The resulting DataFrame contains all the vertex information and one additional column:
 *   - component (`LongType`): unique ID for this component
 */
class ConnectedComponents private[graphframes] (private val graph: GraphFrame)
    extends Arguments
    with Logging
    with WithAlgorithmChoice {

  import org.graphframes.lib.ConnectedComponents._

  private var broadcastThreshold: Int = 1000000
  setAlgorithm(ALGO_GRAPHFRAMES)

  /**
   * Sets broadcast threshold in propagating component assignments (default: 1000000). If a node
   * degree is greater than this threshold at some iteration, its component assignment will be
   * collected and then broadcasted back to propagate the assignment to its neighbors. Otherwise,
   * the assignment propagation is done by a normal Spark join. This parameter is only used when
   * the algorithm is set to "graphframes".
   */
  def setBroadcastThreshold(value: Int): this.type = {
    require(value >= 0, s"Broadcast threshold must be non-negative but got $value.")
    broadcastThreshold = value
    this
  }

  // python-friendly setter
  private[graphframes] def setBroadcastThreshold(value: java.lang.Integer): this.type = {
    setBroadcastThreshold(value.toInt)
  }

  /**
   * Gets broadcast threshold in propagating component assignment.
   * @see
   *   [[org.graphframes.lib.ConnectedComponents.setBroadcastThreshold]]
   */
  def getBroadcastThreshold: Int = broadcastThreshold

  private var checkpointInterval: Int = 2

  /**
   * Sets checkpoint interval in terms of number of iterations (default: 2). Checkpointing
   * regularly helps recover from failures, clean shuffle files, shorten the lineage of the
   * computation graph, and reduce the complexity of plan optimization. As of Spark 2.0, the
   * complexity of plan optimization would grow exponentially without checkpointing. Hence,
   * disabling or setting longer-than-default checkpoint intervals are not recommended. Checkpoint
   * data is saved under `org.apache.spark.SparkContext.getCheckpointDir` with prefix
   * "connected-components". If the checkpoint directory is not set, this throws a
   * `java.io.IOException`. Set a nonpositive value to disable checkpointing. This parameter is
   * only used when the algorithm is set to "graphframes". Its default value might change in the
   * future.
   * @see
   *   `org.apache.spark.SparkContext.setCheckpointDir` in Spark API doc
   */
  def setCheckpointInterval(value: Int): this.type = {
    if (value <= 0 || value > 2) {
      logWarn(
        s"Set checkpointInterval to $value. This would blow up the query plan and hang the " +
          "driver for large graphs.")
    }
    checkpointInterval = value
    this
  }

  // python-friendly setter
  private[graphframes] def setCheckpointInterval(value: java.lang.Integer): this.type = {
    setCheckpointInterval(value.toInt)
  }

  /**
   * Gets checkpoint interval.
   * @see
   *   [[org.graphframes.lib.ConnectedComponents.setCheckpointInterval]]
   */
  def getCheckpointInterval: Int = checkpointInterval

  private var intermediateStorageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK

  /**
   * Sets storage level for intermediate datasets that require multiple passes (default:
   * ``MEMORY_AND_DISK``).
   */
  def setIntermediateStorageLevel(value: StorageLevel): this.type = {
    intermediateStorageLevel = value
    this
  }

  /**
   * Gets storage level for intermediate datasets that require multiple passes.
   */
  def getIntermediateStorageLevel: StorageLevel = intermediateStorageLevel

  /**
   * Runs the algorithm.
   */
  def run(): DataFrame = {
    ConnectedComponents.run(
      graph,
      runInGraphX = algorithm == ALGO_GRAPHX,
      broadcastThreshold = broadcastThreshold,
      checkpointInterval = checkpointInterval,
      intermediateStorageLevel = intermediateStorageLevel)
  }
}

object ConnectedComponents extends Logging {

  import org.graphframes.GraphFrame._

  private val COMPONENT = "component"
  private val ORIG_ID = "orig_id"
  private val MIN_NBR = "min_nbr"
  private val CNT = "cnt"
  private val CHECKPOINT_NAME_PREFIX = "connected-components"

  /**
   * Returns the symmetric directed graph of the graph specified by input edges.
   * @param ee
   *   non-bidirectional edges
   */
  private def symmetrize(ee: DataFrame): DataFrame = {
    val EDGE = "_edge"
    ee.select(explode(
      array(struct(col(SRC), col(DST)), struct(col(DST).as(SRC), col(SRC).as(DST)))).as(EDGE))
      .select(col(s"$EDGE.$SRC").as(SRC), col(s"$EDGE.$DST").as(DST))
  }

  /**
   * Prepares the input graph for computing connected components by:
   *   - de-duplicating vertices and assigning unique long IDs to each,
   *   - changing edge directions to have increasing long IDs from src to dst,
   *   - de-duplicating edges and removing self-loops. In the returned GraphFrame, the vertex
   *     DataFrame has two columns:
   *   - column `id` stores a long ID assigned to the vertex,
   *   - column `attr` stores the original vertex attributes. The edge DataFrame has two columns:
   *   - column `src` stores the long ID of the source vertex,
   *   - column `dst` stores the long ID of the destination vertex, where we always have `src` <
   *     `dst`.
   */
  private def prepare(graph: GraphFrame): GraphFrame = {
    // TODO: This assignment job might fail if the graph is skewed.
    val vertices = graph.indexedVertices
      .select(col(LONG_ID).as(ID), col(ATTR))
    // TODO: confirm the contract for a graph and decide whether we need distinct here
    // .distinct()
    val edges = graph.indexedEdges
      .select(col(LONG_SRC).as(SRC), col(LONG_DST).as(DST))
    val orderedEdges = edges
      .filter(col(SRC) =!= col(DST))
      .select(minValue(col(SRC), col(DST)).as(SRC), maxValue(col(SRC), col(DST)).as(DST))
      .distinct()
    GraphFrame(vertices, orderedEdges)
  }

  /**
   * Returns the min vertex among each vertex and its neighbors in a DataFrame with three columns:
   *   - `src`, the ID of the vertex
   *   - `min_nbr`, the min vertex ID among itself and its neighbors
   *   - `cnt`, the total number of neighbors
   */
  private def minNbrs(ee: DataFrame): DataFrame = {
    symmetrize(ee)
      .groupBy(SRC)
      .agg(min(col(DST)).as(MIN_NBR), count("*").as(CNT))
      .withColumn(MIN_NBR, minValue(col(SRC), col(MIN_NBR)))
  }

  private def minValue(x: Column, y: Column): Column = {
    when(x < y, x).otherwise(y)
  }

  private def maxValue(x: Column, y: Column): Column = {
    when(x > y, x).otherwise(y)
  }

  /**
   * Performs a possibly skewed join between edges and current component assignments. The skew
   * join is done by broadcast join for frequent keys and normal join for the rest.
   */
  private def skewedJoin(
      edges: DataFrame,
      minNbrs: DataFrame,
      broadcastThreshold: Int,
      logPrefix: String): DataFrame = {
    import edges.sparkSession.implicits._
    val hubs = minNbrs
      .filter(col(CNT) > broadcastThreshold)
      .select(SRC)
      .as[Long]
      .collect()
      .toSet
    GraphFrame.skewedJoin(edges, minNbrs, SRC, hubs, logPrefix)
  }

  /**
   * Runs connected components with default parameters.
   */
  def run(graph: GraphFrame): DataFrame = {
    new ConnectedComponents(graph).run()
  }

  private def runGraphX(graph: GraphFrame): DataFrame = {
    val components =
      org.apache.spark.graphx.lib.ConnectedComponents.run(graph.cachedTopologyGraphX)
    GraphXConversions.fromGraphX(graph, components, vertexNames = Seq(COMPONENT)).vertices
  }

  private def run(
      graph: GraphFrame,
      runInGraphX: Boolean,
      broadcastThreshold: Int,
      checkpointInterval: Int,
      intermediateStorageLevel: StorageLevel): DataFrame = {
    if (runInGraphX) {
      return runGraphX(graph)
    }

    val spark = graph.spark
    val sc = spark.sparkContext
    // Store original AQE setting
    val originalAQE = spark.conf.get("spark.sql.adaptive.enabled")

    try {
      spark.conf.set("spark.sql.adaptive.enabled", "false")

      val runId = UUID.randomUUID().toString.takeRight(8)
      val logPrefix = s"[CC $runId]"
      logInfo(s"$logPrefix Start connected components with run ID $runId.")

      val shouldCheckpoint = checkpointInterval > 0
      val checkpointDir: Option[String] = if (shouldCheckpoint) {
        val dir = sc.getCheckpointDir
          .map { d =>
            new Path(d, s"$CHECKPOINT_NAME_PREFIX-$runId").toString
          }
          .getOrElse {
            // Spark-Connect workaround
            spark.conf.getOption("spark.checkpoint.dir") match {
              case Some(d) => new Path(d, s"$CHECKPOINT_NAME_PREFIX-$runId").toString
              case None =>
                throw new IOException(
                  "Checkpoint directory is not set. Please set it first using sc.setCheckpointDir()" +
                    "or by specifying the conf 'spark.checkpoint.dir'.")
            }
          }
        logInfo(s"$logPrefix Using $dir for checkpointing with interval $checkpointInterval.")
        Some(dir)
      } else {
        logInfo(
          s"$logPrefix Checkpointing is disabled because checkpointInterval=$checkpointInterval.")
        None
      }

      logInfo(s"$logPrefix Preparing the graph for connected component computation ...")
      val g = prepare(graph)
      val vv = g.vertices
      var ee = g.edges.persist(intermediateStorageLevel) // src < dst
      logInfo(s"$logPrefix Found ${ee.count()} edges after preparation.")

      var converged = false
      var iteration = 1

      def _calcMinNbrSum(minNbrsDF: DataFrame): BigDecimal = {
        // Taking the sum in DecimalType to preserve precision.
        // We use 20 digits for long values and Spark SQL will add 10 digits for the sum.
        // It should be able to handle 200 billion edges without overflow.
        val (minNbrSum, cnt) = minNbrsDF
          .select(sum(col(MIN_NBR).cast(DecimalType(20, 0))), count("*"))
          .rdd
          .map { r =>
            (r.getAs[BigDecimal](0), r.getLong(1))
          }
          .first()
        if (cnt != 0L && minNbrSum == null) {
          throw new ArithmeticException(s"""
               |The total sum of edge src IDs is used to determine convergence during iterations.
               |However, the total sum at iteration $iteration exceeded 30 digits (1e30),
               |which should happen only if the graph contains more than 200 billion edges.
               |If not, please file a bug report at https://github.com/graphframes/graphframes/issues.
              """.stripMargin)
        }
        minNbrSum
      }
      // compute min neighbors (including self-min)
      var minNbrs1: DataFrame = minNbrs(ee) // src >= min_nbr
        .persist(intermediateStorageLevel)

      var prevSum: BigDecimal = _calcMinNbrSum(minNbrs1)

      var lastRoundPersistedDFs = Seq[DataFrame](ee, minNbrs1)
      while (!converged) {
        var currRoundPersistedDFs = Seq[DataFrame]()
        // large-star step
        // connect all strictly larger neighbors to the min neighbor (including self)
        ee = skewedJoin(ee, minNbrs1, broadcastThreshold, logPrefix)
          .select(col(DST).as(SRC), col(MIN_NBR).as(DST)) // src > dst
          .distinct()
          .persist(intermediateStorageLevel)
        currRoundPersistedDFs = currRoundPersistedDFs :+ ee

        // small-star step
        // compute min neighbors (excluding self-min)
        val minNbrs2 = ee
          .groupBy(col(SRC))
          .agg(min(col(DST)).as(MIN_NBR), count("*").as(CNT)) // src > min_nbr
          .persist(intermediateStorageLevel)
        currRoundPersistedDFs = currRoundPersistedDFs :+ minNbrs2

        // connect all smaller neighbors to the min neighbor
        ee = skewedJoin(ee, minNbrs2, broadcastThreshold, logPrefix)
          .select(col(MIN_NBR).as(SRC), col(DST)) // src <= dst
          .filter(col(SRC) =!= col(DST)) // src < dst
        // connect self to the min neighbor
        ee = ee
          .union(minNbrs2.select(col(MIN_NBR).as(SRC), col(SRC).as(DST))) // src < dst
          .distinct()

        // checkpointing
        if (shouldCheckpoint && (iteration % checkpointInterval == 0)) {
          // TODO: remove this after DataFrame.checkpoint is implemented
          val out = s"${checkpointDir.get}/$iteration"
          ee.write.parquet(out)
          // may hit S3 eventually consistent issue
          ee = spark.read.parquet(out)

          // remove previous checkpoint
          if (iteration > checkpointInterval) {
            val path = new Path(s"${checkpointDir.get}/${iteration - checkpointInterval}")
            path.getFileSystem(sc.hadoopConfiguration).delete(path, true)
          }

          System.gc() // hint Spark to clean shuffle directories
        }

        ee.persist(intermediateStorageLevel)
        currRoundPersistedDFs = currRoundPersistedDFs :+ ee

        minNbrs1 = minNbrs(ee) // src >= min_nbr
          .persist(intermediateStorageLevel)
        currRoundPersistedDFs = currRoundPersistedDFs :+ minNbrs1

        // test convergence
        val currSum = _calcMinNbrSum(minNbrs1)
        logInfo(s"$logPrefix Sum of assigned components in iteration $iteration: $currSum.")
        if (currSum == prevSum) {
          // This also covers the case when cnt = 0 and currSum is null, which means no edges.
          converged = true
        } else {
          prevSum = currSum
        }

        // clean up persisted DFs
        for (persisted_df <- lastRoundPersistedDFs) {
          persisted_df.unpersist()
        }
        lastRoundPersistedDFs = currRoundPersistedDFs
        iteration += 1
      }

      logInfo(s"$logPrefix Connected components converged in ${iteration - 1} iterations.")

      logInfo(s"$logPrefix Join and return component assignments with original vertex IDs.")
      val indexedLabel = vv
        .join(ee, vv(ID) === ee(DST), "left_outer")
        .select(
          vv(ATTR),
          when(ee(SRC).isNull, vv(ID)).otherwise(ee(SRC)).as(COMPONENT),
          col(ATTR + "." + ID).as(ID))
      val output = if (graph.hasIntegralIdType) {
        indexedLabel
          .select(col(s"$ATTR.*"), col(COMPONENT))
          .persist(intermediateStorageLevel)
      } else {
        indexedLabel
          .join(
            indexedLabel
              .groupBy(col(COMPONENT))
              .agg(min(col(ID)).as(ORIG_ID))
              .select(col(COMPONENT), col(ORIG_ID)),
            COMPONENT)
          .select(col(s"$ATTR.*"), col(ORIG_ID).as(COMPONENT))
          .persist(intermediateStorageLevel)
      }

      // An action must be performed on the DataFrame for the cache to load
      output.count()

      // clean up persisted DFs
      for (persisted_df <- lastRoundPersistedDFs) {
        persisted_df.unpersist()
      }

      logWarn("The DataFrame returned by ConnectedComponents is persisted and loaded.")

      output
    } finally {
      // Restore original AQE setting
      spark.conf.set("spark.sql.adaptive.enabled", originalAQE)
    }
  }
}
