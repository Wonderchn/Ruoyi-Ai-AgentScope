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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F22:2 真机第四缺口：消费/回查线程没有登录主体，事件必须携带租户并在 DB 访问前绑定。
 *
 * <p>真机现象（{@code w2/app-6044-f222-lr3.log:3303-3315}）：chunk 消费者已消费到消息，
 * 但 {@code PlusTenantLineHandler} 在 {@code ai_knowledge_document} 的第一次查询前以
 * {@code tenant.context.missing} 拒绝——事件里没有 tenantId，消费线程也没设动态租户。
 *
 * <p>本组用记录型 {@link TenantScopeBinder} 复现平台侧 {@code TenantHelper.dynamic} 的语义
 * （进入绑定、退出清理）：执行期间看到事件租户，之后线程干净；缺租户则显式拒绝且不触达服务。
 * 独立应用（无 binder）保持原直接执行形态。
 */
@Tag("dev")
class KnowledgeDocumentChunkTenantBindingTest {

    /** 与产品默认同义：legacy 已批准打开，边界开放。 */
    private static final SaasCapabilityBoundary OPEN_BOUNDARY = new SaasCapabilityBoundary(
            new SaasBoundaryProperties(true, true, new SaasBoundaryProperties.CustomerApi(true), true));

    /**
     * 记录型 binder：与 {@code RuoYiTenantScopeBinder#runWithTenant}
     * （{@code TenantHelper.dynamic(tenantId, handle)}）同语义。
     */
    private static TenantScopeBinder recordingBinder(AtomicReference<String> bound,
                                                     AtomicBoolean invoked) {
        return (tenantId, handle) -> {
            invoked.set(true);
            bound.set(tenantId);
            try {
                handle.run();
            } finally {
                bound.set(null);
            }
        };
    }

    @Test
    @DisplayName("事件序列化携带 tenantId（Jackson 往返）")
    void eventSerializationCarriesTenantId() throws Exception {
        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .docId("doc-1").kbId("kb-1").operator("operator").tenantId("000000").build();

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"tenantId\":\"000000\"");

