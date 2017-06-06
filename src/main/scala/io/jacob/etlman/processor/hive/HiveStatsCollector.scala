package io.jacob.etlman.processor.hive

import java.sql.Connection
import java.text.SimpleDateFormat
import java.util.Date

import io.jacob.etlman.utils.HiveUtils
import org.apache.spark.SparkContext
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._

import scala.collection.mutable

/**
  * Created by xiaoy on 2017/5/15.
  */
class HiveStatsCollector(val sparkContext: SparkContext,
                         val sysName: String,
                         val schemaName: String,
                         val tableName: String,
                         val loadDate: String,
                         val metaDBConnection: Connection
                        ) {

  def collect(): Unit = {
    val timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())

    val defaultParallelism = System.getProperty("spark.default.parallelism").toInt

    val queryStmt = "select * from %s.%s %s"
      .format(schemaName, tableName, if (loadDate == null) "" else " where data_dt_iso = '" + loadDate + "'")

    val tableDF = try {
      HiveUtils.getDataFromHive(queryStmt, sparkContext, defaultParallelism).cache()
    } catch {
      case e: Exception =>
        println("Exception captured during executing [%s]".format(queryStmt))
        println("Stats collection for %s is aborted.".format(tableName))
        e.printStackTrace()
        return
    }

    var rowCount: Long = 0

    tableDF.schema.fields.filter(!_.name.equalsIgnoreCase("data_dt_iso")).foreach(c => {
      println("Collecting stats for %s ...".format(c.name))

      val stats = tableDF.groupBy(c.name).count().cache()
      println(stats.schema)

      val numNullDF = stats.where(stats.col(c.name).isNull)
      val numNull = {
        if (numNullDF.count() == 1)
          numNullDF.head().getLong(1)
        else
          0
      }

      println("Number of null is %d".format(numNull))

      val numValue = stats.count()
      println("The total number of values is : %d".format(numValue))

      val aggDF = stats.agg(sum("count"), min(c.name), max(c.name))
      println(aggDF.schema)

      val aggRow = aggDF.head()

      val colRowCount = {
        if (!aggRow.isNullAt(0)) aggRow.getLong(0) else 0
      }

      val minVal = {
        if (!aggRow.isNullAt(1)) aggRow.get(1) else null
      }

      val maxVal = {
        if (!aggRow.isNullAt(2)) aggRow.get(2) else null
      }

      println("The row number is %d, min value is %s, the max value is %s".format(
        colRowCount,
        if (minVal != null) minVal.toString else "null",
        if (maxVal != null) maxVal.toString else "null"))

      rowCount = colRowCount

      println("The top 200 number of values are:")

      saveColumnStats(c.name, timeStamp,
        if (minVal != null) normalizeValue(minVal.toString, null) else "null",
        if (maxVal != null) normalizeValue(maxVal.toString, null) else "null",
        numValue, numNull, stats)
    })

    saveTableStats(rowCount, timeStamp)

  }

  def saveTableStats(rowCount: Long, timeStamp: String): Unit = {

    val sqlInsertTableStats = "insert into table_stats values ('%s', '%s', '%s', '%s', %d)".format(
      sysName, schemaName, tableName, timeStamp, rowCount
    )

    val ps = metaDBConnection.prepareStatement(sqlInsertTableStats)
    ps.executeUpdate()
  }

  def saveColumnStats(columnName: String,
                      timeStamp: String,
                      minVal: String,
                      maxVal: String,
                      numValue: Long,
                      numNull: Long,
                      stats: DataFrame): Unit = {

    val histogramId = "%s.%s.%s".format(tableName, columnName, timeStamp).replaceAll(" ", "T")

    val sqlInsertColumnStats = "insert into column_stats values ('%s', '%s', '%s', '%s', '%s', '%s', '%s', %d, %d, '%s')".format(
      sysName, schemaName, tableName, columnName, timeStamp, maxVal, minVal, numValue, numNull, histogramId
    )

    var ps = metaDBConnection.prepareStatement(sqlInsertColumnStats)
    ps.executeUpdate()

    val sqlInsertHistogram = "insert into col_value_histogram values ('%s', ?, ?)".format(histogramId)
    ps = metaDBConnection.prepareStatement(sqlInsertHistogram)

    val vals = new mutable.HashMap[String, Int]
    stats.sort(desc("count")).take(200).foreach(x => {
      println(x)
      ps.setString(1, if (x.get(0) == null) "null" else normalizeValue(x.get(0).toString, vals))
      ps.setLong(2, x.getLong(1))
      ps.executeUpdate()
    })
  }

  private def normalizeValue(colVal: String, vals: mutable.Map[String, Int]): String = {
    // Replace the leading and trailing spaces with 'Space(X)'
    val (preSpaceCount, postSpaceCount, _) = colVal.foldLeft((0, 0, false))((t, c) =>
      if (!t._3) {
        if (c == ' ') (t._1 + 1, 0, false)
        else (t._1, 0, true)
      } else {
        if (c == ' ') (t._1, t._2 + 1, true)
        else (t._1, 0, true)
      }
    )

    val trimedVal = (if (preSpaceCount != 0) "Space(" + preSpaceCount + ")" else "") +
      colVal.trim +
      (if (postSpaceCount != 0) "Space(" + postSpaceCount + ")" else "")

    // The length of the value to be inserted into col_value_histogram.model_value
    // shouldn't exceed the maximum column length of 128.
    val normVal = if (trimedVal.length > 128)
      trimedVal.substring(0, 120) + "..."
    else
      trimedVal

    if (vals != null){
      getDistinctVal(vals, normVal, 0)
    }else
      normVal
  }

  def getDistinctVal(values: mutable.Map[String, Int], value : String, idx : Int):String = {
    if (values.contains(value))
      getDistinctVal(values,
        value.substring(0, 120) + "...(" + idx + ")",
        idx + 1)
    else{
      values(value) = 1
      value
    }
  }
}
