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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * F04：能力保留键 {@code attachmentModalities=["image"]} 必须作为**不可变 revision 参数**的
 * 一等形态被发布侧与绑定读侧严格校验（默认拒图 ⇒ 没有该键就不声明图片能力）。
 *
 * <p>红基线针对旧树：旧实现只校验数字采样参数，保留键既没有发布侧形态门，
 * 绑定读侧也原样放行非法形态 ⇒ 本类断言全部失败（行为红，不是编译红）。
 * 通过判定不是"能读到"，而是：
 * <ol>
 *   <li><b>发布侧第二道门</b>：非法形态在**任何写入之前**拒绝（update 零调用）；</li>
 *   <li><b>绑定读侧 fail-closed</b>：库里出现的非法形态明确拒绝，不降级为空能力、
 *       也不当成"没声明"悄悄继续；</li>
 *   <li><b>合法形态原样随绑定事实带出</b>，供执行期能力判定消费（同一条 params 事实）。</li>
 * </ol>
 */
@Tag("dev")
class AttachmentModalitiesRevisionTest {

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

    private static ConfigRevisionPublisher.ConfigRevisionCommand command(String paramsJson) {
        return new ConfigRevisionPublisher.ConfigRevisionCommand(
                "deepseek", "deepseek-chat", "cat-v1", "params-1", paramsJson, "cred-ref-1", 1536);
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

    private Map<String, Object> revisionRow(String revisionId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("revision_id", revisionId);
        row.put("revision_no", 3L);
        row.put("provider_id", "deepseek");
        row.put("model_id", "deepseek-chat");
        row.put("catalog_version", "cat-v1");
        row.put("params_hash", "params-1");
        row.put("credential_ref", "cred-ref-1");
        row.put("operator_id", "1001");
        row.put("published_at", Timestamp.from(Instant.EPOCH));
        row.put("dimension", 1536);
        return row;
    }

    @Test
    @DisplayName("发布侧：非法 attachmentModalities 形态在任何写入之前拒绝（update 零调用）")
    void malformedReservedKeyIsRefusedBeforeAnyWrite() {
        stubPreviousPublished();
        var refused = assertThatThrownBy(
                () -> publisher.publish(command("{\"attachmentModalities\":[\"video\"]}")));
        refused.isInstanceOf(ConfigAuthorityUnavailable.class)
                .hasMessageContaining("attachmentModalities");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("发布侧：重复/非字符串/空数组/非数组形态逐条拒绝")
    void everyMalformedShapeIsRefused() {
        for (String bad : List.of(
                "{\"attachmentModalities\":[\"image\",\"image\"]}",
                "{\"attachmentModalities\":[1]}",
                "{\"attachmentModalities\":\"image\"}",
                "{\"attachmentModalities\":[]}",
                "{\"attachmentModalities\":{\"image\":true}}")) {
            init();
            stubPreviousPublished();
            assertThatThrownBy(() -> publisher.publish(command(bad)))
                    .as(bad)
                    .isInstanceOf(ConfigAuthorityUnavailable.class);
            verify(jdbc, never()).update(anyString(), any(Object[].class));
        }
    }

    @Test
    @DisplayName("发布侧：合法 [\"image\"] 与其他采样参数共存时正常发布（保留键不是第二配置面）")
    void declaredImagePublishesAsAnOrdinaryImmutableRevision() {
        stubPreviousPublished();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(contains("WHERE tenant_id = ? AND revision_id = ?"), eq(TENANT), anyString()))
                .thenReturn(List.of(revisionRow("rev-img")));

        ConfigRevisionFacts facts = publisher.publish(
                command("{\"attachmentModalities\":[\"image\"],\"temperature\":0.7}"));

        assertThat(facts.revisionId()).isEqualTo("rev-img");
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("绑定读侧：非法形态 fail-closed（拒绝而不是降级为空能力）")
    void boundRevisionWithMalformedReservedKeyFailsClosed() {
        for (String bad : List.of(
                "{\"attachmentModalities\":[\"video\"]}",
                "{\"attachmentModalities\":\"image\"}",
                "{\"attachmentModalities\":[\"image\",\"image\"]}")) {
            jdbc = mock(JdbcTemplate.class);
            Map<String, Object> row = row("rev-bad");
            row.put("revision_params_json", bad);
            when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
            var port = new JdbcRunConfigBindingPort(jdbc);
            assertThatThrownBy(() -> port.requireBoundRevision(TENANT, "run-bad"))
                    .as(bad)
                    .isInstanceOf(ConfigAuthorityUnavailable.class)
                    .hasMessageContaining("attachmentModalities");
        }
    }

    @Test
    @DisplayName("绑定读侧：合法 [\"image\"] 原样随绑定事实带出（执行期读的就是这条）")
    void boundRevisionCarriesDeclaredImageModalities() {
        jdbc = mock(JdbcTemplate.class);
        Map<String, Object> row = row("rev-ok");
        row.put("revision_params_json", "{\"attachmentModalities\":[\"image\"]}");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        RunConfigBinding binding = new JdbcRunConfigBindingPort(jdbc).requireBoundRevision(TENANT, "run-ok");

        assertThat(binding.params()).containsEntry("attachmentModalities", List.of("image"));
    }

    private static Map<String, Object> row(String revisionId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("action", "rag.chat");
        row.put("config_revision_id", revisionId);
        row.put("run_catalog_version", "cat-v1");
        row.put("run_params_hash", "params-1");
        row.put("run_provider_id", "deepseek");
        row.put("run_model_id", "deepseek-chat");
        row.put("config_operator", "operator-1");
        row.put("config_published_at", Timestamp.from(Instant.parse("2026-10-06T00:00:00Z")));
        row.put("revision_no", 3L);
        row.put("revision_state", "PUBLISHED");
        row.put("revision_provider_id", "deepseek");
        row.put("revision_model_id", "deepseek-chat");
        row.put("revision_catalog_version", "cat-v1");
        row.put("revision_params_hash", "params-1");
        row.put("credential_ref", "env:DEEPSEEK_API_KEY");
        row.put("revision_operator", "operator-1");
        row.put("revision_published_at", Timestamp.from(Instant.parse("2026-10-06T00:00:00Z")));
        return row;
    }
}
