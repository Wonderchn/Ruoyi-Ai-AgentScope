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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C1.2：run 级配置版本绑定的**可实证判据**。
 *
 * <p>T0 的验收要求原话是"已受理 run 固定原版本要有**可实证的判据**（两条 run 绑不同 revision，
 * 各自解析到各自那份）"。因此本类的核心不是"能查到"，而是：
 * <ol>
 *   <li><b>两个 run 各自解析到各自那份</b> —— 同一个租户、不同 run、不同 revision，
 *       结果必须分别是各自绑定的版本，而不是都返回"当前最新"；</li>
 *   <li><b>五种拒绝路径逐条钉住</b> —— run 不存在 / 未绑定 / 版本缺失 / 已撤权 / 事实不一致，
 *       每一种都必须抛 {@link ConfigAuthorityUnavailable}，**不得**回退到当前版本、不得回退 YAML。</li>
 * </ol>
 * 只测第 1 条是不够的：一个"取不到就返回最新版本"的实现能通过第 1 条（因为最新版本正好是其中之一），
 * 却正是 C1.2 要禁止的那种实现。
 */
@Tag("dev")
class RunConfigBindingPortTest {

    private static final String TENANT = "000000";

    private JdbcTemplate jdbc;
    private JdbcRunConfigBindingPort port;

    @SuppressWarnings("unchecked")
    private void given(List<Map<String, Object>> rows) {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
        port = new JdbcRunConfigBindingPort(jdbc);
    }

