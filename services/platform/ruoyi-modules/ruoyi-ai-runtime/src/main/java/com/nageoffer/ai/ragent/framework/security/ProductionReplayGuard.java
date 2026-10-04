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

package com.nageoffer.ai.ragent.framework.security;

import java.time.Instant;

/**
 * 委托 jti 的持久重放防护端口（U06/P1.2c 引入）。
 *
 * <p>framework 定义端口、rag 提供持久实现（{@code ai_delegation_replay}，
 * 主键 (issuer, jti)，V5 建立）：验签成功之后、授权判定之前原子消费 jti；
 * 首次消费返回 true，重复 jti 一律 401。消费失败（存储不可用）不得当作未消费——
 * 调用方必须拒绝（503），不能放行。
 */
public interface ProductionReplayGuard {

    /**
     * 原子消费 jti。
     *
     * @return true=首次消费（继续）；false=重复 jti（拒绝）
     * @throws Exception 存储不可用——调用方按 503 拒绝，不得降级放行
     */
    boolean consume(String issuer, String jti, String tenantId, Instant expiresAt) throws Exception;
}
