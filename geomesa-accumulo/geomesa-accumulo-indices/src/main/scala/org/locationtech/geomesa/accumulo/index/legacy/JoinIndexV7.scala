/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.index.legacy

import org.geotools.api.feature.simple.SimpleFeatureType
import org.locationtech.geomesa.accumulo.index.AttributeJoinIndex
import org.locationtech.geomesa.index.api.ShardStrategy.AttributeShardStrategy
import org.locationtech.geomesa.index.api.{RowKeyValue, WritableFeature}
import org.locationtech.geomesa.index.geotools.GeoMesaDataStore
import org.locationtech.geomesa.index.index.attribute.legacy.AttributeIndexV7
import org.locationtech.geomesa.index.index.attribute.legacy.AttributeIndexV7.AttributeIndexKeySpaceV7
import org.locationtech.geomesa.index.index.attribute.{AttributeIndexKey, AttributeIndexKeySpace}
import org.locationtech.geomesa.utils.index.IndexMode.IndexMode

class JoinIndexV7(ds: GeoMesaDataStore[_],
                  sft: SimpleFeatureType,
                  attribute: String,
                  secondaries: Seq[String],
                  mode: IndexMode)
    extends AttributeIndexV7(ds, sft, attribute, secondaries, mode) with AttributeJoinIndex {

  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  override val keySpace: AttributeIndexKeySpace =
    new AttributeIndexKeySpaceV7(sft, sft.getTableSharingBytes, AttributeShardStrategy(sft), attribute) {
      override def toIndexKey(writable: WritableFeature,
                              tier: Array[Byte],
                              id: Array[Byte],
                              lenient: Boolean): RowKeyValue[AttributeIndexKey] = {
        super.toIndexKey(writable, tier, id, lenient).copy(writable.reducedValues)
      }
    }
}
