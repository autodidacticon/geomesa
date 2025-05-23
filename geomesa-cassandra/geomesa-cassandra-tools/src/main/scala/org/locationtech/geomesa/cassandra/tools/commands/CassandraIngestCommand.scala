/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.cassandra.tools.commands

import com.beust.jcommander.{ParameterException, Parameters}
import org.locationtech.geomesa.cassandra.data.CassandraDataStore
import org.locationtech.geomesa.cassandra.tools.CassandraDataStoreCommand
import org.locationtech.geomesa.cassandra.tools.CassandraDataStoreCommand.CassandraDataStoreParams
import org.locationtech.geomesa.cassandra.tools.commands.CassandraIngestCommand.CassandraIngestParams
import org.locationtech.geomesa.tools.ingest.IngestCommand
import org.locationtech.geomesa.tools.ingest.IngestCommand.IngestParams
import org.locationtech.geomesa.utils.io.PathUtils

import scala.collection.JavaConverters._

class CassandraIngestCommand extends IngestCommand[CassandraDataStore] with CassandraDataStoreCommand {

  override val params = new CassandraIngestParams

  override def execute(): Unit = {
    if (params.files.asScala.exists(PathUtils.isRemote _)) {
      throw new ParameterException("The Cassandra ingest tool does not support distributed ingest.")
    }
    super.execute()
  }
}

object CassandraIngestCommand {
  @Parameters(commandDescription = "Ingest/convert various file formats into GeoMesa")
  class CassandraIngestParams extends IngestParams with CassandraDataStoreParams
}
