/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.status

import com.beust.jcommander.ParameterException
import com.typesafe.scalalogging.Logger
import org.geotools.api.data.DataStore
import org.geotools.api.feature.simple.SimpleFeatureType
import org.locationtech.geomesa.index.geotools.GeoMesaDataStore
import org.locationtech.geomesa.index.index.attribute.AttributeIndex
import org.locationtech.geomesa.index.index.z2.{XZ2Index, Z2Index}
import org.locationtech.geomesa.index.index.z3.{XZ3Index, Z3Index}
import org.locationtech.geomesa.tools.{Command, DataStoreCommand, TypeNameParam}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes

import scala.collection.mutable.ArrayBuffer

trait DescribeSchemaCommand[DS <: DataStore] extends DataStoreCommand[DS] {

  import scala.collection.JavaConverters._

  override val name: String = "describe-schema"

  override def execute(): Unit = withDataStore { ds =>
    val sft = getSchema(ds)
    if (sft == null) {
      val msg = params match {
        case p: TypeNameParam => s"Feature '${p.featureName}' not found"
        case _ => s"Feature type not found"
      }
      throw new ParameterException(msg)
    }
    Command.user.info(s"Describing attributes of feature '${sft.getTypeName}'")
    describe(ds, sft, Command.output)
  }

  protected def getSchema(ds: DS): SimpleFeatureType = params match {
    case p: TypeNameParam => ds.getSchema(p.featureName)
  }

  protected def describe(ds: DS, sft: SimpleFeatureType, logger: Logger): Unit = {
    import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType._

    val indices = ds match {
      case gmds: GeoMesaDataStore[_] => gmds.manager.indices(sft)
      case _ => Seq.empty
    }

    val namesAndDescriptions = sft.getAttributeDescriptors.asScala.map { descriptor =>
      val name = descriptor.getLocalName
      val description = ArrayBuffer.empty[String]

      indices.foreach {
        case i if (i.name == Z3Index.name || i.name == XZ3Index.name) && i.attributes.take(2).contains(name) =>
          description.append("(Spatio-temporally indexed)")

        case i if (i.name == Z2Index.name || i.name == XZ2Index.name) && i.attributes.headOption.contains(name) =>
          description.append("(Spatially indexed)")

        case i if i.name == AttributeIndex.name && i.attributes.headOption.contains(name) =>
          description.append("(Attribute indexed)")

        case i if i.name == AttributeIndex.JoinIndexName && i.attributes.headOption.contains(name) =>
          description.append("(Attribute indexed - join)")

        case _ => // no-op
      }

      Option(descriptor.getDefaultValue).foreach(v => description.append(s"Default Value: $v"))
      (name, descriptor.getType.getBinding.getSimpleName, description.mkString(" "))
    }

    val maxName = namesAndDescriptions.map(_._1.length).max
    val maxType = namesAndDescriptions.map(_._2.length).max
    namesAndDescriptions.foreach { case (n, t, d) =>
      logger.info(s"${n.padTo(maxName, ' ')} | ${t.padTo(maxType, ' ')} $d")
    }

    val userData = sft.getUserData
    if (!userData.isEmpty) {
      logger.info("\nUser data:")
      val namesAndValues = userData.asScala.toSeq.map { case (k, v) =>
        if (k == SimpleFeatureTypes.Configs.Keywords) {
          (SimpleFeatureTypes.Configs.Keywords, sft.getKeywords.mkString("'", "', '", "'"))
        } else {
          (s"$k", s"$v")
        }
      }
      val maxName = namesAndValues.map(_._1.length).max
      namesAndValues.sortBy(_._1).foreach { case (n, v) =>
        logger.info(s"  ${n.padTo(maxName, ' ')} | $v")
      }
    }
  }
}
