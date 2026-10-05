/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.runtime.model;

import java.time.Instant;

/**
 * {@code ai_run} 行快照。所有执行写入必须携带 {@code fence} 与 {@code version}。
 */
public record RunRecord(
        String tenantId,
        String runId,
        String memberId,
        String subject,
        String action,
        String status,
        String requestHash,
        String idempotencyKey,
        String inputJson,
        String budgetJson,
        String executionVersion,
        int policyVersion,
        int aclVersion,
        String resourceRefsJson,
        long version,
        long nextSeq,
        int attempt,
        long fence,
        String leaseOwner,
        Instant leaseUntil,
        String terminalResultJson,
        String errorCode,
        String retryOf,
        Instant cancelRequestedAt,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public boolean isTerminal() {
        return RunStatus.isTerminal(status);
    }

    public RunRecord withLease(String owner, Instant until, long newFence, int newAttempt, long newVersion) {
        return new RunRecord(tenantId, runId, memberId, subject, action, status, requestHash, idempotencyKey,
                inputJson, budgetJson, executionVersion, policyVersion, aclVersion, resourceRefsJson,
                newVersion, nextSeq, newAttempt, newFence, owner, until, terminalResultJson, errorCode, retryOf,
                cancelRequestedAt, createdAt, startedAt, finishedAt);
    }
}