        KnowledgeDocumentChunkEvent roundTrip = mapper.readValue(json, KnowledgeDocumentChunkEvent.class);
        assertThat(roundTrip.getTenantId()).isEqualTo("000000");
    }

    @Test
    @DisplayName("消费者：executeChunk 执行期间线程绑定事件租户，结束后清空")
    void consumerBindsEventTenantDuringExecutionAndClearsAfter() {
        AtomicReference<String> bound = new AtomicReference<>();
        AtomicBoolean binderInvoked = new AtomicBoolean();
        AtomicReference<String> seenByService = new AtomicReference<>();

        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        doAnswer(invocation -> {
            seenByService.set(bound.get());
            return null;
        }).when(documentService).executeChunk("doc-1");

        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(
                documentService, providerOf(OPEN_BOUNDARY), providerOf(recordingBinder(bound, binderInvoked)));

        consumer.onMessage(chunkMessage("doc-1", "000000"));

        assertThat(binderInvoked).isTrue();
        assertThat(seenByService)
                .as("扩展点执行期间必须能看到事件租户，否则租户行拦截器会在第一次 DB 操作前拒绝")
                .hasValue("000000");
        assertThat(bound)
                .as("执行结束后必须清理动态租户，避免污染消费线程池")
                .hasValue(null);
        verify(documentService).executeChunk("doc-1");
    }

    @Test
    @DisplayName("消费者：平台 binder 在场但事件缺 tenantId -> 显式拒绝，服务零调用")
    void consumerRejectsMissingTenantWhenPlatformBinderIsPresent() {
        AtomicReference<String> bound = new AtomicReference<>();
        AtomicBoolean binderInvoked = new AtomicBoolean();
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(
                documentService, providerOf(OPEN_BOUNDARY), providerOf(recordingBinder(bound, binderInvoked)));

        assertThatThrownBy(() -> consumer.onMessage(chunkMessage("doc-1", null)))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> consumer.onMessage(chunkMessage("doc-1", "   ")))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("tenantId");

        assertThat(binderInvoked).isFalse();
        verifyNoInteractions(documentService);
    }

    @Test
    @DisplayName("消费者：binder 缺席（独立应用）仍恢复身份引用后直接执行")
    void consumerKeepsStandalonePathWhenBinderIsAbsent() {
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(
                documentService, providerOf(OPEN_BOUNDARY), providerOf(null));

        consumer.onMessage(chunkMessage("doc-1", "000000"));

        verify(documentService).executeChunk("doc-1");
        assertThat(PrincipalContext.get()).isNull();
    }

    @Test
    @DisplayName("事件 JSON 往返携带 principalRef 全部字段；恢复主体为身份引用（scopes 空、issuer 保留）")
    void eventSerializationCarriesPrincipalReference() throws Exception {
        AsyncPrincipalReference reference = principalRef("000000", "1");
        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .docId("doc-1").kbId("kb-1").operator("operator")
                .tenantId("000000").principalRef(reference).build();

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"principalRef\"")
                .contains("\"tenantId\":\"000000\"")
                .contains("\"membershipId\":\"platform:000000:1\"")
                .contains("\"userId\":\"1\"")
                .contains("\"policyVersion\":1")
                .contains("\"aclVersion\":1")
                .contains("\"issuedAtEpochMilli\":123456789");

        KnowledgeDocumentChunkEvent roundTrip = mapper.readValue(json, KnowledgeDocumentChunkEvent.class);
        assertThat(roundTrip.getPrincipalRef()).isEqualTo(reference);

        ExecutionPrincipal restored = roundTrip.getPrincipalRef().toExecutionPrincipal();
        assertThat(restored.tenantId()).isEqualTo("000000");
        assertThat(restored.userId()).isEqualTo("1");
        assertThat(restored.membershipId()).isEqualTo(ExecutionPrincipal.canonicalMembershipId("000000", "1"));
        assertThat(restored.scopes()).as("身份引用恢复出的主体不带任何 scope").isEmpty();
        assertThat(restored.issuer()).isEqualTo(AsyncPrincipalReference.ASYNC_REF_ISSUER);
        assertThat(restored.jti()).startsWith("async-ref:");
    }

    @Test
    @DisplayName("消费者：executeChunk 期间 PrincipalContext 为事件主体，结束后清空/恢复先前主体")
    void consumerRestoresPrincipalDuringExecutionAndRestoresPreviousAfter() {
        AtomicReference<ExecutionPrincipal> seen = new AtomicReference<>();
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        doAnswer(invocation -> {
            seen.set(PrincipalContext.get());
            return null;
        }).when(documentService).executeChunk("doc-1");

        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(
                documentService, providerOf(OPEN_BOUNDARY),
                providerOf(recordingBinder(new AtomicReference<>(), new AtomicBoolean())));
        AsyncPrincipalReference reference = principalRef("000000", "1");

        try {
            // 无先前主体：执行期间是事件主体，结束后清空
            consumer.onMessage(chunkMessage("doc-1", "000000", reference));
            assertThat(seen.get()).isNotNull();
            assertThat(seen.get().tenantId()).isEqualTo("000000");
            assertThat(seen.get().userId()).isEqualTo("1");
            assertThat(seen.get().scopes()).isEmpty();
            assertThat(PrincipalContext.get()).isNull();

            // 已有主体：恢复而不是清空
            ExecutionPrincipal prior = principalRef("000009", "9").toExecutionPrincipal();
            PrincipalContext.set(prior);
            consumer.onMessage(chunkMessage("doc-1", "000000", reference));
            assertThat(PrincipalContext.get()).isSameAs(prior);
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    @DisplayName("消费者：缺 principalRef 显式拒绝，服务零调用")
    void consumerRejectsMissingPrincipalReference() {
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(
                documentService, providerOf(OPEN_BOUNDARY),
                providerOf(recordingBinder(new AtomicReference<>(), new AtomicBoolean())));

        assertThatThrownBy(() -> consumer.onMessage(chunkMessage("doc-1", "000000", null)))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("principalRef");

        verifyNoInteractions(documentService);
    }

    @Test
    @DisplayName("消费者：事件 tenantId 与 principalRef 租户不一致时显式拒绝，服务零调用")
    void consumerRejectsMismatchedPrincipalTenant() {
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(
                documentService, providerOf(OPEN_BOUNDARY),
                providerOf(recordingBinder(new AtomicReference<>(), new AtomicBoolean())));

        assertThatThrownBy(() -> consumer.onMessage(
                chunkMessage("doc-1", "000000", principalRef("000001", "1"))))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("不一致");

        verifyNoInteractions(documentService);
    }

    @Test
    @DisplayName("AsyncPrincipalReference 不能被外部 JSON 伪造成非法主体（紧凑构造器校验沿用）")
    void asyncPrincipalReferenceRejectsForgedJson() {
        ObjectMapper mapper = new ObjectMapper();
        String wrongMembership = "{\"tenantId\":\"000000\","
                + "\"membershipId\":\"platform:000000:2\",\"userId\":\"1\","
                + "\"policyVersion\":1,\"aclVersion\":1,\"issuedAtEpochMilli\":1}";
        assertThatThrownBy(() -> mapper.readValue(wrongMembership, AsyncPrincipalReference.class))
                .hasRootCauseInstanceOf(ClientException.class);

        String zeroPolicy = "{\"tenantId\":\"000000\","
                + "\"membershipId\":\"platform:000000:1\",\"userId\":\"1\","
                + "\"policyVersion\":0,\"aclVersion\":1,\"issuedAtEpochMilli\":1}";
        assertThatThrownBy(() -> mapper.readValue(zeroPolicy, AsyncPrincipalReference.class))
                .hasRootCauseInstanceOf(ClientException.class);

        assertThatThrownBy(() -> AsyncPrincipalReference.from(null))
                .as("不存在“从无中生出引用”")
                .isInstanceOf(ClientException.class);
    }

    @Test
    @DisplayName("分块回查器：documentMapper 查询期间绑定事件租户，结束后清空")
    void chunkCheckerBindsTenantAroundDocumentLookup() {
        AtomicReference<String> bound = new AtomicReference<>();
        AtomicReference<String> seenByMapper = new AtomicReference<>();
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentDO running = new KnowledgeDocumentDO();
        running.setStatus(DocumentStatus.RUNNING.getCode());
        when(documentMapper.selectById("doc-1")).thenAnswer(invocation -> {
            seenByMapper.set(bound.get());
            return running;
        });

        KnowledgeDocumentChunkTransactionChecker checker =
                new KnowledgeDocumentChunkTransactionChecker(documentMapper, new DelegatingTransactionListener());
        ReflectionTestUtils.setField(checker, "capabilityBoundary", OPEN_BOUNDARY);
        ReflectionTestUtils.setField(checker, "tenantScopeBinder", recordingBinder(bound, new AtomicBoolean()));

        assertThat(checker.check(chunkMessage("doc-1", "000000"))).isTrue();
        assertThat(seenByMapper).hasValue("000000");
        assertThat(bound).hasValue(null);
    }

    @Test
    @DisplayName("分块回查器：缺 tenantId 在查询前拒绝，mapper 零调用")
    void chunkCheckerRejectsMissingTenantBeforeQuery() {
        AtomicBoolean binderInvoked = new AtomicBoolean();
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentChunkTransactionChecker checker =
                new KnowledgeDocumentChunkTransactionChecker(documentMapper, new DelegatingTransactionListener());
        ReflectionTestUtils.setField(checker, "capabilityBoundary", OPEN_BOUNDARY);
        ReflectionTestUtils.setField(checker, "tenantScopeBinder",
                recordingBinder(new AtomicReference<>(), binderInvoked));

        assertThatThrownBy(() -> checker.check(chunkMessage("doc-1", null)))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("tenantId");

        assertThat(binderInvoked).isFalse();
        verifyNoInteractions(documentMapper);
    }

    @Test
    @DisplayName("清理回查器：knowledgeBaseMapper 查询期间绑定事件租户，结束后清空")
    void cleanupCheckerBindsTenantAroundKnowledgeBaseLookup() {
        AtomicReference<String> bound = new AtomicReference<>();
        AtomicReference<String> seenByMapper = new AtomicReference<>();
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        when(knowledgeBaseMapper.selectById("kb-1")).thenAnswer(invocation -> {
            seenByMapper.set(bound.get());
            return null;
        });

        KnowledgeBaseCleanupTransactionChecker checker =
                new KnowledgeBaseCleanupTransactionChecker(knowledgeBaseMapper, new DelegatingTransactionListener());
        ReflectionTestUtils.setField(checker, "capabilityBoundary", OPEN_BOUNDARY);
        ReflectionTestUtils.setField(checker, "tenantScopeBinder", recordingBinder(bound, new AtomicBoolean()));

        assertThat(checker.check(cleanupMessage("kb-1", "000000"))).isTrue();
        assertThat(seenByMapper).hasValue("000000");
        assertThat(bound).hasValue(null);
    }

    private static MessageWrapper<KnowledgeDocumentChunkEvent> chunkMessage(String docId, String tenantId) {
        return chunkMessage(docId, tenantId, principalRef("000000", "1"));
    }

    private static MessageWrapper<KnowledgeDocumentChunkEvent> chunkMessage(
            String docId, String tenantId, AsyncPrincipalReference principalRef) {
        return MessageWrapper.<KnowledgeDocumentChunkEvent>builder()
                .keys("doc:" + docId)
                .body(KnowledgeDocumentChunkEvent.builder()
                        .docId(docId).operator("operator").tenantId(tenantId)
                        .principalRef(principalRef).build())
                .build();
    }

    /** 受理线程主体 → 异步身份引用（与 startChunk 的生产路径同形）。 */
    private static AsyncPrincipalReference principalRef(String tenantId, String userId) {
        ExecutionPrincipal principal = new ExecutionPrincipal(
                tenantId, userId, ExecutionPrincipal.canonicalMembershipId(tenantId, userId),
                1, 1, Set.of(), "jti-test", "platform:local", 1_000L, 2_000L);
        return AsyncPrincipalReference.of(principal, 123_456_789L);
    }

    private static MessageWrapper<KnowledgeBaseCleanupEvent> cleanupMessage(String kbId, String tenantId) {
        return MessageWrapper.<KnowledgeBaseCleanupEvent>builder()
                .keys("kb:" + kbId)
                .body(KnowledgeBaseCleanupEvent.builder()
                        .kbId(kbId).tenantId(tenantId).collectionName("kb_" + kbId).build())
                .build();
    }

    /** 最小 {@link ObjectProvider}：命中时返回固定实例，未命中返回 {@code null}。 */
    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
