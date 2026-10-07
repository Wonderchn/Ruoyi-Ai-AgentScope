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

package com.nageoffer.ai.ragent.runtime.config;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-040 A2/A6 的**发布侧**判据（@Tag("dev")，门禁 15）。
 *
 * <ol>
 *   <li><b>A2 第一道门</b>：发布命令 dimension=1535 ⇒ 在**任何写入之前**拒绝
 *       （update 零调用 = DB 零增量；第二道门是 V24 的 CHECK，非本类覆盖面）；</li>
 *   <li><b>发布审计同事务</b>：发布产生恰好 2 条 INSERT（版本行 + audit 行），
 *       且 audit 的 diff **不含凭据引用值**（密钥只存引用/掩码，K3）；</li>
 *   <li><b>撤权审计</b>：撤权成功也必须写 audit；命中 0 行（缺失/已撤）⇒ 拒绝；</li>
 *   <li><b>无主体</b> ⇒ 全部拒绝（fail-closed，不落到"随便哪个租户"）。</li>
 * </ol>
 */
@Tag("dev")
class JdbcConfigRevisionPublisherTest {

    private static final String TENANT = "t1";

    private JdbcTemplate jdbc;
    private JdbcConfigRevisionPublisher publisher;

    @BeforeEach
    void init() {
        jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        publisher = new JdbcConfigRevisionPublisher(jdbc, txManager);
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "1001", "platform:t1:1001", 1, 1,
                java.util.Set.of("ai:config:write"), "jti", "platform", 1, 9999999999L));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    private static ConfigRevisionPublisher.ConfigRevisionCommand command(int dimension) {
        return new ConfigRevisionPublisher.ConfigRevisionCommand(
                " deepseek ", "deepseek-chat", "cat-v1", "params-1",
                "{\"temperature\":0.7}", "cred-ref-1", dimension);
    }

    private void stubPreviousPublished() {
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("provider_id", "deepseek");
        previous.put("model_id", "deepseek-chat");
        previous.put("catalog_version", "cat-v0");
        previous.put("params_hash", "params-0");
        previous.put("dimension", 1536);
        previous.put("credential_ref", "cred-ref-0");
        when(jdbc.queryForList(contains("FROM platform.ai_runtime_config_revision"), eq(TENANT)))
                .thenReturn(List.of(previous));
        when(jdbc.queryForObject(contains("max(revision_no)"), eq(Long.class), eq(TENANT))).thenReturn(2L);
    }

    private Map<String, Object> revisionRow(String revisionId, long revisionNo, int dimension) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("revision_id", revisionId);
        row.put("revision_no", revisionNo);
        row.put("provider_id", "deepseek");
        row.put("model_id", "deepseek-chat");
        row.put("catalog_version", "cat-v1");
        row.put("params_hash", "params-1");
        row.put("credential_ref", "cred-ref-1");
        row.put("operator_id", "1001");
        row.put("published_at", Timestamp.from(Instant.EPOCH));
        row.put("dimension", dimension);
        return row;
    }

    @Test
    @DisplayName("A2：dimension=1535 的发布在任何写入之前被拒（update 零调用 = DB 零增量）")
    void publishWithWrongDimensionIsRefusedBeforeAnyWrite() {
        stubPreviousPublished();
        var refused = assertThrows(ConfigAuthorityUnavailable.class,
                () -> publisher.publish(command(1535)));
        assertTrue(refused.getMessage().contains("1535"), refused.getMessage());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("发布成功：恰好 2 条 INSERT（版本行 + audit 行），diff 不含凭据引用值")
    void successfulPublishWritesRevisionAndAuditWithoutCredentialValues() {
        stubPreviousPublished();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(contains("WHERE tenant_id = ? AND revision_id = ?"), eq(TENANT), anyString()))
                .thenReturn(List.of(revisionRow("rev-new", 3L, 1536)));

        ConfigRevisionFacts facts = publisher.publish(command(1536));

        assertEquals("rev-new", facts.revisionId());
        assertEquals(1536, facts.dimension());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(sqlCaptor.capture(), argsCaptor.capture());

        String auditSql = sqlCaptor.getAllValues().get(1);
        assertTrue(auditSql.contains("ai_runtime_config_revision_audit"),
                "the audit row must be written in the same transaction");
        String diff = String.valueOf(argsCaptor.getAllValues().get(1)[4]);
        assertTrue(diff.contains("\"dimension\":1536"), diff);
        assertFalse(diff.contains("cred-ref-1"), "diff must not carry the credential ref value");
    }

    @Test
    @DisplayName("首发布：租户还没有 PUBLISHED 行时合法（diff 视为相对空版本），不与读失败混同")
    void firstPublishSucceedsWithoutAPreviousRevision() {
        when(jdbc.queryForList(contains("FROM platform.ai_runtime_config_revision"), eq(TENANT)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(contains("max(revision_no)"), eq(Long.class), eq(TENANT))).thenReturn(0L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(contains("WHERE tenant_id = ? AND revision_id = ?"), eq(TENANT), anyString()))
                .thenReturn(List.of(revisionRow("rev-first", 1L, 1536)));

        ConfigRevisionFacts facts = publisher.publish(command(1536));

        assertEquals("rev-first", facts.revisionId());
        assertEquals(1L, facts.revisionNo());
    }

    @Test
    @DisplayName("撤权：命中 1 行时写 audit；命中 0 行（缺失/已撤）拒绝且不写 audit")
    void revokeIsAuditedAndMissingRevisionIsRefused() {
        when(jdbc.update(contains("state = 'REVOKED'"), eq(TENANT), eq("rev-1"))).thenReturn(1);
        publisher.revoke("rev-1", null);
        verify(jdbc).update(contains("ai_runtime_config_revision_audit"),
                eq(TENANT), anyString(), eq("rev-1"), eq("1001"), anyString());

        when(jdbc.update(contains("state = 'REVOKED'"), eq(TENANT), eq("rev-missing"))).thenReturn(0);
        var refused = assertThrows(ConfigAuthorityUnavailable.class,
                () -> publisher.revoke("rev-missing", null));
        assertTrue(refused.getMessage().contains("missing or already revoked"), refused.getMessage());
    }

    @Test
    @DisplayName("无主体 ⇒ 发布/撤权/读取全部拒绝（fail-closed）")
    void everyOperationRequiresAPrincipal() {
        PrincipalContext.clear();
        assertThrows(ConfigAuthorityUnavailable.class, () -> publisher.publish(command(1536)));
        assertThrows(ConfigAuthorityUnavailable.class, () -> publisher.revoke("rev-1", null));
        assertThrows(ConfigAuthorityUnavailable.class, () -> publisher.require("rev-1"));
    }
}
