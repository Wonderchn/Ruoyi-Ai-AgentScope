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

package com.nageoffer.ai.ragent.runtime.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.ingest.EmbeddingGateway;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.exec.ProviderCallBoundary;
import com.nageoffer.ai.ragent.runtime.exec.RagChatExecutor;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutor;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code rag.chat} 在"<b>没有知识库</b>"时的真实语义判据（RW-01 §3）。
 *
 * <p><b>为什么必须有这一条。</b>F03 的"普通聊天"在本仓库里有<b>两条完全不同的路径</b>，
 * 机械地把一条的语义套到另一条上会得到错误的契约：
 * <ul>
 *   <li>{@code rag.chat}（{@code POST /api/ai/v1/runs} → {@link RagChatExecutor}）是
 *       <b>知识库问答</b>：它先解析 {@code resourceRefs} 里的知识库、逐个做 {@code kb.read}
 *       授权判定，<b>授权集合为空就不检索、不 embedding、不调用模型</b>，直接以
 *       {@code NO_AUTHORIZED_SCOPE} 终止；</li>
 *   <li>{@code agent.run} 是 Agent（ReAct）执行，需要 agentVersion 与 P3 装配，
 *       与"有没有知识库"无关。</li>
 * </ul>
 *
 * <p>因此"没有知识库的普通聊天"<b>不是</b>"降级成通用 LLM 闲聊"，而是<b>明确失败</b>。
 * 这正是 AGENTS.md 的"检索授权集合为空时不能扩大为全库查询"：空集合既不能扩成全库，
 * 也不能扩成"无检索直连模型"。前端不得把 {@code NO_AUTHORIZED_SCOPE} 当成"再试一次就好"。
 *
 * <p>本判据同时钉住"0 次外发"：授权为空时 embedding 与模型网关<b>都没有被调用</b>——
 * 这是成本与数据外发边界，不是实现细节。
 */
