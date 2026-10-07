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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.web.ConversationBatchDeleteController.BatchDeleteRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.isA;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /internal/ai/v1/conversations/batch-delete} 的真实 MVC 契约判据（F03 / D05）。
 *
 * <p>本测试用 <b>真实</b> {@link ConversationBatchDeleteService} + 真实 Spring MVC
 * （{@code standaloneSetup}：真实 RequestMapping / 真实 Jackson 序列化 / 真实 @ExceptionHandler），
 * 只把授权事实源、仓储、屏障与 JDBC 换成替身。因此它检验的是"客户端真正看到的 HTTP 契约"：
 * 方法、路径、请求体字段、<b>整数</b> code 包络、HTTP status == body.code、符号码放
 * {@code data.errorCode}，以及 D05 要求的全部负例。
 *
 * <p>网关（{@code /api/ai/v1}）一侧的路由登记属 T0 集成面（{@code AiGatewayController.ROUTES} 禁写），
 * 见 RW-01 报告的共享文件补丁；本测试覆盖的是内层 handler 的真实契约。
 */
@Tag("dev")
class ConversationBatchDeleteHttpTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";
    private static final String PATH = "/internal/ai/v1/conversations/batch-delete";

    private AiResourceAuthorizationService authorization;
    private TenantConversationReadRepository conversations;
    private RevocationGuard guard;
    private NamedParameterJdbcTemplate jdbc;
    private MockMvc mvc;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RevocationGuard> provider(RevocationGuard guard) {
        ObjectProvider<RevocationGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        return provider;
    }

    private static final TransactionOperations TRANSACTIONS = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    @BeforeEach
    void setUp() {
        authorization = mock(AiResourceAuthorizationService.class);
        conversations = mock(TenantConversationReadRepository.class);
        guard = mock(RevocationGuard.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        ConversationBatchDeleteService service = new ConversationBatchDeleteService(
                authorization, conversations, provider(guard), jdbc, TRANSACTIONS);
        mvc = MockMvcBuilders.standaloneSetup(new ConversationBatchDeleteController(service)).build();
        principal(1, 1);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private static void principal(int policyVersion, int aclVersion) {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, MEMBER, policyVersion, aclVersion,
                Set.of(ConversationBatchDeleteService.DELETE_ACTION), "jti", "platform",
                java.time.Instant.now().getEpochSecond(), 9999999999L));
    }

    private void visible(String... ids) {
        for (String id : ids) {
            when(conversations.findConversation(TENANT, MEMBER, id))
                    .thenReturn(Optional.of(new TenantConversationReadRepository.ConversationRow(
                            id, "t", new Timestamp(0L))));
        }
    }

    private void granted(String... ids) {
        Map<String, ResourceAuthorizationService.Verdict> verdicts = new LinkedHashMap<>();
        for (String id : ids) {
            verdicts.put("conv:" + id, ResourceAuthorizationService.Verdict.GRANT);
        }
        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(verdicts);
    }

    private void permitGranted() {
        when(guard.acquire(any())).thenAnswer(invocation -> new RevocationGuard.PermitGrant("permit-1", 1));
    }

    private String body(String... ids) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new BatchDeleteRequest(List.of(ids)));
    }

    // ------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("成功：200 + 整数 code=200 + data.deletedCount/permitCount；permitCount 恒为 1")
    void successEnvelope() throws Exception {
        visible("c-1", "c-2");
        granted("c-1", "c-2");
        permitGranted();
        when(jdbc.update(anyString(), any(Map.class))).thenReturn(2);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1", "c-2")))
                .andExpect(status().isOk())
                // 整数 code：网关强制"单 JSON 对象 + 整数 code == HTTP 状态"，字符串 code 会被判成缺包络
                .andExpect(jsonPath("$.code", isA(Integer.class)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deletedCount").value(2))
                .andExpect(jsonPath("$.data.permitCount").value(1));
    }

    @Test
    @DisplayName("成功：请求体只有 conversationIds —— 不接收任何租户/成员/用户字段")
    void requestBodyAcceptsOnlyIds() throws Exception {
        visible("c-1");
        granted("c-1");
        permitGranted();
        when(jdbc.update(anyString(), any(Map.class))).thenReturn(1);

        // 伪造的归属字段必须被忽略（不是"被信任后改写归属"）：期望仍以 PrincipalContext 的租户/成员写入
        String forged = "{\"conversationIds\":[\"c-1\"],\"tenantId\":\"t2\",\"userId\":\"9999\",\"memberId\":\"platform:t2:9999\"}";
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount").value(1));
        org.mockito.Mockito.verify(conversations).findConversation(TENANT, MEMBER, "c-1");
    }

    @Test
    @DisplayName("空请求体 / 缺 conversationIds ⇒ 400（不得被当成\"删 0 条\"静默成功）")
    void blankBodyRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data.errorCode").value("BAD_REQUEST"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"conversationIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("BAD_REQUEST"));
    }

    // ------------------------------------------------------------ 负例

    @Test
    @DisplayName("无身份 ⇒ 401 AUTH_REQUIRED，且不触达授权与数据库")
    void missingPrincipal() throws Exception {
        PrincipalContext.clear();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data.errorCode").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.data.retryable").value(false));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
        org.mockito.Mockito.verify(guard, org.mockito.Mockito.never()).acquire(any());
    }

    @Test
    @DisplayName("无 scope / 功能级拒绝 ⇒ 403 FORBIDDEN（不是 500）")
    void missingScopeOrFunctionDenied() throws Exception {
        doAnswer(invocation -> {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }).when(authorization).requireFunction(any(), anyString(), anyString());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data.errorCode").value("FORBIDDEN"));
        org.mockito.Mockito.verify(guard, org.mockito.Mockito.never()).acquire(any());
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("跨租户/越权（任一资源 DENY）⇒ 404，且 0 permit、0 写入（无部分成功）")
    void crossTenantDenied() throws Exception {
        Map<String, ResourceAuthorizationService.Verdict> verdicts = new LinkedHashMap<>();
        verdicts.put("conv:c-1", ResourceAuthorizationService.Verdict.GRANT);
        verdicts.put("conv:c-2", ResourceAuthorizationService.Verdict.DENY);
        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(verdicts);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1", "c-2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data.errorCode").value("RESOURCE_NOT_FOUND_OR_FORBIDDEN"));
        org.mockito.Mockito.verify(guard, org.mockito.Mockito.never()).acquire(any());
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("跨租户（资源不可见）⇒ 404，且 0 permit、0 写入")
    void crossTenantInvisible() throws Exception {
        visible("c-1");
        granted("c-1", "c-2");
        when(conversations.findConversation(TENANT, MEMBER, "c-2")).thenReturn(Optional.empty());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1", "c-2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("RESOURCE_NOT_FOUND_OR_FORBIDDEN"));
        org.mockito.Mockito.verify(guard, org.mockito.Mockito.never()).acquire(any());
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("撤权（判定 STALE）⇒ 409 VERSION_CONFLICT；UNKNOWN ⇒ 503 AUTHORIZATION_UNAVAILABLE")
    void revocationAndUnknown() throws Exception {
        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(
                Map.of("conv:c-1", ResourceAuthorizationService.Verdict.STALE));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.errorCode").value("VERSION_CONFLICT"));

        when(authorization.checkBatch(any(), anyString(), any())).thenReturn(
                Map.of("conv:c-1", ResourceAuthorizationService.Verdict.UNKNOWN));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.errorCode").value("AUTHORIZATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.retryable").value(true));
    }

    @Test
    @DisplayName("版本冲突（permit 后 epoch 变化）⇒ 409，且不写库、permit 已释放")
    void versionConflictAfterPermit() throws Exception {
        visible("c-1");
        granted("c-1");
        doAnswer(invocation -> {
            principal(2, 1);
            return new RevocationGuard.PermitGrant("permit-x", 1);
        }).when(guard).acquire(any());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.errorCode").value("VERSION_CONFLICT"));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
        org.mockito.Mockito.verify(guard).release(anyString(), anyString());
    }

    @Test
    @DisplayName("重复 ID ⇒ 400（整体拒绝，不去重）")
    void duplicateIdsRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("c-1", "c-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("BAD_REQUEST"));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("超过上限（101）⇒ 400（拒绝，不截断）")
    void overLimitRejected() throws Exception {
        String[] ids = new String[ConversationBatchDeleteService.MAX_BATCH + 1];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "c-" + i;
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(ids)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("BAD_REQUEST"));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }
}
