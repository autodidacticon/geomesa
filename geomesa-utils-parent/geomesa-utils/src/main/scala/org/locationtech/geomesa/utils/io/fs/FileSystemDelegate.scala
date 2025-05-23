/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.utils.io.fs

import com.typesafe.scalalogging.LazyLogging
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.utils.io.PathUtils
import org.locationtech.geomesa.utils.io.fs.FileSystemDelegate.FileHandle

import java.io.{InputStream, OutputStream}
import java.net.URL

trait FileSystemDelegate extends LazyLogging {

  /**
    * Get the handle for a given file, which may or may not exist
    *
    * @param path path
    * @return
    */
  def getHandle(path: String): FileHandle

  /**
    * Expand wildcards, recurse into directories, etc
    *
    * @param path input path
    * @return any files found in the interpreted path
    */
  def interpretPath(path: String): Seq[FileHandle]

  /**
   * Gets a URL for a path
   *
   * @param path path
   * @return
   */
  def getUrl(path: String): URL
}

object FileSystemDelegate extends LazyLogging {

  /**
    * Creation mode for files
    *
    * `Create` - file must not exist, else throw `FileAlreadyExists`
    * `Overwrite` - existing file will be truncated, else throw `FileNotFoundException`
    * `Append` - existing file will be appended, else throw `FileNotFoundException`
    * `Create|Overwrite` - if file exists, create it, else truncate it
    * `Create|Append` - if file exists, create it, else append it
    * `CreateParents` - combined with `Create`, if parent folder does not exist, create it
    */
  object CreateMode {
    val Create    : CreateMode = new CreateMode(0x01)
    val Overwrite : CreateMode = new CreateMode(0x02)
    val Append    : CreateMode = new CreateMode(0x04)
  }

  class CreateMode(val flag: Int) extends AnyVal {

    def |(other: CreateMode): CreateMode = new CreateMode(flag | other.flag)

    def create: Boolean = (flag & CreateMode.Create.flag) != 0
    def append: Boolean = (flag & CreateMode.Append.flag) != 0
    def overwrite: Boolean = (flag & CreateMode.Overwrite.flag) != 0

    def validate(): Unit = {
      if (append && overwrite) {
        throw new IllegalArgumentException("Can't specify both append and overwrite")
      } else if (!append && !overwrite && !create) {
        throw new IllegalArgumentException("Must specify at least one of create, append or overwrite")
      }
    }
  }

  /**
    * Abstraction over a readable file
    */
  trait FileHandle {

    /**
      * The file extension, minus any compression or zipping
      *
      * @return
      */
    lazy val format: String = PathUtils.getUncompressedExtension(path)

    /**
      * Path to the underlying file represented by this object
      *
      * @return
      */
    def path: String

    /**
      * Does the file exist or not
      *
      * @return
      */
    def exists: Boolean

    /**
      * File length (size), in bytes
      *
      * @return
      */
    def length: Long

    /**
      * Open an input stream to read the underlying file. Archive formats (tar, zip) will return multiple streams,
      * one per archive entry, along with the name of the entry. The iterator of input streams should only be
      * closed once all the input streams have been processed. The individual streams will be closed when the
      * overall iterator is closed, although they may be closed individually if desired
      *
      * @return
      */
    def open: CloseableIterator[(Option[String], InputStream)]

    /**
      * Open the file for writing
      *
      * @param mode write mode
      */
    def write(mode: CreateMode): OutputStream

    /**
     * Open the file for writing
     *
     * @param mode write mode
     * @param createParents create parent dirs as necessary
     */
    @deprecated("createParents is always true")
    def write(mode: CreateMode, createParents: Boolean): OutputStream = {
      if (!createParents) {
        logger.warn("Call to write with createParents=false, which is not supported")
      }
      write(mode)
    }

    /**
      * Delete the file
      *
      * @param recursive if the file is a directory, recursively delete its contents
      */
    def delete(recursive: Boolean = false): Unit
  }
}
