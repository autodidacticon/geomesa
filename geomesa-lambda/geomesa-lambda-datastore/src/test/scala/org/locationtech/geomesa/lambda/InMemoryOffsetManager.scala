/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.lambda

import org.locationtech.geomesa.index.utils.DistributedLocking
import org.locationtech.geomesa.lambda.stream.OffsetManager
import org.locationtech.geomesa.lambda.stream.OffsetManager.OffsetListener

import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.{Lock, ReentrantLock}

class InMemoryOffsetManager extends OffsetManager {

  private val locks = scala.collection.mutable.Map.empty[String, Lock]
  private val offsets = scala.collection.mutable.Map.empty[(String, Int), Long]
  private val listeners = scala.collection.mutable.Map.empty[String, Set[OffsetListener]]

  override def getOffset(topic: String, partition: Int): Long =
    offsets.synchronized(offsets.get((topic, partition))).getOrElse(-1L)

  override def setOffset(topic: String, partition: Int, offset: Long): Unit = {
    offsets.synchronized {
      offsets.put((topic, partition), offset)
      listeners.synchronized(listeners.getOrElse(topic, Set.empty)).foreach(_.offsetChanged(partition, offset))
    }
  }

  // note: not very robust - won't work with 10+ partitions
  override def deleteOffsets(topic: String): Unit =
    (0 until 10).foreach(i => offsets.synchronized(offsets.remove((topic, i))))

  override def addOffsetListener(topic: String, listener: OffsetListener): Unit =
    listeners.synchronized(listeners.put(topic, listeners.getOrElse(topic, Set.empty) + listener))

  override def removeOffsetListener(topic: String, listener: OffsetListener): Unit =
    listeners.synchronized(listeners.put(topic, listeners.getOrElse(topic, Set.empty) - listener))

  override def close(): Unit = {}

  override protected def acquireDistributedLock(key: String): Closeable = {
    val lock = locks.synchronized(locks.getOrElseUpdate(key, new ReentrantLock()))
    lock.lock()
    DistributedLocking.releasable(lock)
  }

  override protected def acquireDistributedLock(key: String, timeOut: Long): Option[Closeable] = {
    val lock = locks.synchronized(locks.getOrElseUpdate(key, new ReentrantLock()))
    if (lock.tryLock(timeOut, TimeUnit.MILLISECONDS)) {
      Some(DistributedLocking.releasable(lock))
    } else {
      None
    }
  }
}
