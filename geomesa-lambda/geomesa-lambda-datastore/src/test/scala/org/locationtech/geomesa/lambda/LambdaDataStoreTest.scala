/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.lambda

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.geotools.api.data.{DataStoreFinder, Query, Transaction}
import org.geotools.api.feature.simple.SimpleFeatureType
import org.geotools.api.filter.Filter
import org.geotools.data.DataUtilities
import org.geotools.util.factory.Hints
import org.junit.runner.RunWith
import org.locationtech.geomesa.arrow.io.SimpleFeatureArrowFileReader
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.index.conf.QueryHints
import org.locationtech.geomesa.index.iterators.StatsScan
import org.locationtech.geomesa.lambda.data.LambdaDataStore
import org.locationtech.geomesa.utils.bin.BinaryOutputEncoder
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.geotools.{FeatureUtils, SimpleFeatureTypes}
import org.locationtech.geomesa.utils.io.WithClose
import org.locationtech.geomesa.utils.stats.{EnumerationStat, Stat}
import org.specs2.matcher.MatchResult
import org.specs2.runner.JUnitRunner

import java.io.ByteArrayInputStream
import java.util.Date

@RunWith(classOf[JUnitRunner])
class LambdaDataStoreTest extends LambdaContainerTest {

  import scala.collection.JavaConverters._
  import scala.concurrent.duration._

  sequential

  implicit val allocator: BufferAllocator = new RootAllocator(Long.MaxValue)

  val sft = SimpleFeatureTypes.createImmutableType("lambda", "name:String,dtg:Date,*geom:Point:srid=4326")
  val features = Seq(
    ScalaSimpleFeature.create(sft, "0", "n0", "2017-06-15T00:00:00.000Z", "POINT (45 50)"),
    ScalaSimpleFeature.create(sft, "1", "n1", "2017-06-15T00:00:01.000Z", "POINT (46 51)")
  )

