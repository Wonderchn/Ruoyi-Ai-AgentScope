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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 图谱后端连通性与投影状态（F08:2）。
 *
 * <p>配置是部署级只读事实（{@code GraphProperties}，改后需重启），本 VO 不提供运行时写。
 * {@link #apiKeyConfigured} 只表达"是否配置"，永不回显 key 值。
 */
@Data
@Builder
public class GraphStatusVO {

    /**
     * 后端类型：none / lightrag
     */
    private String backendType;

    /**
     * 是否配置了可探测的图谱后端（lightrag=true）
     */
    private boolean configured;

    /**
     * NOT_CONFIGURED / UP / DOWN / TIMEOUT
     */
    private String status;

    /**
     * 探测是否可达（仅 UP 为 true）
     */
    private boolean reachable;

    /**
     * 探测耗时（毫秒）；未探测时为 0
     */
    private long latencyMs;

    /**
     * 本次结果对应的探测时刻（缓存命中时为首次探测时刻）
     */
    private long checkedAtEpochMs;

    /**
     * 是否为 TTL 缓存命中
     */
    private boolean cached;

    /**
     * 后端基址（部署配置；非凭据）
     */
    private String baseUrl;

    /**
     * LightRAG 检索模式
     */
    private String queryMode;

    /**
     * 图谱 embedding 模型标识
     */
    private String embeddingModel;

    /**
     * 是否配置了 API Key（只回显布尔，不回显值）
     */
    private boolean apiKeyConfigured;

    /**
     * 可读中文原因（前端只看 HTTP status + msg）
     */
    private String reason;

    /**
     * 投影统计（主库派生数据）
     */
    private Projection projection;

    /**
     * 读源：固定 projection
     */
    private String readSource;

    @Data
    @Builder
    public static class Projection {

        private long entities;

        private long relations;

        private long docs;

        private Long lastSyncedAtEpochMs;
    }
}
