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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphStatusVO;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;

/**
 * 图谱后端状态（F08:2）。
 *
 * <p>配置唯一来源是 {@link GraphProperties}（部署级只读，改 type 需重启），本服务不提供运行时写。
 * 探测语义：
 * <ul>
 *   <li>{@code type=none} → 200 NOT_CONFIGURED，<b>零外呼</b>；</li>
 *   <li>lightrag → {@code GET /health} 专用短超时（不重试）；2xx=UP、超时=TIMEOUT、其他=DOWN；</li>
 *   <li>探测结果 TTL 缓存（默认 10s），{@code synchronized} 单锁防击穿。</li>
 * </ul>
 *
 * <p>投影统计来自主库，与后端是否 UP 无关：后端故障时页面仍能看到"投影陈旧但可读"。
 */
public class GraphStatusService {

    private final GraphProperties properties;
    private final GraphProjectionRepository repository;
    private final ObjectProvider<LightRagClient> clientProvider;
    private final AiResourceAuthorizationService authorization;
    private final Clock clock;
    private final Object probeLock = new Object();
    private CachedProbe cached;

    public GraphStatusService(GraphProperties properties,
                              GraphProjectionRepository repository,
                              ObjectProvider<LightRagClient> clientProvider,
                              AiResourceAuthorizationService authorization,
                              Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.clientProvider = clientProvider;
        this.authorization = authorization;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public GraphStatusVO status() {
        ExecutionPrincipal principal = PrincipalContext.require();
        authorization.requireFunction(principal, "config.read", "tenant:graph");
        GraphProjectionRepository.ProjectionStats stats = repository.stats(principal.tenantId());

        if (!properties.isLightrag()) {
            return base("NOT_CONFIGURED", false, false, 0L, clock.millis(), false,
                    "未配置图谱后端（rag.graph.type=none）；读路径只使用主库投影", stats);
        }

        LightRagClient.ProbeResult probe;
        long checkedAt;
        boolean cacheHit = false;
        synchronized (probeLock) {
            long now = clock.millis();
            long ttl = properties.getStatus().getCacheTtlMs();
            if (cached != null && ttl > 0 && now - cached.checkedAtEpochMs() < ttl) {
                probe = cached.probe();
                checkedAt = cached.checkedAtEpochMs();
                cacheHit = true;
            } else {
                LightRagClient client = clientProvider.getIfAvailable();
                probe = client == null
                        ? new LightRagClient.ProbeResult(false, false, 0L,
                                "图谱客户端未装配（rag.graph.type=lightrag 但 LightRagClient 缺席）")
                        : client.probe();
                checkedAt = now;
                cached = new CachedProbe(probe, checkedAt);
            }
        }

        String status = probe.reachable() ? "UP" : (probe.timedOut() ? "TIMEOUT" : "DOWN");
        return base(status, true, probe.reachable(), probe.latencyMs(), checkedAt, cacheHit,
                probe.reason(), stats);
    }

    private GraphStatusVO base(String status, boolean configured, boolean reachable, long latencyMs,
                               long checkedAtEpochMs, boolean cacheHit, String reason,
                               GraphProjectionRepository.ProjectionStats stats) {
        return GraphStatusVO.builder()
                .backendType(properties.getType())
                .configured(configured)
                .status(status)
                .reachable(reachable)
                .latencyMs(latencyMs)
                .checkedAtEpochMs(checkedAtEpochMs)
                .cached(cacheHit)
                .baseUrl(properties.getLightrag().getBaseUrl())
                .queryMode(properties.getLightrag().getQueryMode())
                .embeddingModel(properties.getEmbeddingModel())
                .apiKeyConfigured(StrUtil.isNotBlank(properties.getLightrag().getApiKey()))
                .reason(reason)
                .projection(GraphStatusVO.Projection.builder()
                        .entities(stats == null ? 0 : stats.entities())
                        .relations(stats == null ? 0 : stats.relations())
                        .docs(stats == null ? 0 : stats.docs())
                        .lastSyncedAtEpochMs(stats == null ? null : stats.lastSyncedAtEpochMs())
                        .build())
                .readSource("projection")
                .build();
    }

    private record CachedProbe(LightRagClient.ProbeResult probe, long checkedAtEpochMs) {
    }
}
