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

package com.nageoffer.ai.ragent.media.task;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 媒体任务行（{@code platform.ai_media_task} 的内存视图）。
 *
 * <p>租户 + 属主只由执行主体写入；查询/取流都必须同时带这两列。
 */
public record MediaTaskRecord(String tenantId,
                              String taskId,
                              String ownerMemberId,
                              String ownerUserId,
                              String mediaType,
                              String provider,
                              String model,
                              String requestId,
                              String previousTaskId,
                              int attempt,
                              MediaTaskStatus status,
                              String providerTaskId,
                              String prompt,
                              String size,
                              Integer seed,
                              String resultRef,
                              String resultMime,
                              String resultSha256,
                              Long resultSize,
                              String errorCode,
                              String errorMessage,
                              String usageJson,
                              Long units,
                              BigDecimal reservedCny,
                              String spendCallId,
                              Instant createdAt,
                              Instant updatedAt) {

    public boolean ownedBy(String tenant, String member) {
        return tenantId.equals(tenant) && ownerMemberId.equals(member);
    }

    public MediaTaskRecord withStatus(MediaTaskStatus next) {
        return new MediaTaskRecord(tenantId, taskId, ownerMemberId, ownerUserId, mediaType, provider, model,
                requestId, previousTaskId, attempt, next, providerTaskId, prompt, size, seed, resultRef,
                resultMime, resultSha256, resultSize, errorCode, errorMessage, usageJson, units,
                reservedCny, spendCallId, createdAt, updatedAt);
    }
}
