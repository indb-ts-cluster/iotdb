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

package org.apache.iotdb.db.pipe.connector.protocol.thrift.sync;

import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.commons.client.property.ThriftClientProperty;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.commons.pipe.config.PipeConfig;
import org.apache.iotdb.commons.pipe.connector.client.IoTDBThriftSyncConnectorClient;
import org.apache.iotdb.db.pipe.agent.PipeAgent;
import org.apache.iotdb.db.pipe.connector.payload.evolvable.common.PipeTransferHandshakeConstant;
import org.apache.iotdb.db.pipe.connector.payload.evolvable.request.PipeTransferHandshakeV1Req;
import org.apache.iotdb.db.pipe.connector.payload.evolvable.request.PipeTransferHandshakeV2Req;
import org.apache.iotdb.db.pipe.connector.protocol.thrift.IoTDBThriftClientManager;
import org.apache.iotdb.pipe.api.exception.PipeConnectionException;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.service.rpc.thrift.TPipeTransferResp;
import org.apache.iotdb.tsfile.utils.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IoTDBThriftSyncClientManager extends IoTDBThriftClientManager implements Closeable {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBThriftSyncClientManager.class);

  private static final PipeConfig PIPE_CONFIG = PipeConfig.getInstance();

  private final boolean useSSL;
  private final String trustStorePath;
  private final String trustStorePwd;

  private final Map<TEndPoint, Pair<IoTDBThriftSyncConnectorClient, Boolean>>
      endPoint2ClientAndStatus = new ConcurrentHashMap<>();

  public IoTDBThriftSyncClientManager(
      List<TEndPoint> endPoints,
      boolean useSSL,
      String trustStorePath,
      String trustStorePwd,
      boolean useLeaderCache) {
    super(endPoints, useLeaderCache);

    this.useSSL = useSSL;
    this.trustStorePath = trustStorePath;
    this.trustStorePwd = trustStorePwd;

    for (final TEndPoint endPoint : endPoints) {
      endPoint2ClientAndStatus.put(endPoint, new Pair<>(null, false));
    }
  }

  public void checkClientStatusAndTryReconstructIfNecessary() throws IOException {
    // reconstruct all dead clients
    for (final Map.Entry<TEndPoint, Pair<IoTDBThriftSyncConnectorClient, Boolean>> entry :
        endPoint2ClientAndStatus.entrySet()) {
      if (Boolean.TRUE.equals(entry.getValue().getRight())) {
        continue;
      }

      reconstructClient(entry.getKey());
    }

    // check whether any clients are available
    for (final Pair<IoTDBThriftSyncConnectorClient, Boolean> clientAndStatus :
        endPoint2ClientAndStatus.values()) {
      if (Boolean.TRUE.equals(clientAndStatus.getRight())) {
        return;
      }
    }
    throw new PipeConnectionException(
        String.format(
            "All target servers %s are not available.", endPoint2ClientAndStatus.keySet()));
  }

  private void reconstructClient(TEndPoint endPoint) {
    final Pair<IoTDBThriftSyncConnectorClient, Boolean> clientAndStatus =
        endPoint2ClientAndStatus.get(endPoint);

    if (clientAndStatus.getLeft() != null) {
      try {
        clientAndStatus.getLeft().close();
      } catch (Exception e) {
        LOGGER.warn(
            "Failed to close client with target server ip: {}, port: {}, because: {}. Ignore it.",
            endPoint.getIp(),
            endPoint.getPort(),
            e.getMessage());
      }
    }

    initClientAndStatus(clientAndStatus, endPoint);
    sendHandshakeReq(clientAndStatus, endPoint);
  }

  private void initClientAndStatus(
      Pair<IoTDBThriftSyncConnectorClient, Boolean> clientAndStatus, TEndPoint endPoint) {
    try {
      clientAndStatus.setLeft(
          new IoTDBThriftSyncConnectorClient(
              new ThriftClientProperty.Builder()
                  .setConnectionTimeoutMs((int) PIPE_CONFIG.getPipeConnectorHandshakeTimeoutMs())
                  .setRpcThriftCompressionEnabled(
                      PIPE_CONFIG.isPipeConnectorRPCThriftCompressionEnabled())
                  .build(),
              endPoint.getIp(),
              endPoint.getPort(),
              useSSL,
              trustStorePath,
              trustStorePwd));
    } catch (Exception e) {
      throw new PipeConnectionException(
          String.format(
              PipeConnectionException.CONNECTION_ERROR_FORMATTER,
              endPoint.getIp(),
              endPoint.getPort()),
          e);
    }
  }

  public void sendHandshakeReq(
      Pair<IoTDBThriftSyncConnectorClient, Boolean> clientAndStatus, TEndPoint endPoint) {
    try {
      final HashMap<String, String> params = new HashMap<>();
      params.put(
          PipeTransferHandshakeConstant.HANDSHAKE_KEY_TIME_PRECISION,
          CommonDescriptor.getInstance().getConfig().getTimestampPrecision());
      params.put(
          PipeTransferHandshakeConstant.HANDSHAKE_KEY_CLUSTER_ID,
          PipeAgent.runtime().getClusterIdIfPossible());

      // Try to handshake by PipeTransferHandshakeV2Req.
      TPipeTransferResp resp =
          clientAndStatus
              .getLeft()
              .pipeTransfer(PipeTransferHandshakeV2Req.toTPipeTransferReq(params));
      // Receiver may be an old version, so we need to retry to handshake by
      // PipeTransferHandshakeV1Req.
      if (resp.getStatus().getCode() == TSStatusCode.PIPE_TYPE_ERROR.getStatusCode()) {
        LOGGER.info(
            "Handshake error with target server ip: {}, port: {}, because: {}. "
                + "Retry to handshake by PipeTransferHandshakeV1Req.",
            endPoint.getIp(),
            endPoint.getPort(),
            resp.getStatus());
        resp =
            clientAndStatus
                .getLeft()
                .pipeTransfer(
                    PipeTransferHandshakeV1Req.toTPipeTransferReq(
                        CommonDescriptor.getInstance().getConfig().getTimestampPrecision()));
      }

      if (resp.getStatus().getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        LOGGER.warn(
            "Handshake error with target server ip: {}, port: {}, because: {}.",
            endPoint.getIp(),
            endPoint.getPort(),
            resp.getStatus());
      } else {
        clientAndStatus.setRight(true);
        clientAndStatus
            .getLeft()
            .setTimeout((int) PipeConfig.getInstance().getPipeConnectorTransferTimeoutMs());
        LOGGER.info(
            "Handshake success. Target server ip: {}, port: {}",
            endPoint.getIp(),
            endPoint.getPort());
      }
    } catch (Exception e) {
      LOGGER.warn(
          "Handshake error with target server ip: {}, port: {}, because: {}.",
          endPoint.getIp(),
          endPoint.getPort(),
          e.getMessage(),
          e);
    }
  }

  public Pair<IoTDBThriftSyncConnectorClient, Boolean> getClient() {
    final int clientSize = endPointList.size();
    // Round-robin, find the next alive client
    for (int tryCount = 0; tryCount < clientSize; ++tryCount) {
      final int clientIndex = (int) (currentClientIndex++ % clientSize);
      final Pair<IoTDBThriftSyncConnectorClient, Boolean> clientAndStatus =
          endPoint2ClientAndStatus.get(endPointList.get(clientIndex));
      if (Boolean.TRUE.equals(clientAndStatus.getRight())) {
        return clientAndStatus;
      }
    }
    throw new PipeConnectionException(
        "All clients are dead, please check the connection to the receiver.");
  }

  public Pair<IoTDBThriftSyncConnectorClient, Boolean> getClient(String deviceId) {
    final TEndPoint endPoint = leaderCacheManager.getLeaderEndPoint(deviceId);
    return useLeaderCache
            && endPoint != null
            && endPoint2ClientAndStatus.containsKey(endPoint)
            && Boolean.TRUE.equals(endPoint2ClientAndStatus.get(endPoint).getRight())
        ? endPoint2ClientAndStatus.get(endPoint)
        : getClient();
  }

  public void updateLeaderCache(String deviceId, TEndPoint endPoint) {
    if (!useLeaderCache) {
      return;
    }

    try {
      if (!endPoint2ClientAndStatus.containsKey(endPoint)) {
        endPointList.add(endPoint);
        endPoint2ClientAndStatus.put(endPoint, new Pair<>(null, false));
        reconstructClient(endPoint);
      }

      leaderCacheManager.updateLeaderEndPoint(deviceId, endPoint);
    } catch (Exception e) {
      LOGGER.warn(
          "Failed to update leader cache for device {} with endpoint {}:{}.",
          deviceId,
          endPoint.getIp(),
          endPoint.getPort(),
          e);
    }
  }

  @Override
  public void close() {
    for (final Map.Entry<TEndPoint, Pair<IoTDBThriftSyncConnectorClient, Boolean>> entry :
        endPoint2ClientAndStatus.entrySet()) {
      final TEndPoint endPoint = entry.getKey();
      final Pair<IoTDBThriftSyncConnectorClient, Boolean> clientAndStatus = entry.getValue();

      if (clientAndStatus == null) {
        continue;
      }

      try {
        if (clientAndStatus.getLeft() != null) {
          clientAndStatus.getLeft().close();
          clientAndStatus.setLeft(null);
        }
        LOGGER.info("Client {}:{} closed.", endPoint.getIp(), endPoint.getPort());
      } catch (Exception e) {
        LOGGER.warn(
            "Failed to close client {}:{}, because: {}.",
            endPoint.getIp(),
            endPoint.getPort(),
            e.getMessage(),
            e);
      } finally {
        clientAndStatus.setRight(false);
      }
    }
  }
}
