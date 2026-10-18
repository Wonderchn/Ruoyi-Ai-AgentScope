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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphStatusVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class GraphStatusServiceTest {

    private GraphProperties properties;
    private FakeGraphProjectionRepository repository;
    private AiResourceAuthorizationService authorization;
    private LightRagClient client;
    private MutableClock clock;
    private GraphStatusService service;
    private ExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        properties = new GraphProperties();
        repository = new FakeGraphProjectionRepository();
        authorization = mock(AiResourceAuthorizationService.class);
        client = mock(LightRagClient.class);
        clock = new MutableClock(Instant.ofEpochMilli(1_000_000));
        ObjectProvider<LightRagClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        service = new GraphStatusService(properties, repository, provider, authorization, clock);
        principal = new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 1,
                Set.of("config.read"), "jti-1", "platform", 1, 9_999_999_999L);
        PrincipalContext.set(principal);
        repository.stats = new GraphProjectionRepository.ProjectionStats(7, 5, 2, 1_234_567L);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("R11 none：NOT_CONFIGURED、零外呼、投影统计仍返回")
    void noneBackendNeverProbes() {
        GraphStatusVO status = service.status();

        assertFalse(status.isConfigured());
        assertEquals("NOT_CONFIGURED", status.getStatus());
        assertFalse(status.isReachable());
        assertTrue(status.getReason().contains("rag.graph.type=none"));
        assertEquals("projection", status.getReadSource());
        assertEquals(7, status.getProjection().getEntities());
        assertEquals(5, status.getProjection().getRelations());
        assertEquals(2, status.getProjection().getDocs());
        assertEquals(1_234_567L, status.getProjection().getLastSyncedAtEpochMs());
        verify(client, times(0)).probe();
    }

    @Test
    @DisplayName("R11 UP：探测结果与延迟进入 VO")
    void upBackendReportsLatency() {
        properties.setType("lightrag");
        when(client.probe()).thenReturn(new LightRagClient.ProbeResult(true, false, 12L, "ok"));

        GraphStatusVO status = service.status();

        assertTrue(status.isConfigured());
        assertEquals("UP", status.getStatus());
        assertTrue(status.isReachable());
        assertEquals(12L, status.getLatencyMs());
        assertFalse(status.isCached());
        assertEquals("projection", status.getReadSource());
    }

    @Test
    @DisplayName("R11 TTL 缓存：10s 内复用、过期后重新探测")
    void probeResultIsCachedForTtl() {
        properties.setType("lightrag");
        when(client.probe()).thenReturn(new LightRagClient.ProbeResult(true, false, 12L, "ok"));

        service.status();
        clock.advance(9_999);
        GraphStatusVO cached = service.status();
        assertTrue(cached.isCached());
        verify(client, times(1)).probe();

        clock.advance(1);
        GraphStatusVO fresh = service.status();
        assertFalse(fresh.isCached());
        verify(client, times(2)).probe();
    }

    @Test
    @DisplayName("R11 不可达/超时：DOWN / TIMEOUT 各自外显，不伪造 UP")
    void downAndTimeoutAreDistinct() {
        properties.setType("lightrag");
        when(client.probe()).thenReturn(new LightRagClient.ProbeResult(false, false, 4L, "connection refused"));
        GraphStatusVO down = service.status();
        assertEquals("DOWN", down.getStatus());
        assertFalse(down.isReachable());

        clock.advance(20_000);
        when(client.probe()).thenReturn(new LightRagClient.ProbeResult(false, true, 1500L, "timeout"));
        GraphStatusVO timeout = service.status();
        assertEquals("TIMEOUT", timeout.getStatus());
        assertFalse(timeout.isReachable());
    }

    @Test
    @DisplayName("R11 apiKey 只回显是否配置，绝不回显值")
    void apiKeyIsNeverEchoed() {
        properties.setType("lightrag");
        properties.getLightrag().setApiKey("sk-super-secret");
        when(client.probe()).thenReturn(new LightRagClient.ProbeResult(true, false, 1L, "ok"));

        GraphStatusVO status = service.status();

        assertTrue(status.isApiKeyConfigured());
        assertFalse(status.toString().contains("sk-super-secret"));
        assertEquals("http://127.0.0.1:9621", status.getBaseUrl());
        assertEquals("hybrid", status.getQueryMode());
    }

    @Test
    @DisplayName("R11 功能级拒绝在探测前生效")
    void deniedFunctionStopsBeforeProbe() {
        doThrow(new P04AiException(P04AiErrorCode.FORBIDDEN))
                .when(authorization).requireFunction(principal, "config.read", "tenant:graph");

        assertThrows(P04AiException.class, () -> service.status());
        verify(client, times(0)).probe();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
