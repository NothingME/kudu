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

package org.apache.kudu.spark.kudu

import java.security.{AccessController, PrivilegedAction}
import java.util
import javax.security.auth.Subject
import javax.security.auth.login.{AppConfigurationEntry, Configuration, LoginContext}

import scala.collection.JavaConverters._
import scala.collection.mutable
import org.apache.hadoop.util.ShutdownHookManager
import org.apache.kudu.ColumnTypeAttributes.ColumnTypeAttributesBuilder
import org.apache.spark.{AccumulatorParam, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{DataType, DataTypes, DecimalType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.yetus.audience.{InterfaceAudience, InterfaceStability}
import org.slf4j.{Logger, LoggerFactory}
import org.apache.kudu.client.SessionConfiguration.FlushMode
import org.apache.kudu.client._
import org.apache.kudu.{ColumnSchema, Schema, Type}

/**
  * KuduContext is a serializable container for Kudu client connections.
  *
  * If a Kudu client connection is needed as part of a Spark application, a
  * [[KuduContext]] should be created in the driver, and shared with executors
  * as a serializable field.
  */
@InterfaceStability.Unstable
class KuduContext(val kuduMaster: String,
                  sc: SparkContext,
                  val socketReadTimeoutMs: Option[Long]) extends Serializable {

  def this(kuduMaster: String, sc: SparkContext) = this(kuduMaster, sc, None)

  /**
    * TimestampAccumulator accumulates the maximum value of client's
    * propagated timestamp of all executors and can only read by the
    * driver.
    */
  private object TimestampAccumulator extends AccumulatorParam[Long] {
    override def zero(initialValue: Long): Long = 0L

    override def addAccumulator(v1: Long, v2: Long): Long = {
      v1.max(v2)
    }

    override def addInPlace(v1: Long, v2: Long): Long = {
      val timestamp = v1.max(v2)

      // Since for every write/scan operation, each executor holds its own copy of
      // client. We need to update the propagated timestamp on the driver based on
      // the latest propagated timestamp from all executors through TimestampAccumulator.
      syncClient.updateLastPropagatedTimestamp(timestamp)
      timestamp
    }
  }

  val timestampAccumulator = sc.accumulator(0L)(TimestampAccumulator)

  @Deprecated()
  def this(kuduMaster: String) {
    this(kuduMaster, new SparkContext())
  }

  @transient lazy val syncClient: KuduClient = asyncClient.syncClient()

  @transient lazy val asyncClient: AsyncKuduClient = {
    val c = KuduClientCache.getAsyncClient(kuduMaster, socketReadTimeoutMs)
    if (authnCredentials != null) {
      c.importAuthenticationCredentials(authnCredentials)
    }
    c
  }

  // Visible for testing.
  private[kudu] val authnCredentials : Array[Byte] = {
    Subject.doAs(KuduContext.getSubject(sc), new PrivilegedAction[Array[Byte]] {
      override def run(): Array[Byte] = syncClient.exportAuthenticationCredentials()
    })
  }

  /**
    * Create an RDD from a Kudu table.
    *
    * @param tableName          table to read from
    * @param columnProjection   list of columns to read. Not specifying this at all
    *                           (i.e. setting to null) or setting to the special
    *                           string '*' means to project all columns
    * @return a new RDD that maps over the given table for the selected columns
    */
  def kuduRDD(sc: SparkContext,
              tableName: String,
              columnProjection: Seq[String] = Nil): RDD[Row] = {
    // TODO: provide an elegant way to pass various options (faultTolerantScan,
    // TODO: localityScan, etc) to KuduRDD
    new KuduRDD(this, 1024*1024*20, columnProjection.toArray, Array(),
                syncClient.openTable(tableName), false, ReplicaSelection.LEADER_ONLY,
                None, None, sc)
  }

  /**
    * Check if kudu table already exists
    *
    * @param tableName name of table to check
    * @return true if table exists, false if table does not exist
    */
  def tableExists(tableName: String): Boolean = syncClient.tableExists(tableName)

  /**
    * Delete kudu table
    *
    * @param tableName name of table to delete
    * @return DeleteTableResponse
    */
  def deleteTable(tableName: String): DeleteTableResponse = syncClient.deleteTable(tableName)

  /**
    * Creates a kudu table for the given schema. Partitioning can be specified through options.
    *
    * @param tableName table to create
    * @param schema struct schema of table
    * @param keys primary keys of the table
    * @param options replication and partitioning options for the table
    * @return the KuduTable that was created
    */
  def createTable(tableName: String,
                  schema: StructType,
                  keys: Seq[String],
                  options: CreateTableOptions): KuduTable = {
    val kuduSchema = createSchema(schema, keys)
    createTable(tableName, kuduSchema, options)
  }

  /**
    * Creates a kudu table for the given schema. Partitioning can be specified through options.
    *
    * @param tableName table to create
    * @param schema schema of table
    * @param options replication and partitioning options for the table
    * @return the KuduTable that was created
    */
  def createTable(tableName: String,
                  schema: Schema,
                  options: CreateTableOptions): KuduTable = {
    syncClient.createTable(tableName, schema, options)
  }

  /**
    * Creates a kudu schema for the given struct schema.
    *
    * @param schema struct schema of table
    * @param keys primary keys of the table
    * @return the Kudu schema
    */
  def createSchema(schema: StructType, keys: Seq[String]): Schema = {
    val kuduCols = new util.ArrayList[ColumnSchema]()
    // add the key columns first, in the order specified
    for (key <- keys) {
      val field = schema.fields(schema.fieldIndex(key))
      val col = createColumn(field, isKey = true)
      kuduCols.add(col)
    }
    // now add the non-key columns
    for (field <- schema.fields.filter(field => !keys.contains(field.name))) {
      val col = createColumn(field, isKey = false)
      kuduCols.add(col)
    }
    new Schema(kuduCols)
  }

  private def createColumn(field: StructField, isKey: Boolean): ColumnSchema = {
    val kt = kuduType(field.dataType)
    val col = new ColumnSchema.ColumnSchemaBuilder(field.name, kt).key(isKey).nullable(field.nullable)
    // Add ColumnTypeAttributesBuilder to DECIMAL columns
    if (kt == Type.DECIMAL) {
      val dt = field.dataType.asInstanceOf[DecimalType]
      col.typeAttributes(
        new ColumnTypeAttributesBuilder().precision(dt.precision).scale(dt.scale).build()
      )
    }
    col.build()
  }

  /** Map Spark SQL type to Kudu type */
  def kuduType(dt: DataType) : Type = dt match {
    case DataTypes.BinaryType => Type.BINARY
    case DataTypes.BooleanType => Type.BOOL
    case DataTypes.StringType => Type.STRING
    case DataTypes.TimestampType => Type.UNIXTIME_MICROS
    case DataTypes.ByteType => Type.INT8
    case DataTypes.ShortType => Type.INT16
    case DataTypes.IntegerType => Type.INT32
    case DataTypes.LongType => Type.INT64
    case DataTypes.FloatType => Type.FLOAT
    case DataTypes.DoubleType => Type.DOUBLE
    case DecimalType() => Type.DECIMAL
    case _ => throw new IllegalArgumentException(s"No support for Spark SQL type $dt")
  }

  /**
    * Inserts the rows of a [[DataFrame]] into a Kudu table.
    *
    * @param data the data to insert
    * @param tableName the Kudu table to insert into
    */
  def insertRows(data: DataFrame, tableName: String): Unit = {
    writeRows(data, tableName, Insert)
  }

  /**
    * Inserts the rows of a [[DataFrame]] into a Kudu table, ignoring any new
    * rows that have a primary key conflict with existing rows.
    *
    * @param data the data to insert into Kudu
    * @param tableName the Kudu table to insert into
    */
  def insertIgnoreRows(data: DataFrame, tableName: String): Unit = {
    writeRows(data, tableName, InsertIgnore)
  }

  /**
    * Upserts the rows of a [[DataFrame]] into a Kudu table.
    *
    * @param data the data to upsert into Kudu
    * @param tableName the Kudu table to upsert into
    */
  def upsertRows(data: DataFrame, tableName: String): Unit = {
    writeRows(data, tableName, Upsert)
  }

  /**
    * Updates a Kudu table with the rows of a [[DataFrame]].
    *
    * @param data the data to update into Kudu
    * @param tableName the Kudu table to update
    */
  def updateRows(data: DataFrame, tableName: String): Unit = {
    writeRows(data, tableName, Update)
  }

  /**
    * Deletes the rows of a [[DataFrame]] from a Kudu table.
    *
    * @param data the data to delete from Kudu
    *             note that only the key columns should be specified for deletes
    * @param tableName The Kudu tabe to delete from
    */
  def deleteRows(data: DataFrame, tableName: String): Unit = {
    writeRows(data, tableName, Delete)
  }

  private[kudu] def writeRows(data: DataFrame, tableName: String, operation: OperationType) {
    val schema = data.schema
    // Get the client's last propagated timestamp on the driver.
    val lastPropagatedTimestamp = syncClient.getLastPropagatedTimestamp
    data.foreachPartition(iterator => {
      val pendingErrors = writePartitionRows(iterator, schema, tableName, operation,
                                             lastPropagatedTimestamp)
      val errorCount = pendingErrors.getRowErrors.length
      if (errorCount > 0) {
        val errors = pendingErrors.getRowErrors.take(5).map(_.getErrorStatus).mkString
        throw new RuntimeException(
          s"failed to write $errorCount rows from DataFrame to Kudu; sample errors: $errors")
      }
    })
  }

  private def writePartitionRows(rows: Iterator[Row],
                                 schema: StructType,
                                 tableName: String,
                                 operationType: OperationType,
                                 lastPropagatedTimestamp: Long): RowErrorsAndOverflowStatus = {
    // Since each executor has its own KuduClient, update executor's propagated timestamp
    // based on the last one on the driver.
    syncClient.updateLastPropagatedTimestamp(lastPropagatedTimestamp)
    val table: KuduTable = syncClient.openTable(tableName)
    val indices: Array[(Int, Int)] = schema.fields.zipWithIndex.map({ case (field, sparkIdx) =>
      sparkIdx -> table.getSchema.getColumnIndex(field.name)
    })
    val session: KuduSession = syncClient.newSession
    session.setFlushMode(FlushMode.AUTO_FLUSH_BACKGROUND)
    session.setIgnoreAllDuplicateRows(operationType.ignoreDuplicateRowErrors)
    try {
      for (row <- rows) {
        val operation = operationType.operation(table)
        for ((sparkIdx, kuduIdx) <- indices) {
          if (row.isNullAt(sparkIdx)) {
            operation.getRow.setNull(kuduIdx)
          } else {
            schema.fields(sparkIdx).dataType match {
              case DataTypes.StringType => operation.getRow.addString(kuduIdx, row.getString(sparkIdx))
              case DataTypes.BinaryType => operation.getRow.addBinary(kuduIdx, row.getAs[Array[Byte]](sparkIdx))
              case DataTypes.BooleanType => operation.getRow.addBoolean(kuduIdx, row.getBoolean(sparkIdx))
              case DataTypes.ByteType => operation.getRow.addByte(kuduIdx, row.getByte(sparkIdx))
              case DataTypes.ShortType => operation.getRow.addShort(kuduIdx, row.getShort(sparkIdx))
              case DataTypes.IntegerType => operation.getRow.addInt(kuduIdx, row.getInt(sparkIdx))
              case DataTypes.LongType => operation.getRow.addLong(kuduIdx, row.getLong(sparkIdx))
              case DataTypes.FloatType => operation.getRow.addFloat(kuduIdx, row.getFloat(sparkIdx))
              case DataTypes.DoubleType => operation.getRow.addDouble(kuduIdx, row.getDouble(sparkIdx))
              case DataTypes.TimestampType => operation.getRow.addLong(kuduIdx, KuduRelation.timestampToMicros(row.getTimestamp(sparkIdx)))
              case DecimalType() => operation.getRow.addDecimal(kuduIdx, row.getDecimal(sparkIdx))
              case t => throw new IllegalArgumentException(s"No support for Spark SQL type $t")
            }
          }
        }
        session.apply(operation)
      }
    } finally {
      session.close()
      // Update timestampAccumulator with the client's last propagated
      // timestamp on each executor.
      timestampAccumulator.add(syncClient.getLastPropagatedTimestamp)
    }
    session.getPendingErrors
  }
}

private object KuduContext {
  val Log: Logger = LoggerFactory.getLogger(classOf[KuduContext])

  /**
    * Returns a new Kerberos-authenticated [[Subject]] if the Spark context contains
    * principal and keytab options, otherwise returns the currently active subject.
    *
    * The keytab and principal options should be set when deploying a Spark
    * application in cluster mode with Yarn against a secure Kudu cluster. Spark
    * internally will grab HDFS and HBase delegation tokens (see
    * [[org.apache.spark.deploy.SparkSubmit]]), so we do something similar.
    *
    * This method can only be called on the driver, where the SparkContext is
    * available.
    *
    * @return A Kerberos-authenticated subject if the Spark context contains
    *         principal and keytab options, otherwise returns the currently
    *         active subject
    */
  private def getSubject(sc: SparkContext): Subject = {
    val subject = Subject.getSubject(AccessController.getContext)

    val principal = sc.getConf.getOption("spark.yarn.principal").getOrElse(return subject)
    val keytab = sc.getConf.getOption("spark.yarn.keytab").getOrElse(return subject)

    Log.info(s"Logging in as principal $principal with keytab $keytab")

    val conf = new Configuration {
      override def getAppConfigurationEntry(name: String): Array[AppConfigurationEntry] = {
        val options = Map(
          "principal" -> principal,
          "keyTab" -> keytab,
          "useKeyTab" -> "true",
          "useTicketCache" -> "false",
          "doNotPrompt" -> "true",
          "refreshKrb5Config" -> "true"
        )

        Array(new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                                        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                                        options.asJava))
      }
    }

    val loginContext = new LoginContext("kudu-spark", new Subject(), null, conf)
    loginContext.login()
    loginContext.getSubject
  }
}

private object KuduClientCache {
  private case class CacheKey(kuduMaster: String, socketReadTimeoutMs: Option[Long])

  /**
    * Set to
    * [[org.apache.spark.util.ShutdownHookManager.DEFAULT_SHUTDOWN_PRIORITY]].
    * The client instances are closed through the JVM shutdown hook
    * mechanism in order to make sure that any unflushed writes are cleaned up
    * properly. Spark has no shutdown notifications.
    */
  private val ShutdownHookPriority = 100

  private val clientCache = new mutable.HashMap[CacheKey, AsyncKuduClient]()

  // Visible for testing.
  private[kudu] def clearCacheForTests() = clientCache.clear()

  def getAsyncClient(kuduMaster: String, socketReadTimeoutMs: Option[Long]): AsyncKuduClient = {
    val cacheKey = CacheKey(kuduMaster, socketReadTimeoutMs)
    clientCache.synchronized {
      if (!clientCache.contains(cacheKey)) {
        val builder = new AsyncKuduClient.AsyncKuduClientBuilder(kuduMaster)
        socketReadTimeoutMs match {
          case Some(timeout) => builder.defaultSocketReadTimeoutMs(timeout)
          case None =>
        }

        val asyncClient = builder.build()
        ShutdownHookManager.get().addShutdownHook(
          new Runnable {
            override def run() = asyncClient.close()
          }, ShutdownHookPriority)
        clientCache.put(cacheKey, asyncClient)
      }
      return clientCache(cacheKey)
    }
  }
}
