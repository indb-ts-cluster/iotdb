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
package org.apache.iotdb.consensus.natraft.protocol.log.dispatch;

import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.consensus.natraft.protocol.RaftMember;
import org.apache.iotdb.consensus.natraft.protocol.log.VotingLog;
import org.apache.iotdb.consensus.raft.thrift.AppendEntryResult;

import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;

import static org.apache.iotdb.consensus.natraft.protocol.Response.RESPONSE_AGREE;
import static org.apache.iotdb.consensus.natraft.protocol.Response.RESPONSE_LOG_MISMATCH;
import static org.apache.iotdb.consensus.natraft.protocol.Response.RESPONSE_OUT_OF_WINDOW;
import static org.apache.iotdb.consensus.natraft.protocol.Response.RESPONSE_STRONG_ACCEPT;
import static org.apache.iotdb.consensus.natraft.protocol.Response.RESPONSE_WEAK_ACCEPT;

/**
 * AppendNodeEntryHandler checks if the log is successfully appended by the quorum or some node has
 * rejected it for some reason when one node has finished the AppendEntryRequest. The target of the
 * log is the single nodes, it requires the agreement from the quorum of the nodes to reach
 * consistency.
 */
public class AppendNodeEntryHandler implements AsyncMethodCallback<AppendEntryResult> {

  private static final Logger logger = LoggerFactory.getLogger(AppendNodeEntryHandler.class);

  protected RaftMember member;
  protected VotingLog log;
  protected TEndPoint directReceiver;
  protected int quorumSize;

  public AppendNodeEntryHandler() {}

  @Override
  public void onComplete(AppendEntryResult response) {
    if (log.isHasFailed()) {
      return;
    }

    TEndPoint trueReceiver = response.isSetReceiver() ? response.receiver : directReceiver;

    logger.debug(
        "{}: Append response {} from {} for log {}", member.getName(), response, trueReceiver, log);

    long resp = response.status;

    if (resp == RESPONSE_STRONG_ACCEPT || resp == RESPONSE_AGREE) {
      member
          .getVotingLogList()
          .onStronglyAccept(
              log.getEntry().getCurrLogIndex(), log.getEntry().getCurrLogTerm(), trueReceiver);

      member.getStatus().getPeerMap().get(trueReceiver).setMatchIndex(response.lastLogIndex);
    } else if (resp > 0) {
      // a response > 0 is the follower's term
      // the leadership is stale, wait for the new leader's heartbeat
      logger.debug(
          "{}: Received a rejection from {} because term is stale: {}, log: {}",
          member.getName(),
          trueReceiver,
          resp,
          log);
      member.stepDown(resp, null);
      synchronized (log) {
        log.notifyAll();
      }
    } else if (resp == RESPONSE_WEAK_ACCEPT) {
      synchronized (log) {
        log.getWeaklyAcceptedNodes().add(trueReceiver);
        log.notifyAll();
      }
    } else {
      // e.g., Response.RESPONSE_LOG_MISMATCH
      if (resp == RESPONSE_LOG_MISMATCH || resp == RESPONSE_OUT_OF_WINDOW) {
        logger.debug(
            "{}: The log {} is rejected by {} because: {}",
            member.getName(),
            log,
            trueReceiver,
            resp);
      } else {
        logger.warn(
            "{}: The log {} is rejected by {} because: {}",
            member.getName(),
            log,
            trueReceiver,
            resp);
        onFail(trueReceiver);
      }
    }
    // rejected because the receiver's logs are stale or the receiver has no cluster info, just
    // wait for the heartbeat to handle
  }

  @Override
  public void onError(Exception exception) {
    if (exception instanceof ConnectException) {
      logger.warn(
          "{}: Cannot append log {}: cannot connect to {}: {}",
          member.getName(),
          log,
          directReceiver,
          exception.getMessage());
    } else {
      logger.warn(
          "{}: Cannot append log {} to {}", member.getName(), log, directReceiver, exception);
    }
    onFail(directReceiver);
  }

  private void onFail(TEndPoint trueReceiver) {
    synchronized (log) {
      log.getFailedNodes().add(trueReceiver);
      if (log.getFailedNodes().size() > quorumSize) {
        // quorum members have failed, there is no need to wait for others
        log.setHasFailed(true);
        log.notifyAll();
      }
    }
  }

  public void setLog(VotingLog log) {
    this.log = log;
  }

  public void setMember(RaftMember member) {
    this.member = member;
  }

  public void setDirectReceiver(TEndPoint follower) {
    this.directReceiver = follower;
  }

  public void setQuorumSize(int quorumSize) {
    this.quorumSize = quorumSize;
  }
}
