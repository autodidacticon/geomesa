/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs.data

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.geotools.api.data.DataAccessFactory.Param
import org.geotools.api.data.{DataStore, DataStoreFactorySpi}
import org.locationtech.geomesa.fs.data.FileSystemDataStore.FileSystemDataStoreConfig
import org.locationtech.geomesa.fs.storage.api.FileSystemStorageFactory
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.{GeoMesaDataStoreInfo, NamespaceParams}
import org.locationtech.geomesa.utils.classpath.ServiceLoader
import org.locationtech.geomesa.utils.conf.GeoMesaSystemProperties.SystemProperty
import org.locationtech.geomesa.utils.geotools.GeoMesaParam
import org.locationtech.geomesa.utils.geotools.GeoMesaParam.{ConvertedParam, ReadWriteFlag, SystemPropertyDurationParam}
import org.locationtech.geomesa.utils.hadoop.HadoopUtils

import java.awt.RenderingHints
import java.io.{ByteArrayInputStream, StringReader, StringWriter}
import java.nio.charset.StandardCharsets
import java.util.{Collections, Properties}
import scala.concurrent.duration.Duration

class FileSystemDataStoreFactory extends DataStoreFactorySpi {

  import FileSystemDataStoreFactory.FileSystemDataStoreParams._

  override def createDataStore(params: java.util.Map[String, _]): DataStore = {

    val xml = ConfigsParam.lookupOpt(params)
    val resources = ConfigPathsParam.lookupOpt(params).toSeq.flatMap(_.split(',')).map(_.trim).filterNot(_.isEmpty)

    val conf = if (xml.isEmpty && resources.isEmpty) { FileSystemDataStoreFactory.configuration } else {
      val conf = new Configuration(FileSystemDataStoreFactory.configuration)
      // add the explicit props first, they may be needed for loading the path resources
      xml.foreach(x => conf.addResource(new ByteArrayInputStream(x.getBytes(StandardCharsets.UTF_8))))
      resources.foreach(HadoopUtils.addResource(conf, _))
      conf
    }

    val path = new Path(PathParam.lookup(params))
    val encoding = EncodingParam.lookupOpt(params).filterNot(_.isEmpty)

    // Need to do more tuning here. On a local system 1 thread (so basic producer/consumer) was best
    // because Parquet is also threading the reads underneath I think. using prod/cons pattern was
    // about 30% faster but increasing beyond 1 thread slowed things down. This could be due to the
    // cost of serializing simple features though. need to investigate more.
    //
    // However, if you are doing lots of filtering it appears that bumping the threads up high
    // can be very useful. Seems possibly numcores/2 might is a good setting (which is a standard idea)

    val readThreads = ReadThreadsParam.lookup(params)
    val writeTimeout = WriteTimeoutParam.lookup(params)
    val queryTimeout = QueryTimeoutParam.lookupOpt(params).filter(_.isFinite)
    AuthsParam.lookupOpt(params).foreach { auths =>
      conf.set(AuthsParam.key, auths)
    }

    val namespace = NamespaceParam.lookupOpt(params)

    val fs = FileSystem.get(path.toUri, conf)

    val config = FileSystemDataStoreConfig(conf, path, readThreads, writeTimeout, queryTimeout, encoding, namespace)

    new FileSystemDataStore(fs, config)
  }

  override def createNewDataStore(params: java.util.Map[String, _]): DataStore =
    createDataStore(params)

  override def isAvailable: Boolean = true

  override def canProcess(params: java.util.Map[String, _]): Boolean =
    FileSystemDataStoreFactory.canProcess(params)

  override def getDisplayName: String = FileSystemDataStoreFactory.DisplayName

  override def getDescription: String = FileSystemDataStoreFactory.Description

  override def getParametersInfo: Array[Param] = Array(FileSystemDataStoreFactory.ParameterInfo :+ NamespaceParam: _*)

  override def getImplementationHints: java.util.Map[RenderingHints.Key, _] = Collections.emptyMap()
}

