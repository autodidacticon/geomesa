/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.arrow.tools.ingest

import com.beust.jcommander.{ParameterException, Parameters}
import org.locationtech.geomesa.arrow.data.ArrowDataStore
import org.locationtech.geomesa.arrow.tools.ArrowDataStoreCommand
import org.locationtech.geomesa.arrow.tools.ArrowDataStoreCommand.UrlParam
import org.locationtech.geomesa.arrow.tools.ingest.ArrowIngestCommand.ArrowIngestParams
import org.locationtech.geomesa.tools.ingest.IngestCommand
import org.locationtech.geomesa.tools.ingest.IngestCommand.IngestParams
import org.locationtech.geomesa.utils.io.PathUtils

class ArrowIngestCommand extends IngestCommand[ArrowDataStore] with ArrowDataStoreCommand {

  override val params = new ArrowIngestParams()

  override def execute(): Unit = {
    import scala.collection.JavaConverters._
    if (params.files.asScala.exists(PathUtils.isRemote _)) {
      throw new ParameterException(s"Only local ingestion supported: ${params.files}")
    }
    super.execute()
  }
}

object ArrowIngestCommand {
  @Parameters(commandDescription = "Ingest/convert various file formats into GeoMesa")
  class ArrowIngestParams extends IngestParams with UrlParam
}
