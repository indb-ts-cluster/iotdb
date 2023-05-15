/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.tsfile.fileSystem.fileOutputFactory;

import org.apache.iotdb.tsfile.write.writer.TsFileOutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class OSFileOutputFactory implements FileOutputFactory {
  private static final Logger logger = LoggerFactory.getLogger(OSFileOutputFactory.class);
  private static final String OS_OUTPUT_CLASS_NAME =
      "org.apache.iotdb.os.fileSystem.OSTsFileOutput";
  private static Constructor constructor;

  static {
    try {
      Class<?> clazz = Class.forName(OS_OUTPUT_CLASS_NAME);
      constructor = clazz.getConstructor(String.class, boolean.class);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      logger.error(
          "Failed to get OSInput in object storage. Please check your dependency of object storage module.",
          e);
    }
  }

  @Override
  public TsFileOutput getTsFileOutput(String filePath, boolean append) {
    try {
      return (TsFileOutput) constructor.newInstance(filePath, !append);
    } catch (InstantiationException | InvocationTargetException | IllegalAccessException e) {
      logger.error(
          "Failed to get TsFile output of file: {}. Please check your dependency of object storage module.",
          filePath,
          e);
      return null;
    }
  }
}