object FileSystemDataStoreFactory extends GeoMesaDataStoreInfo {

  import scala.collection.JavaConverters._

  override val DisplayName: String = "File System (GeoMesa)"
  override val Description: String = "File System Data Store"

  override val ParameterInfo: Array[GeoMesaParam[_ <: AnyRef]] =
    Array(
      FileSystemDataStoreParams.PathParam,
      FileSystemDataStoreParams.EncodingParam,
      FileSystemDataStoreParams.ReadThreadsParam,
      FileSystemDataStoreParams.WriteTimeoutParam,
      FileSystemDataStoreParams.QueryTimeoutParam,
      FileSystemDataStoreParams.ConfigPathsParam,
      FileSystemDataStoreParams.ConfigsParam,
      FileSystemDataStoreParams.AuthsParam,
    )

  // lazy to avoid masking classpath errors with missing hadoop
  private lazy val configuration = new Configuration()

  override def canProcess(params: java.util.Map[String, _]): Boolean =
    FileSystemDataStoreParams.PathParam.exists(params)

  object FileSystemDataStoreParams extends NamespaceParams {

    val WriterFileTimeout: SystemProperty = SystemProperty("geomesa.fs.writer.partition.timeout", "60s")

    val DeprecatedConfParam = new ConvertedParam[String, String]("fs.config", convertPropsToXml)

    val PathParam =
      new GeoMesaParam[String](
        "fs.path",
        "Root of the filesystem hierarchy",
        optional = false,
        supportsNiFiExpressions = true
      )

    val EncodingParam =
      new GeoMesaParam[String](
        "fs.encoding",
        "Encoding of data",
        default = "", // needed to prevent geoserver from selecting something
        enumerations = ServiceLoader.load[FileSystemStorageFactory]().map(_.encoding),
        supportsNiFiExpressions = true,
        readWrite = ReadWriteFlag.WriteOnly
      )

    val ConfigPathsParam =
      new GeoMesaParam[String](
        "fs.config.paths",
        "Additional Hadoop configuration resource files (comma-delimited)",
        supportsNiFiExpressions = true
      )

    val ConfigsParam =
      new GeoMesaParam[String](
        "fs.config.xml",
        "Additional Hadoop configuration properties, as a standard XML `<configuration>` element",
        largeText = true,
        deprecatedParams = Seq(DeprecatedConfParam),
        supportsNiFiExpressions = true
      )

    val ReadThreadsParam =
      new GeoMesaParam[Integer](
        "fs.read-threads",
        "Read Threads",
        default = 4,
        supportsNiFiExpressions = true,
        readWrite = ReadWriteFlag.ReadOnly
      )

    val WriteTimeoutParam =
      new GeoMesaParam[Duration](
        "fs.writer.partition.timeout",
        "Timeout for closing a partition file after write, e.g. '60 seconds'",
        default = Duration("60s"),
        systemProperty = Some(SystemPropertyDurationParam(WriterFileTimeout)),
        supportsNiFiExpressions = true,
        readWrite = ReadWriteFlag.WriteOnly
      )

    val QueryTimeoutParam: GeoMesaParam[Duration] = GeoMesaDataStoreFactory.QueryTimeoutParam

    val AuthsParam: GeoMesaParam[String] = org.locationtech.geomesa.security.AuthsParam

    @deprecated("ConfigsParam")
    val ConfParam =
      new GeoMesaParam[Properties](
        "fs.config",
        "Values to set in the root Configuration, in Java properties format",
        largeText = true
      )

    /**
      * Convert java properties format to *-site.xml
      *
      * @param properties props
      * @return
      */
    private [fs] def convertPropsToXml(properties: String): String = {
      val conf = new Configuration(false)

      val props = new Properties()
      props.load(new StringReader(properties))
      props.asScala.foreach { case (k, v) => conf.set(k, v) }

      val out = new StringWriter()
      conf.writeXml(out)
      out.toString
    }
  }
}
