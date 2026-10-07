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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogConflictException;
import com.nageoffer.ai.ragent.rag.service.RuntimeCatalogService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-06 判据：目录读写的 JDBC 口径（租户限定 / jsonb 解析 / 档位 write-once）。
 *
 * <p>用 mock JdbcTemplate 而不是真库：本卡的可执行窗口只允许定向模块测试，真机往返由 T8 执行
 * （见报告 NOT_RUN）。这里能钉死的是**SQL 形态与决策语义**：
 * <ol>
 *   <li>每条读 SQL 都带 {@code tenant_id = ?} 且参数就是调用方给的租户（跨租户读不可能发生）；</li>
 *   <li>没有已发布行返回 {@code null}（与"读失败"区分）；</li>
 *   <li>模型目录按 (provider, model) 去重且保留最近发布的那条；</li>
 *   <li>档位 write-once：首次插入成功、同内容幂等、不同内容拒绝且不重复写。</li>
 * </ol>
 */
@Tag("dev")
class JdbcRuntimeCatalogServiceTest {

    private static final String TENANT = "T1";
    private static final String REVISION = "rev-1";

    private JdbcTemplate jdbc;
    private JdbcRuntimeCatalogService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new JdbcRuntimeCatalogService(jdbc, txManager);
    }

    private static Map<String, Object> revisionRow(String revisionId, long revisionNo, String provider, String model) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("revision_id", revisionId);
        row.put("revision_no", revisionNo);
        row.put("state", "PUBLISHED");
        row.put("provider_id", provider);
        row.put("model_id", model);
        row.put("catalog_version", "cat-v1");
        row.put("params_hash", "hash-1");
        row.put("params_json", "{\"temperature\":0.7}");
        row.put("credential_ref", "env:deepseek");
        row.put("operator_id", "2101");
        row.put("published_at", Timestamp.from(Instant.parse("2026-10-07T00:00:00Z")));
        row.put("dimension", 1536);
        row.put("budget_units", 500L);
        return row;
    }

    @Test
    @DisplayName("当前权威版本：只读该租户 state=PUBLISHED 的最新行，并带回维度与限额")
    void currentRevisionReadsTheLatestPublishedRowForTheTenant() {
        when(jdbc.queryForList(contains("state = 'PUBLISHED'"), eq(TENANT)))
                .thenReturn(List.of(revisionRow(REVISION, 7L, "deepseek", "deepseek-chat")));

        RuntimeCatalogService.RevisionRow row = service.currentRevision(TENANT);

        assertThat(row).isNotNull();
        assertThat(row.tenantId()).isEqualTo(TENANT);
        assertThat(row.revisionId()).isEqualTo(REVISION);
        assertThat(row.revisionNo()).isEqualTo(7L);
        assertThat(row.dimension()).isEqualTo(1536);
        assertThat(row.budgetUnits()).isEqualTo(500L);
        assertThat(row.credentialRef()).isEqualTo("env:deepseek");
        assertThat(row.paramsJson()).contains("temperature");
    }

    @Test
    @DisplayName("没有已发布行返回 null（与读失败区分，由调用方 fail-closed）")
    void noPublishedRowIsNotAnError() {
        when(jdbc.queryForList(contains("state = 'PUBLISHED'"), eq(TENANT))).thenReturn(List.of());

        assertThat(service.currentRevision(TENANT)).isNull();
    }

    @Test
    @DisplayName("版本序列与模型目录都按租户参数读取，且 limit 原样下推")
    void revisionsAndModelsAreTenantScopedAndBounded() {
        when(jdbc.queryForList(contains("SELECT revision_id, revision_no, state"), eq(TENANT), anyInt()))
                .thenReturn(List.of(revisionRow("rev-2", 2L, "deepseek", "deepseek-chat")));
        when(jdbc.queryForList(contains("SELECT provider_id, model_id"), eq(TENANT), anyInt()))
                .thenReturn(List.of(revisionRow("rev-2", 2L, "deepseek", "deepseek-chat")));

        assertThat(service.revisions(TENANT, 5)).hasSize(1);
        assertThat(service.knownModels(TENANT, 5))
                .singleElement()
                .satisfies(model -> assertThat(model.providerId()).isEqualTo("deepseek"));

        ArgumentCaptor<Object> limit = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).queryForList(contains("SELECT revision_id, revision_no, state"), eq(TENANT), limit.capture());
        assertThat(limit.getValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("模型目录按 (provider, model) 去重并保留最近发布的那条")
    void knownModelsDeduplicatesKeepingTheLatest() {
        when(jdbc.queryForList(contains("SELECT provider_id, model_id"), eq(TENANT), anyInt()))
                .thenReturn(List.of(
                        revisionRow("rev-3", 3L, "deepseek", "deepseek-chat"),
                        revisionRow("rev-2", 2L, "deepseek", "deepseek-chat"),
                        revisionRow("rev-1", 1L, "ghost", "ghost-model")));

        List<RuntimeCatalogService.ModelRow> models = service.knownModels(TENANT, 10);

        assertThat(models).hasSize(2);
        assertThat(models.get(0).modelId()).isEqualTo("deepseek-chat");
        assertThat(models.get(0).catalogVersion()).isEqualTo("cat-v1");
        assertThat(models.get(1).providerId()).isEqualTo("ghost");
    }

    @Test
    @DisplayName("设置行与档位行的 jsonb 解析：键值映射、候选数组顺序保持")
    void settingsAndTiersParseJsonbColumns() {
        when(jdbc.queryForList(contains("FROM platform.ai_runtime_config_setting"), eq(TENANT), eq(REVISION)))
                .thenReturn(List.of(Map.of("setting_key", "rag.top_k", "setting_value", "8"),
                        Map.of("setting_key", "rag.flags", "setting_value", "{\"rerank\":true}")));
        when(jdbc.queryForList(contains("FROM platform.ai_runtime_config_tier"), eq(TENANT), eq(REVISION)))
                .thenReturn(List.of(Map.of(
                        "revision_id", REVISION,
                        "tier_code", "default",
                        "candidate_ids", "[\"deepseek-chat\",\"deepseek-reasoner\"]",
                        "failure_threshold", 2,
                        "open_duration_seconds", 30)));

        assertThat(service.settings(TENANT, REVISION))
                .containsEntry("rag.top_k", 8)
                .containsEntry("rag.flags", Map.of("rerank", true));
        assertThat(service.tiers(TENANT, REVISION)).singleElement().satisfies(tier -> {
            assertThat(tier.candidateIds()).containsExactly("deepseek-chat", "deepseek-reasoner");
            assertThat(tier.failureThreshold()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("首次附加档位：一次 INSERT，replayed=false")
    void firstTierAttachmentInsertsOnce() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(contains("FROM platform.ai_runtime_config_tier"), eq(TENANT), eq(REVISION)))
                .thenReturn(List.of(Map.of(
                        "revision_id", REVISION, "tier_code", "fast",
                        "candidate_ids", "[\"deepseek-chat\"]", "failure_threshold", 3,
                        "open_duration_seconds", 45)));

        RuntimeCatalogService.AttachResult result = service.attachTiers(TENANT, REVISION,
                List.of(new RuntimeCatalogService.TierSpec("fast", List.of("deepseek-chat"), 3, 45)), "2101");

        assertThat(result.replayed()).isFalse();
        assertThat(result.tiers()).singleElement()
                .satisfies(tier -> assertThat(tier.tierCode()).isEqualTo("fast"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("INSERT INTO platform.ai_runtime_config_tier");
        assertThat(sql.getValue()).contains("ON CONFLICT (tenant_id, revision_id, tier_code) DO NOTHING");
        assertThat(args.getValue()[0]).isEqualTo(TENANT);
        assertThat(args.getValue()[1]).isEqualTo(REVISION);
        assertThat(String.valueOf(args.getValue()[3])).isEqualTo("[\"deepseek-chat\"]");
    }

    @Test
    @DisplayName("同内容重复附加：不再重复写，按 replayed=true 回放")
    void identicalTierAttachmentReplaysWithoutWriting() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForList(contains("failure_threshold, open_duration_seconds FROM platform.ai_runtime_config_tier"),
                eq(TENANT), eq(REVISION), eq("fast")))
                .thenReturn(List.of(Map.of(
                        "candidate_ids", "[\"deepseek-chat\"]", "failure_threshold", 2, "open_duration_seconds", 30)));
        when(jdbc.queryForList(contains("FROM platform.ai_runtime_config_tier"), eq(TENANT), eq(REVISION)))
                .thenReturn(List.of(Map.of(
                        "revision_id", REVISION, "tier_code", "fast", "candidate_ids", "[\"deepseek-chat\"]",
                        "failure_threshold", 2, "open_duration_seconds", 30)));

        RuntimeCatalogService.AttachResult result = service.attachTiers(TENANT, REVISION,
                List.of(new RuntimeCatalogService.TierSpec("fast", List.of("deepseek-chat"), 2, 30)), "2101");

        assertThat(result.replayed()).isTrue();
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("不可变版本拒绝改档位：同版本不同内容 ⇒ 冲突，且不产生第二次写入")
    void changedTierFactsOnAnImmutableRevisionAreRefused() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForList(contains("failure_threshold, open_duration_seconds FROM platform.ai_runtime_config_tier"),
                eq(TENANT), eq(REVISION), eq("fast")))
                .thenReturn(List.of(Map.of(
                        "candidate_ids", "[\"deepseek-chat\"]", "failure_threshold", 5, "open_duration_seconds", 30)));

        assertThatThrownBy(() -> service.attachTiers(TENANT, REVISION,
                List.of(new RuntimeCatalogService.TierSpec("fast", List.of("deepseek-chat"), 2, 30)), "2101"))
                .isInstanceOf(RuntimeCatalogConflictException.class)
                .hasMessageContaining("immutable revision");
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("空档位集合直接拒绝（不静默成功）")
    void emptyTierListIsRefused() {
        assertThatThrownBy(() -> service.attachTiers(TENANT, REVISION, List.of(), "2101"))
                .isInstanceOf(RuntimeCatalogConflictException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