  def testTransforms(ds: LambdaDataStore, transform: SimpleFeatureType): MatchResult[Any] = {
    val query = new Query(sft.getTypeName, Filter.INCLUDE, transform.getAttributeDescriptors.asScala.map(_.getLocalName).toSeq: _*)
    // note: need to copy the features as the same object is re-used in the iterator
    val iter = SelfClosingIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT))
    val result = iter.map(DataUtilities.encodeFeature).toSeq
    val expected = features.map(DataUtilities.reType(transform, _)).map(DataUtilities.encodeFeature)
    result must containTheSameElementsAs(expected)
  }

  def testBin(ds: LambdaDataStore): MatchResult[Any] = {
    val query = new Query(sft.getTypeName, Filter.INCLUDE)
    query.getHints.put(QueryHints.BIN_TRACK, "name")
    // note: need to copy the features as the same object is re-used in the iterator
    val iter = SelfClosingIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT))
    val bytes = iter.map(_.getAttribute(0).asInstanceOf[Array[Byte]]).reduceLeftOption(_ ++ _).getOrElse(Array.empty[Byte])
    bytes must haveLength(32)
    val bins = bytes.grouped(16).map(BinaryOutputEncoder.decode).toSeq
    bins.map(_.trackId) must containAllOf(Seq("n0", "n1").map(_.hashCode))
    bins.map(_.dtg) must containAllOf(features.map(_.getAttribute("dtg").asInstanceOf[Date].getTime))
    bins.map(_.lat) must containAllOf(Seq(50f, 51f))
    bins.map(_.lon) must containAllOf(Seq(45f, 46f))
  }

  def testArrow(ds: LambdaDataStore): MatchResult[Any] = {
    val query = new Query(sft.getTypeName, Filter.INCLUDE)
    query.getHints.put(QueryHints.ARROW_ENCODE, java.lang.Boolean.TRUE)
    query.getHints.put(QueryHints.ARROW_SORT_FIELD, "dtg")
    query.getHints.put(QueryHints.ARROW_DICTIONARY_FIELDS, "name")
    // note: need to copy the features as the same object is re-used in the iterator
    val iter = SelfClosingIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT))
    val bytes = iter.map(_.getAttribute(0).asInstanceOf[Array[Byte]]).reduceLeftOption(_ ++ _).getOrElse(Array.empty[Byte])
    WithClose(SimpleFeatureArrowFileReader.streaming(() => new ByteArrayInputStream(bytes))) { reader =>
      SelfClosingIterator(reader.features()).map(ScalaSimpleFeature.copy).toSeq must
          containTheSameElementsAs(features)
    }
  }

  def testStats(ds: LambdaDataStore): MatchResult[Any] = {
    val query = new Query(sft.getTypeName, Filter.INCLUDE)
    query.getHints.put(QueryHints.STATS_STRING, Stat.Enumeration("name"))
    query.getHints.put(QueryHints.ENCODE_STATS, true)

    // note: need to copy the features as the same object is re-used in the iterator
    val result = SelfClosingIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT)).toSeq
    result must haveLength(1)
    val stat = StatsScan.decodeStat(sft)(result.head.getAttribute(0).asInstanceOf[String])
    stat must beAnInstanceOf[EnumerationStat[String]]
    stat.asInstanceOf[EnumerationStat[String]].frequencies must containTheSameElementsAs(Seq(("n0", 1L), ("n1", 1L)))

    val jsonQuery = new Query(sft.getTypeName, Filter.INCLUDE)
    jsonQuery.getHints.put(QueryHints.STATS_STRING, Stat.Enumeration("name"))
    jsonQuery.getHints.put(QueryHints.ENCODE_STATS, false)
    val jsonResult = SelfClosingIterator(ds.getFeatureReader(jsonQuery, Transaction.AUTO_COMMIT)).toSeq
    jsonResult must haveLength(1)
    jsonResult.head.getAttribute(0).asInstanceOf[String] must (contain(""""n0":1""") and contain(""""n1":1"""))
    jsonResult.head.getAttribute(0).asInstanceOf[String] must haveLength(15)
  }

  "LambdaDataStore" should {
    "write and read features" in {
      readAndWriteTest()
    }
  }


  def readAndWriteTest(): MatchResult[Any] = {
    foreach(Seq(None, Some("my-lambda-topic"))) { customTopic =>
      def dsParams(extras: (String, String)*): java.util.Map[String, _] = {
        val catalog = s"${getClass.getSimpleName}${customTopic.getOrElse("").replaceAll("[^A-Za-z0-9]", "_")}"
        (this.dsParams ++ Map("lambda.accumulo.catalog" -> catalog) ++ extras.toMap).asJava
      }

      clock.tick = 0

      val sft = SimpleFeatureTypes.mutable(SimpleFeatureTypes.copy(this.sft))
      customTopic.foreach(sft.getUserData.put(LambdaDataStore.TopicKey, _))

      val ds = DataStoreFinder.getDataStore(dsParams()).asInstanceOf[LambdaDataStore]
      ds must not(beNull)

      try {
        ds.createSchema(sft)
        SimpleFeatureTypes.compare(ds.getSchema(sft.getTypeName), sft) mustEqual 0

        customTopic.foreach { topic =>
          LambdaDataStore.topic(ds.getSchema(sft.getTypeName), "") mustEqual topic
        }

        // check namespaces
        val ns = DataStoreFinder.getDataStore(dsParams("namespace" -> "ns0")).getSchema(sft.getTypeName).getName
        ns.getNamespaceURI mustEqual "ns0"
        ns.getLocalPart mustEqual sft.getTypeName

        // note: instantiate after creating the schema so it's not cached as missing
        val readOnly = DataStoreFinder.getDataStore(dsParams("expiry" -> "Inf")).asInstanceOf[LambdaDataStore]
        readOnly must not(beNull)

        try {
          SimpleFeatureTypes.compare(readOnly.getSchema(sft.getTypeName), sft) mustEqual 0

          WithClose(ds.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            features.foreach { feature =>
              FeatureUtils.write(writer, feature, useProvidedFid = true)
              clock.tick = clock.millis + 50
            }
          }

          // test queries against the transient store
          forall(Seq(ds, readOnly)) { store =>
            eventually(40, 100.millis)(SelfClosingIterator(store.transients.get(sft.getTypeName).read().iterator()).toSeq must
              containTheSameElementsAs(features))
            SelfClosingIterator(store.getFeatureReader(new Query(sft.getTypeName), Transaction.AUTO_COMMIT)).toSeq must
              containTheSameElementsAs(features)
          }
          testTransforms(ds, SimpleFeatureTypes.createType("lambda", "*geom:Point:srid=4326"))
          testBin(ds)
          testArrow(ds)
          testStats(ds)

          // persist one feature to long-term store
          clock.tick = 101
          ds.persist(sft.getTypeName)
          // test mixed queries against both stores
          forall(Seq(ds, readOnly)) { store =>
            eventually(40, 100.millis)(SelfClosingIterator(store.transients.get(sft.getTypeName).read().iterator()).toSeq must
              beEqualTo(features.drop(1)))
            SelfClosingIterator(store.getFeatureReader(new Query(sft.getTypeName), Transaction.AUTO_COMMIT)).toSeq must
              containTheSameElementsAs(features)
          }
          testTransforms(ds, SimpleFeatureTypes.createType("lambda", "*geom:Point:srid=4326"))
          testBin(ds)
          testArrow(ds)
          testStats(ds)

          // test query_persistent/query_transient hints
          forall(Seq((features.take(1), QueryHints.LAMBDA_QUERY_TRANSIENT, "LAMBDA_QUERY_TRANSIENT"),
            (features.drop(1) , QueryHints.LAMBDA_QUERY_PERSISTENT, "LAMBDA_QUERY_PERSISTENT"))) {
            case (feature, hint, string) =>
              val hints = Seq((hint, java.lang.Boolean.FALSE),
                (Hints.VIRTUAL_TABLE_PARAMETERS, Map(string -> "false").asJava))
              forall(hints) { case (k, v) =>
                val query = new Query(sft.getTypeName)
                query.getHints.put(k, v)
                SelfClosingIterator(ds.getFeatureReader(query, Transaction.AUTO_COMMIT)).toSeq mustEqual feature
              }
          }

          // persist both features to the long-term storage
          clock.tick = 151
          ds.persist(sft.getTypeName)
          // test queries against the persistent store
          forall(Seq(ds, readOnly)) { store =>
            eventually(40, 100.millis)(SelfClosingIterator(store.transients.get(sft.getTypeName).read().iterator()) must beEmpty)
            SelfClosingIterator(store.getFeatureReader(new Query(sft.getTypeName), Transaction.AUTO_COMMIT)).toSeq must
              containTheSameElementsAs(features)
          }
          testTransforms(ds, SimpleFeatureTypes.createType("lambda", "*geom:Point:srid=4326"))
          testBin(ds)
          testArrow(ds)
          testStats(ds)

          // write an update feature to the already persisted features and verify no duplicates are returned
          val update = ScalaSimpleFeature.create(sft, "0", "n0", "2017-06-15T00:00:01.000Z", "POINT (45 50.1)")
          WithClose(ds.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            FeatureUtils.write(writer, update, useProvidedFid = true)
          }
          forall(Seq(ds, readOnly)) { store =>
            eventually(40, 100.millis)(
              SelfClosingIterator(store.getFeatureReader(new Query(sft.getTypeName), Transaction.AUTO_COMMIT)).toSeq must
                containTheSameElementsAs(Seq(update, features.last))
            )
          }

          // verify the update is persisted
          clock.tick = 252
          ds.persist(sft.getTypeName)
          forall(Seq(ds, readOnly)) { store =>
            eventually(40, 100.millis)(SelfClosingIterator(store.transients.get(sft.getTypeName).read().iterator()) must beEmpty)
            SelfClosingIterator(store.getFeatureReader(new Query(sft.getTypeName), Transaction.AUTO_COMMIT)).toSeq must
              containTheSameElementsAs(Seq(update, features.last))
          }
        } finally {
          readOnly.dispose()
        }
      } finally {
        ds.dispose()
      }
    }
  }

  step {
    allocator.close()
    logger.info("LambdaDataStoreTest complete")
  }
}
