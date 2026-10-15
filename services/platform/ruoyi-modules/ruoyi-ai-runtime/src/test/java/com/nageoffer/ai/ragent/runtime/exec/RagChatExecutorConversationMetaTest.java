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

package com.nageoffer.ai.ragent.runtime.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.ingest.EmbeddingGateway;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBinding;
import com.nageoffer.ai.ragent.runtime.config.RunConfigBindingPort;
import com.nageoffer.ai.ragent.runtime.config.RuntimeModelGatewayPort;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.port.ConversationContextPort;
import com.nageoffer.ai.ragent.runtime.port.ConversationHistoryPort;
import com.nageoffer.ai.ragent.runtime.port.ConversationTurnMeta;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ai.api.runtime.ChatPort;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F03 op3 写侧：模型步成功后，**模型绑定 / 逐消息用量 / 耗时**随 7 参重载交给会话历史端口。
 *
 * <p>本类钉的是"执行器把 fact 交给谁、交的是什么"，不是适配器落库（那由
 * {@code ConversationHistoryAdapterTest} 在 ai-rag 侧钉住）。两者合起来才构成
 * "读取面已声明的字段真的有写入面"的完整证据链。
 *
 * <p>负例同场：属性缺失（usage 不含 total_tokens / 非数值）必须保持 {@code null} ——
 * "没有记录"与"0 tokens"是两件事，不许伪造。
 */
