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

package com.nageoffer.ai.ragent.runtime.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F23-A2 判据（红样本钉缺陷，修后在位）：SSE 回放保留期收口（P0.3 §3.3 / P2 U03）。
 *
 * <p>缺陷（F23 探针 op2-N6 实锤）：{@code retentionExpired} 计算后未参与任何判定 ⇒
 * 早于保留窗口（默认 24h）的事件仍被 200 流式回放（红样本实测即此形态）。修法：保留期
 * 判定接入 410（{@code writeCursorExpired}+快照，与 minSeq 游标 410 同路径），且时间窗
 * 一律以 DB {@code now()} 为锚（D4：VM app 时钟与 DB 差 +8h）。
 */
@Tag("dev")
class RunEventStreamRetentionTest {

    private record Harness(RunEventStreamService service, RunLedgerDao dao, NotificationBus bus,
                           ExecutionPrincipal principal) {
    }

    private static Harness harness() {
        RunLedgerDao dao = mock(RunLedgerDao.class);
        RunLifecycleService lifecycle = mock(RunLifecycleService.class);
        NotificationBus bus = mock(NotificationBus.class);
        DeliveryPermits permits = mock(DeliveryPermits.class);
        AiResourceAuthorizationService authorization = mock(AiResourceAuthorizationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiResourceAuthorizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(authorization);

        ExecutionPrincipal principal = new ExecutionPrincipal("t1", "1001", "platform:t1:1001", 1, 1,
                Set.of("run.stream"), "j", "platform", 1, 9_999_999_999L);
        Instant longAgo = Instant.now().minus(25, ChronoUnit.HOURS);
        RunRecord run = new RunRecord("t1", "r-old", "platform:t1:1001", "1001", "rag.chat", "SUCCEEDED",
                "hash", "key-1", "{}", "{}", "v1", 1, 1, "[]", 3L, 2L, 1, 1L,
                null, null, "{\"status\":\"SUCCEEDED\"}", null, null, null, longAgo, longAgo, longAgo);
        when(lifecycle.get(principal, "r-old")).thenReturn(run);
        when(authorization.check(any(), any(), any())).thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        when(bus.subscribe(any(), any(), any())).thenReturn(() -> { });
        RevocationGuard guard = mock(RevocationGuard.class);
        when(permits.enter(any(), any(), any())).thenReturn(new DeliveryPermits.Permit("permit-1", "op-1",
                new RevocationGuard.Operation(guard, "permit-1", "op-1")));

        var service = new RunEventStreamService(dao, lifecycle, bus, new P2RuntimeProperties(),
                wireMapper(), provider, permits);
        return new Harness(service, dao, bus, principal);
    }

    /** 与应用权威 mapper 同口径（Long→字符串、JavaTimeModule），使 410 信封断言即线上形状。 */
    private static ObjectMapper wireMapper() {
        SimpleModule platformModule = new SimpleModule();
        platformModule.addSerializer(Long.class, ToStringSerializer.instance);
        return new ObjectMapper().registerModules(platformModule, new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /** 红用例：保留期外游标（事件 25h 前落库）必须以 410+快照终结，不得 200 静默回放。 */
    @Test
    void outOfRetentionCursorEndsWith410InsteadOfSilentReplay() throws Exception {
        Harness h = harness();
        when(h.dao.minSeq("t1", "r-old")).thenReturn(1L);
        when(h.dao.retentionExpired("t1", "r-old", 0, 24)).thenReturn(true);
        // 若保留期判定被忽略，该夹具会让 25h 前的事件被真实回放出去（红样本即此形态）
        when(h.dao.maxSeq("t1", "r-old")).thenReturn(1L);
        when(h.dao.listEvents(eq("t1"), eq("r-old"), anyLong(), anyInt())).thenReturn(List.of(
                new RunLedgerDao.EventRow(1, "e-1", "run.terminal", "{}", Instant.now().minus(25, ChronoUnit.HOURS))));

        var response = new MockHttpServletResponse();
        boolean streamed = h.service.stream(h.principal, "r-old", 0, response);
        String body = response.getContentAsString();

        assertFalse(streamed, () -> "保留期外回放必须以 410 终结，实际 body=" + body);
        assertEquals(410, response.getStatus(), () -> "保留期外回放必须以 410 终结，实际 body=" + body);
        assertTrue(body.contains("CURSOR_EXPIRED"), body);
        assertTrue(body.contains("\"lastSeq\":\"0\""), body); // Long→String 为产品 wire 契约（PlatformObjectMapperConfig）
        assertTrue(body.contains("\"snapshot\""), body);
        assertFalse(body.contains("id:"), "保留期外不得静默回放事件帧：" + body);
        verify(h.dao).retentionExpired("t1", "r-old", 0, 24); // DB 锚判定入口：afterSeq + 配置保留窗口原样下传
        verify(h.bus, never()).subscribe(any(), any(), any());
    }

    /** 对照：保留期内（1h 前）的同一回放路径照常 200 流到终态（防"一律 410"的过度收口）。 */
    @Test
    void inRetentionCursorStillReplaysToTerminal() throws Exception {
        Harness h = harness();
        when(h.dao.minSeq("t1", "r-old")).thenReturn(1L);
        when(h.dao.retentionExpired("t1", "r-old", 0, 24)).thenReturn(false);
        when(h.dao.maxSeq("t1", "r-old")).thenReturn(1L);
        when(h.dao.listEvents(eq("t1"), eq("r-old"), anyLong(), anyInt())).thenReturn(List.of(
                new RunLedgerDao.EventRow(1, "e-1", "run.terminal", "{}", Instant.now().minus(1, ChronoUnit.HOURS))));

        var response = new MockHttpServletResponse();
        boolean streamed = h.service.stream(h.principal, "r-old", 0, response);
        String body = response.getContentAsString();

        assertTrue(streamed);
        assertEquals(200, response.getStatus(), () -> "body=" + body);
        assertTrue(body.contains("id: 1"), body);
        assertTrue(body.contains("event: run.terminal"), body);
        assertFalse(body.contains("CURSOR_EXPIRED"), body);
    }

    /** 游标（Last-Event-ID）原样进 DB 判定：afterSeq>0 的超期请求同样 410+快照。 */
    @Test
    void nonzeroCursorIsPassedToRetentionDecision() throws Exception {
        Harness h = harness();
        when(h.dao.minSeq("t1", "r-old")).thenReturn(1L);
        when(h.dao.retentionExpired("t1", "r-old", 1, 24)).thenReturn(true);
        // 判定被绕开时（变异/回归）该夹具快速回放 seq=2 并以 200 失败断言，不落入空闲等待
        when(h.dao.maxSeq("t1", "r-old")).thenReturn(2L);
        when(h.dao.listEvents(eq("t1"), eq("r-old"), anyLong(), anyInt())).thenReturn(List.of(
                new RunLedgerDao.EventRow(2, "e-2", "run.terminal", "{}", Instant.now().minus(25, ChronoUnit.HOURS))));

        var response = new MockHttpServletResponse();
        boolean streamed = h.service.stream(h.principal, "r-old", 1, response);
        String body = response.getContentAsString();

        assertFalse(streamed, () -> "保留期外回放必须以 410 终结，实际 body=" + body);
        assertEquals(410, response.getStatus(), () -> "保留期外回放必须以 410 终结，实际 body=" + body);
        assertTrue(body.contains("CURSOR_EXPIRED"), body);
        assertTrue(body.contains("\"lastSeq\":\"1\""), body);
        assertFalse(body.contains("id:"), body);
        verify(h.dao).retentionExpired("t1", "r-old", 1, 24);
        verify(h.bus, never()).subscribe(any(), any(), any());
    }
}