    /** 库行形态：run 的绑定列 + JOIN 出来的版本行（LEFT JOIN 未命中时版本侧为 null）。 */
    private static Map<String, Object> row(String action, String revisionId, long revisionNo,
                                           String providerId, String modelId, String catalogVersion,
                                           String paramsHash, String state, String credentialRef) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("action", action);
        row.put("config_revision_id", revisionId);
        row.put("run_catalog_version", catalogVersion);
        row.put("run_params_hash", paramsHash);
        row.put("run_provider_id", providerId);
        row.put("run_model_id", modelId);
        row.put("config_operator", "operator-1");
        row.put("config_published_at", Timestamp.from(Instant.parse("2026-10-06T00:00:00Z")));
        row.put("revision_no", revisionId == null ? null : revisionNo);
        row.put("revision_state", revisionId == null ? null : state);
        row.put("revision_provider_id", revisionId == null ? null : providerId);
        row.put("revision_model_id", revisionId == null ? null : modelId);
        row.put("revision_catalog_version", revisionId == null ? null : catalogVersion);
        row.put("revision_params_hash", revisionId == null ? null : paramsHash);
        row.put("credential_ref", revisionId == null ? null : credentialRef);
        row.put("revision_operator", revisionId == null ? null : "operator-1");
        row.put("revision_published_at", revisionId == null ? null
                : Timestamp.from(Instant.parse("2026-10-06T00:00:00Z")));
        return row;
    }

    @Test
    @DisplayName("C1.2 核心：两个 run 绑不同 revision，各自解析到各自那份（不是都取最新）")
    void twoRunsBoundToDifferentRevisionsResolveIndependently() {
        // 关键点在"两条查询各自返回自己那一行"：判据于是真的在测"按 run 解析"，
        // 而不是在测"能不能查到一个版本"。
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("chat", "rev-1", 1, "bailian", "qwen-max", "catalog-1", "sha256:p1",
                        "PUBLISHED", "env:DASHSCOPE_API_KEY")))
                .thenReturn(List.of(row("chat", "rev-2", 2, "deepseek", "deepseek-chat", "catalog-2",
                        "sha256:p2", "PUBLISHED", "env:DEEPSEEK_API_KEY")));
        port = new JdbcRunConfigBindingPort(jdbc);

        RunConfigBinding older = port.requireBoundRevision(TENANT, "run-old");
        RunConfigBinding newer = port.requireBoundRevision(TENANT, "run-new");

        assertThat(older.revisionId()).isEqualTo("rev-1");
        assertThat(older.modelId()).isEqualTo("qwen-max");
        assertThat(older.providerId()).isEqualTo("bailian");
        assertThat(older.revisionNo()).isEqualTo(1);

        assertThat(newer.revisionId()).isEqualTo("rev-2");
        assertThat(newer.modelId()).isEqualTo("deepseek-chat");
        assertThat(newer.providerId()).isEqualTo("deepseek");
        assertThat(newer.revisionNo()).isEqualTo(2);

        assertThat(older.modelId())
                .as("已受理 run 必须固定原版本：新发布 rev-2 之后，run-old 仍解析到 rev-1 的事实")
                .isNotEqualTo(newer.modelId());
    }

    @Test
    @DisplayName("单条解析带齐 C1.3 全部可追溯字段，且密钥只给引用/掩码")
    void bindingCarriesTraceableFactsWithoutSecretMaterial() {
        given(List.of(row("chat", "rev-9", 9, "bailian", "qwen-max", "catalog-9", "sha256:p9",
                "PUBLISHED", "env:DASHSCOPE_API_KEY")));

        RunConfigBinding binding = port.requireBoundRevision(TENANT, "run-9");

        assertThat(binding.tenantId()).isEqualTo(TENANT);
        assertThat(binding.runId()).isEqualTo("run-9");
        assertThat(binding.action()).isEqualTo("chat");
        assertThat(binding.revisionId()).isEqualTo("rev-9");
        assertThat(binding.revisionNo()).isEqualTo(9);
        assertThat(binding.catalogVersion()).isEqualTo("catalog-9");
        assertThat(binding.paramsHash()).isEqualTo("sha256:p9");
        assertThat(binding.operatorId()).isEqualTo("operator-1");
        assertThat(binding.publishedAt()).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
        assertThat(binding.credentialRef())
                .as("C1.3：密钥只存引用/掩码")
                .isEqualTo("env:DASHSCOPE_API_KEY");
    }

    @Test
    @DisplayName("run 不存在 ⇒ 拒绝")
    void unknownRunIsRejected() {
        given(List.of());
        assertThatThrownBy(() -> port.requireBoundRevision(TENANT, "missing"))
                .isInstanceOf(ConfigAuthorityUnavailable.class)
                .hasMessageContaining("run not found");
    }

    @Test
    @DisplayName("C1.2 第 3 行：run 未绑定版本 ⇒ 明确拒绝，**不复用当前版本顶替**")
    void runWithoutBoundRevisionIsRejectedInsteadOfFallingBackToCurrent() {
        given(List.of(row("chat", null, 0, null, null, null, null, null, null)));
        assertThatThrownBy(() -> port.requireBoundRevision(TENANT, "legacy-run"))
                .isInstanceOf(ConfigAuthorityUnavailable.class)
                .hasMessageContaining("no bound config revision");
    }

    @Test
    @DisplayName("C1.2 第 3 行：绑定版本在库中缺失 ⇒ 明确拒绝，不拿当前版本顶（LEFT JOIN 未命中）")
    void missingBoundRevisionIsRejected() {
        Map<String, Object> dangling = row("chat", "rev-gone", 0, null, null, null, null, null, null);
        // JOIN 未命中的真实形态：run 侧仍有绑定记录（config_revision_id 非空、run 侧事实齐全），
        // 而**版本侧每一列都是 null**（这里必须显式清掉，否则造出的是"版本行存在但 state 为 null"
        // 的另一种成因，判据就测错了对象 —— 本测试第一版正是这样失败的）。
        dangling.put("run_provider_id", "bailian");
        dangling.put("run_model_id", "qwen-max");
        dangling.put("run_catalog_version", "catalog-1");
        dangling.put("run_params_hash", "sha256:p1");
        for (String versionSide : List.of("revision_no", "revision_state", "revision_provider_id",
                "revision_model_id", "revision_catalog_version", "revision_params_hash",
                "credential_ref", "revision_operator", "revision_published_at")) {
            dangling.put(versionSide, null);
        }
        given(List.of(dangling));

        assertThatThrownBy(() -> port.requireBoundRevision(TENANT, "run-dangling"))
                .isInstanceOf(ConfigAuthorityUnavailable.class)
                .hasMessageContaining("bound config revision missing");
    }

    @Test
    @DisplayName("C1.3：绑定版本已撤权 ⇒ 拒绝（撤权立即生效，不受 run 固定版本保护）")
    void revokedBoundRevisionIsRejected() {
        given(List.of(row("chat", "rev-revoked", 3, "bailian", "qwen-max", "catalog-3", "sha256:p3",
                "REVOKED", "env:DASHSCOPE_API_KEY")));

        assertThatThrownBy(() -> port.requireBoundRevision(TENANT, "run-3"))
                .isInstanceOf(ConfigAuthorityUnavailable.class)
                .hasMessageContaining("REVOKED");
    }

    @Test
    @DisplayName("run 记录的事实与版本行不一致 ⇒ 拒绝，不挑一个信")
    void divergentBindingFactsAreRejected() {
        Map<String, Object> divergent = row("chat", "rev-4", 4, "bailian", "qwen-max", "catalog-4",
                "sha256:p4", "PUBLISHED", "env:DASHSCOPE_API_KEY");
        divergent.put("run_model_id", "tampered-model");
        given(List.of(divergent));

        assertThatThrownBy(() -> port.requireBoundRevision(TENANT, "run-4"))
                .isInstanceOf(ConfigAuthorityUnavailable.class)
                .hasMessageContaining("model_id");
    }

    @Test
    @DisplayName("数据库读失败 ⇒ 拒绝（不得回退 YAML/当前版本）")
    void databaseFailureIsRejected() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        port = new JdbcRunConfigBindingPort(jdbc);

        assertThatThrownBy(() -> port.requireBoundRevision(TENANT, "run-db-down"))
                .isInstanceOf(ConfigAuthorityUnavailable.class)
                .hasMessageContaining("read failed");
    }

    @Test
    @DisplayName("缺 tenantId/runId ⇒ 拒绝（不做无租户限定的解析）")
    void missingIdentityIsRejected() {
        given(List.of(row("chat", "rev-1", 1, "bailian", "qwen-max", "catalog-1", "sha256:p1",
                "PUBLISHED", "env:DASHSCOPE_API_KEY")));

        assertThatThrownBy(() -> port.requireBoundRevision(null, "run-1"))
                .isInstanceOf(ConfigAuthorityUnavailable.class);
        assertThatThrownBy(() -> port.requireBoundRevision(TENANT, "  "))
                .isInstanceOf(ConfigAuthorityUnavailable.class);
    }
}