@Tag("dev")
class RagChatNoKnowledgeBaseSemanticsTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";

    private org.ruoyi.ai.api.runtime.DocumentPort documentPort;
    private EmbeddingGateway embeddingGateway;
    private RuntimeModelGatewayPort chatGateway;
    private AiResourceAuthorizationService authorization;
    private RunAccessService access;
    private RagChatExecutor executor;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @BeforeEach
    void setUp() throws Exception {
        documentPort = mock(org.ruoyi.ai.api.runtime.DocumentPort.class);
        embeddingGateway = mock(EmbeddingGateway.class);
        chatGateway = mock(RuntimeModelGatewayPort.class);
        authorization = mock(AiResourceAuthorizationService.class);
        PlatformFactsClient facts = mock(PlatformFactsClient.class);
        when(facts.currentFacts(anyString(), anyString(), anyString()))
                .thenReturn(new PlatformFactsClient.CurrentFacts(true, 1));
        when(authorization.currentAclVersion(anyString())).thenReturn(1);

        executor = new RagChatExecutor(documentPort, embeddingGateway, chatGateway,
                mock(EgressPolicy.class), mock(UsageLedgerService.class),
                provider(facts), provider(authorization), provider((P2FaultInjector) null),
                mock(JdbcTemplate.class), new ObjectMapper());

        // access / providerBoundary 是 @Autowired 字段（不是构造参数）：测试用反射注入替身，
        // 这是本类唯一"绕过容器"的地方，只为把 execute() 的判定链真正跑起来。
        access = mock(RunAccessService.class);
        when(access.current(any(), any())).thenReturn(new com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal(
                TENANT, USER, MEMBER, 1, 1, Set.of("kb.read"), "jti", "platform",
                Instant.now().getEpochSecond(), 9999999999L));
        inject("access", access);
        inject("providerBoundary", mock(ProviderCallBoundary.class));
    }

    private void inject(String name, Object value) throws Exception {
        Field field = RagChatExecutor.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(executor, value);
    }

    private static RunRecord run(String resourceRefsJson, String inputJson) {
        return new RunRecord(TENANT, "run-1", MEMBER, USER, "rag.chat", "RUNNING", "hash", "key",
                inputJson, "{\"maxTokens\":2000}", "p2-v1", 1, 1, resourceRefsJson, 3L, 1L, 1, 7L,
                "worker-1", null, null, null, null, null, Instant.EPOCH, Instant.EPOCH, null);
    }

    private RunExecutor.Outcome execute(String resourceRefsJson) throws Exception {
        RunExecutionGuard guard = mock(RunExecutionGuard.class);
        return executor.execute(new RunExecution(run(resourceRefsJson, "{\"text\":\"你好\"}"), guard));
    }

    @Test
    @DisplayName("无知识库（resourceRefs=[]）⇒ FAILED/NO_AUTHORIZED_SCOPE，且 0 次 embedding、0 次模型外发")
    void emptyResourceRefsIsExplicitlyRefused() throws Exception {
        RunExecutor.Outcome outcome = execute("[]");

        assertEquals("FAILED", outcome.status());
        assertEquals("NO_AUTHORIZED_SCOPE", outcome.errorCode(),
                "无知识库的 rag.chat 必须明确失败，不得降级为'无检索直连模型'的通用闲聊");
        verifyNoInteractions(embeddingGateway);
        verifyNoInteractions(chatGateway);
        verify(documentPort, never()).searchPublished(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("空 resourceRefs 与全为空白的 refs 同语义：不扩成全库、不扩成无检索")
    void missingResourceRefsNodeIsExplicitlyRefused() throws Exception {
        for (String refs : new String[] {null, "", "  ", "[]"}) {
            RunExecutor.Outcome outcome = execute(refs);
            assertEquals("NO_AUTHORIZED_SCOPE", outcome.errorCode(), "refs=" + refs);
        }
        verifyNoInteractions(embeddingGateway);
        verifyNoInteractions(chatGateway);
    }

    @Test
    @DisplayName("请求了知识库但一条都没被授权 ⇒ 同样是 NO_AUTHORIZED_SCOPE（空授权不扩全库）")
    void requestedButUnauthorizedKbIsRefused() throws Exception {
        when(authorization.check(any(), anyString(), anyString()))
                .thenReturn(ResourceAuthorizationService.Verdict.DENY);

        RunExecutor.Outcome outcome = execute("[{\"type\":\"knowledge_base\",\"id\":\"kb-1\"}]");

        assertEquals("FAILED", outcome.status());
        assertEquals("NO_AUTHORIZED_SCOPE", outcome.errorCode());
        verifyNoInteractions(embeddingGateway);
        verifyNoInteractions(chatGateway);
        // 未授权的 ref 不得被当成"没请求"而回落到全库检索
        verify(documentPort, never()).searchPublished(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("来源未知（授权事实源缺席）⇒ AUTHORIZATION_UNAVAILABLE，绝不当作'没有知识库'放行")
    void authorizationSourceMissingIsRefused() throws Exception {
        RagChatExecutor bare = new RagChatExecutor(documentPort, embeddingGateway, chatGateway,
                mock(EgressPolicy.class), mock(UsageLedgerService.class),
                provider((PlatformFactsClient) null), provider((AiResourceAuthorizationService) null),
                provider((P2FaultInjector) null), mock(JdbcTemplate.class), new ObjectMapper());
        Field field = RagChatExecutor.class.getDeclaredField("access");
        field.setAccessible(true);
        field.set(bare, access);

        RunExecutionGuard guard = mock(RunExecutionGuard.class);
        RunExecutor.Outcome outcome = bare.execute(
                new RunExecution(run("[]", "{\"text\":\"你好\"}"), guard));

        assertEquals("FAILED", outcome.status());
        assertEquals("AUTHORIZATION_UNAVAILABLE", outcome.errorCode());
        verifyNoInteractions(chatGateway);
    }
}