@Tag("dev")
class RagChatExecutorConversationMetaTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";
    private static final String CONVERSATION = "conv-1";

    private DocumentPort documentPort;
    private EmbeddingGateway embeddingGateway;
    private RuntimeModelGatewayPort chatGateway;
    private AiResourceAuthorizationService authorization;
    private RunAccessService access;
    private ConversationHistoryPort historyPort;
    private RagChatExecutor executor;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @BeforeEach
    void setUp() throws Exception {
        documentPort = mock(DocumentPort.class);
        embeddingGateway = mock(EmbeddingGateway.class);
        chatGateway = mock(RuntimeModelGatewayPort.class);
        authorization = mock(AiResourceAuthorizationService.class);
        historyPort = mock(ConversationHistoryPort.class);

        PlatformFactsClient facts = mock(PlatformFactsClient.class);
        when(facts.currentFacts(anyString(), anyString(), anyString()))
                .thenReturn(new PlatformFactsClient.CurrentFacts(true, 1));
        when(authorization.currentAclVersion(anyString())).thenReturn(1);
        when(authorization.check(any(), anyString(), anyString()))
                .thenReturn(ResourceAuthorizationService.Verdict.GRANT);

        when(embeddingGateway.model()).thenReturn("emb-x");
        when(embeddingGateway.provider()).thenReturn("dashscope");
        when(embeddingGateway.embedBatchWithUsage(any()))
                .thenReturn(new EmbeddingGateway.EmbeddingResult(List.of(List.of(0.1f)), "emb-req", Map.of()));
        when(documentPort.matchesEmbeddingModel(any(), any(), anyString())).thenReturn(true);
        when(documentPort.searchPublished(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new DocumentPort.RetrievedChunk("doc1", "ver1", "chk-1", 0, "证据正文", 1, 2, 0.9)));
        when(documentPort.findDocument(TENANT, "doc1")).thenReturn(Optional.of(new DocumentPort.DocumentRow(
                "doc1", "kb-1", "文档", MEMBER, "ver1", null, null)));

        RunConfigBinding binding = new RunConfigBinding(TENANT, "run-1", "rag.chat", "rev-1", 1L,
                "deepseek", "deepseek-chat", "catalog-1", "sha256:p", "env:DEEPSEEK_API_KEY", "op-1",
                Instant.EPOCH, Map.of("temperature", 0.7));

        executor = new RagChatExecutor(documentPort, embeddingGateway, chatGateway,
                mock(EgressPolicy.class), mock(UsageLedgerService.class),
                provider(facts), provider(authorization), provider((P2FaultInjector) null),
                mock(JdbcTemplate.class), new ObjectMapper());

        // access / providerBoundary / runConfigBindingPort / conversationHistory 是 @Autowired 字段：
        // 测试用反射注入替身（与本包既有 RagChatNoKnowledgeBaseSemanticsTest 同一手法）。
        access = mock(RunAccessService.class);
        when(access.current(any(), any())).thenReturn(new ExecutionPrincipal(
                TENANT, USER, MEMBER, 1, 1, Set.of("kb.read"), "jti", "platform",
                Instant.now().getEpochSecond(), 9999999999L));
        inject("access", access);

        ProviderCallBoundary boundary = mock(ProviderCallBoundary.class);
        // RevocationGuard.Operation 是 final 类：不 mock 它，用真实例（close() 只回调 guard.release，
        // 用一个接口 mock 承接即可 —— 也不依赖 mockito 是否能 mock final）。
        when(boundary.enter(any())).thenReturn(new RevocationGuard.Operation(mock(RevocationGuard.class), "permit-1", "op-1"));
        inject("providerBoundary", boundary);

        inject("runConfigBindingPort", (RunConfigBindingPort) (tenantId, runId) -> binding);
        inject("conversationHistory", historyPort);
        // F16：本夹具 run 带 conversationId（历史写侧夹具），执行器在模型步会加载授权历史：
        // 读端口必须接线（空表即可——本类只判写侧 meta 传递，不判历史内容）。
        ConversationContextPort contextPort = mock(ConversationContextPort.class);
        when(contextPort.load(any(), any())).thenReturn(List.of());
        inject("conversationContext", contextPort);

        when(chatGateway.stream(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(3);
            onDelta.accept("hi");
            return new ChatPort.ChatResult("hi", "req-1", Map.of("total_tokens", 7), "stop");
        });
    }

    private void inject(String name, Object value) throws Exception {
        Field field = RagChatExecutor.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(executor, value);
    }

    private static RunRecord run() {
        return new RunRecord(TENANT, "run-1", MEMBER, USER, "rag.chat", "RUNNING", "hash", "key",
                "{\"text\":\"你好\",\"conversationId\":\"" + CONVERSATION + "\"}",
                "{\"maxTokens\":2000}", "p2-v1", 1, 1,
                "[{\"type\":\"knowledge_base\",\"id\":\"kb-1\"}]", 3L, 1L, 1, 7L,
                "worker-1", null, null, null, null, null, Instant.EPOCH, Instant.EPOCH, null);
    }

    @Test
    @DisplayName("F03 op3：模型步成功后，绑定模型名/usage tokens/耗时经附件感知重载（appendAttachedTurn）交给会话历史端口")
    void modelStepPassesBoundModelUsageAndDurationIntoTheHistoryPort() throws Exception {
        RunExecutionGuard guard = mock(RunExecutionGuard.class);
        when(guard.isCancelRequested()).thenReturn(false);
        when(guard.findStep(anyString())).thenReturn(Optional.empty());
        // 真执行 commitAtomic 的提交体（否则持久化分支根本不会跑，判据会变成"什么都没发生也绿"）
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(guard).commitAtomic(any());

        RunExecutor.Outcome outcome = executor.execute(new RunExecution(run(), guard));

        assertEquals("SUCCEEDED", outcome.status());

        ArgumentCaptor<ConversationTurnMeta> captor = ArgumentCaptor.forClass(ConversationTurnMeta.class);
        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> attachmentsCaptor =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(historyPort).appendAttachedTurn(eq(TENANT), eq(MEMBER), eq(USER), eq(CONVERSATION),
                eq("你好"), eq("hi"), captor.capture(), attachmentsCaptor.capture());
        ConversationTurnMeta meta = captor.getValue();
        assertEquals("deepseek-chat", meta.modelName(), "模型名来自 run 绑定事实（不是默认模型）");
        assertEquals(7, meta.totalTokens().intValue());
        assertNotNull(meta.durationMs());
        assertTrue(meta.durationMs() >= 0);
        // D7a 契约：无附件载荷原样透传 MissingNode/null——不得伪造数组
        assertTrue(attachmentsCaptor.getValue().isMissingNode() || attachmentsCaptor.getValue().isNull(),
                "无附件载荷不得伪造数组");
        // 老重载（6 参 / 7 参 appendTurn）在本次执行里不得被调用：补事实不允许丢
        verify(historyPort, never()).appendTurn(any(), any(), any(), any(), any(), any());
        verify(historyPort, never()).appendTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("F03 op3：usage 缺 total_tokens / 非数值 ⇒ null（不伪造 0）")
    void missingUsageStaysNullInsteadOfZero() {
        assertNull(RagChatExecutor.totalTokensOf(null));
        assertNull(RagChatExecutor.totalTokensOf(Map.of()));
        assertNull(RagChatExecutor.totalTokensOf(Map.of("total_tokens", "12")));
        assertEquals(12, RagChatExecutor.totalTokensOf(Map.of("total_tokens", 12)).intValue());
        assertEquals(12, RagChatExecutor.totalTokensOf(Map.of("total_tokens", 12L)).intValue());
    }
}
