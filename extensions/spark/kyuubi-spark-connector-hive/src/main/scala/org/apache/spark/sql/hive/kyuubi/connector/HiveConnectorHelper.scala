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

package org.apache.spark.sql.hive.kyuubi.connector

import scala.collection.mutable

import org.apache.spark.SparkContext
import org.apache.spark.rdd.InputFileBlockHolder
import org.apache.spark.sql.catalyst.catalog.{BucketSpec, ExternalCatalogEvent}
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, Literal}
import org.apache.spark.sql.connector.catalog.CatalogV2Util
import org.apache.spark.sql.connector.expressions.{BucketTransform, FieldReference, IdentityTransform, LogicalExpressions, Transform}
import org.apache.spark.sql.connector.expressions.LogicalExpressions.{bucket, reference}
import org.apache.spark.sql.hive.{HadoopTableReader, HiveShim, HiveTableUtil}
import org.apache.spark.sql.hive.client.HiveClientImpl
import org.apache.spark.sql.types.{DataType, DoubleType, FloatType, StructType}

object HiveConnectorHelper {
  type HiveSessionCatalog = org.apache.spark.sql.hive.HiveSessionCatalog
  type HiveMetastoreCatalog = org.apache.spark.sql.hive.HiveMetastoreCatalog
  type HiveExternalCatalog = org.apache.spark.sql.hive.HiveExternalCatalog
  type NextIterator[U] = org.apache.spark.util.NextIterator[U]
  type FileSinkDesc = org.apache.spark.sql.hive.HiveShim.ShimFileSinkDesc
  type HiveVersion = org.apache.spark.sql.hive.client.HiveVersion
  type InsertIntoHiveTable = org.apache.spark.sql.hive.execution.InsertIntoHiveTable

  val hive = org.apache.spark.sql.hive.client.hive
  val logicalExpressions: LogicalExpressions.type = LogicalExpressions
  val hiveClientImpl: HiveClientImpl.type = HiveClientImpl
  val catalogV2Util: CatalogV2Util.type = CatalogV2Util
  val hiveTableUtil: HiveTableUtil.type = HiveTableUtil
  val hiveShim: HiveShim.type = HiveShim
  val inputFileBlockHolder: InputFileBlockHolder.type = InputFileBlockHolder
  val hadoopTableReader: HadoopTableReader.type = HadoopTableReader

  def postExternalCatalogEvent(sc: SparkContext, event: ExternalCatalogEvent): Unit = {
    sc.listenerBus.post(event)
  }

  implicit class TransformHelper(transforms: Seq[Transform]) {
    def convertTransforms: (Seq[String], Option[BucketSpec]) = {
      val identityCols = new mutable.ArrayBuffer[String]
      var bucketSpec = Option.empty[BucketSpec]

      transforms.map {
        case IdentityTransform(FieldReference(Seq(col))) =>
          identityCols += col

        case BucketTransform(numBuckets, col, sortCol) =>
          if (bucketSpec.nonEmpty) {
            throw new UnsupportedOperationException("Multiple bucket transforms are not supported.")
          }
          if (sortCol.isEmpty) {
            bucketSpec = Some(BucketSpec(numBuckets, col.map(_.fieldNames.mkString(".")), Nil))
          } else {
            bucketSpec = Some(BucketSpec(
              numBuckets,
              col.map(_.fieldNames.mkString(".")),
              sortCol.map(_.fieldNames.mkString("."))))
          }

        case transform =>
          throw new UnsupportedOperationException(
            s"Unsupported partition transform: $transform")
      }

      (identityCols.toSeq, bucketSpec)
    }
  }

  implicit class BucketSpecHelper(spec: BucketSpec) {
    def asTransform: Transform = {
      val references = spec.bucketColumnNames.map(col => reference(Seq(col)))
      if (spec.sortColumnNames.nonEmpty) {
        val sortedCol = spec.sortColumnNames.map(col => reference(Seq(col)))
        bucket(spec.numBuckets, references.toArray, sortedCol.toArray)
      } else {
        bucket(spec.numBuckets, references.toArray)
      }
    }
  }

  implicit class StructTypeHelper(structType: StructType) {
    def toAttributes: Seq[AttributeReference] = structType.toAttributes
  }

  def toSQLValue(v: Any, t: DataType): String = Literal.create(v, t) match {
    case Literal(null, _) => "NULL"
    case Literal(v: Float, FloatType) =>
      if (v.isNaN) "NaN"
      else if (v.isPosInfinity) "Infinity"
      else if (v.isNegInfinity) "-Infinity"
      else v.toString
    case l @ Literal(v: Double, DoubleType) =>
      if (v.isNaN) "NaN"
      else if (v.isPosInfinity) "Infinity"
      else if (v.isNegInfinity) "-Infinity"
      else l.sql
    case l => l.sql
  }

}
