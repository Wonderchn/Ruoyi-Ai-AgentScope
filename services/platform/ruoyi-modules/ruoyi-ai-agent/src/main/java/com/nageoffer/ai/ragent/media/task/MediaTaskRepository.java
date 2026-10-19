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
import java.util.List;
import java.util.Optional;

/**
 * 媒体任务仓储（服务端任务权威）。所有查询都要求 {@code tenant_id + owner_member_id}
 * 同时命中；跨租户/他人任务与"不存在"同形（empty）。
 */
public interface MediaTaskRepository {

    /** 幂等键命中（同租户 + 同属主 + 同 requestId）。 */
    Optional<MediaTaskRecord> findByRequestId(String tenantId, String ownerMemberId, String requestId);

    /** 属主限定读取。 */
    Optional<MediaTaskRecord> findOwned(String tenantId, String ownerMemberId, String taskId);

    /** 属主限定列表（租户 + 属主，倒序）。 */
    List<MediaTaskRecord> listOwned(String tenantId, String ownerMemberId, int limit);

    /** 插入 QUEUED 任务；requestId 冲突由唯一约束抛出。 */
    void insert(MediaTaskRecord task);

    /** QUEUED → RUNNING（写 provider task id）。 */
    boolean markRunning(String tenantId, String taskId, String providerTaskId);

    /** RUNNING → SUCCEEDED（私有对象引用 + 用量事实）。 */
    boolean markSucceeded(String tenantId, String taskId, String resultRef, String resultMime,
                          String resultSha256, long resultSize, String usageJson, long units,
                          BigDecimal reservedCny);

    /** QUEUED/RUNNING → FAILED。 */
    boolean markFailed(String tenantId, String taskId, String errorCode, String errorMessage);

    /** QUEUED/RUNNING → UNKNOWN（不自动重发付费请求、不退还预占）。 */
    boolean markUnknown(String tenantId, String taskId, String errorCode, String errorMessage);

    /** 提交前登记预算信封 callId（只对 QUEUED 生效）。 */
    boolean attachSpendCall(String tenantId, String taskId, String spendCallId, BigDecimal reservedCny);
}
