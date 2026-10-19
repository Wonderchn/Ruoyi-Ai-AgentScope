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

package com.nageoffer.ai.ragent.flow.runtime;

import com.nageoffer.ai.ragent.runtime.RunApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F13:2 投影仓储：varchar(32) 映射、可执行谓词、owner-only 谓词、软删不物理删。
 */
@Tag("dev")
class FlowRuntimeRepositoryTest {

    private static final String TENANT = "T1";
    private static final long USER = 2101L;
    private static final String WORKFLOW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String RUNTIME = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("runId -> runtime.uuid：剥掉 r- 前缀后恰 32 字符（V9 varchar(32)）")
    void runtimeUuidIsThe32CharacterSuffixRequiredByVarchar32() {
        String runId = "r-" + RUNTIME;
        assertThat(FlowRuntimeRepository.runtimeUuidOf(runId)).isEqualTo(RUNTIME).hasSize(32);
        assertThat(FlowRuntimeRepository.runIdOf(RUNTIME)).isEqualTo(runId);
        assertThatThrownBy(() -> FlowRuntimeRepository.runtimeUuidOf("r-short"))
                .isInstanceOf(RunApiException.class)
                .hasMessageContaining("runtime uuid");
        assertThatThrownBy(() -> FlowRuntimeRepository.runtimeUuidOf(null))
                .isInstanceOf(RunApiException.class);
    }

    @Test
    @DisplayName("可执行谓词：tenant + 未删 + 启用 + (本人或公开)")
    void executableGraphUsesFrozenPredicate() {
        RecordingJdbc jdbc = new RecordingJdbc(sql -> {
            if (sql.contains("FROM ai_flow_workflow")) {
                return List.of(41L);
            }
            if (sql.contains("FROM ai_flow_node")) {
                return List.of(new FlowRuntimeRepository.NodeSource(11L, "n1", "s", 9L, "{}", null));
            }
            if (sql.contains("FROM ai_flow_edge")) {
                return List.of(new FlowRuntimeRepository.EdgeSource("n1", "n2"));
            }
            if (sql.contains("FROM ai_flow_component")) {
                return List.of("Start");
            }
            return List.of();
        });

        Optional<FlowRuntimeRepository.GraphSource> source =
                new FlowRuntimeRepository(jdbc).findExecutableGraph(TENANT, WORKFLOW, USER);

        assertThat(source).isPresent();
        assertThat(source.get().workflowId()).isEqualTo(41L);
        assertThat(source.get().componentNames()).containsEntry(9L, "Start");
        String workflowSql = jdbc.sqls.get(0);
        assertThat(workflowSql)
                .contains("tenant_id=?")
                .contains("uuid=?")
                .contains("is_deleted=0")
                .contains("is_enable=1")
                .contains("user_id=?")
                .contains("is_public=1");
        for (String sql : jdbc.sqls) {
            if (sql.contains("FROM ai_flow_node") || sql.contains("FROM ai_flow_edge")) {
                assertThat(sql).contains("tenant_id=?").contains("is_deleted=0");
            }
        }
    }

    @Test
    @DisplayName("owner-only 列表/详情/节点：谓词含 tenant + user_id + is_deleted=0")
    void ownerOnlyQueriesKeepBothBoundaryPredicates() {
        RecordingJdbc jdbc = new RecordingJdbc(sql -> List.of());

        new FlowRuntimeRepository(jdbc).listRuntimes(TENANT, USER, WORKFLOW, 10, 0);
        new FlowRuntimeRepository(jdbc).countRuntimes(TENANT, USER, WORKFLOW);
        new FlowRuntimeRepository(jdbc).findRuntimeView(TENANT, USER, RUNTIME);
        new FlowRuntimeRepository(jdbc).listRuntimeNodes(TENANT, USER, RUNTIME);

        assertThat(jdbc.sqls).hasSizeGreaterThanOrEqualTo(4);
        for (String sql : jdbc.sqls) {
            assertThat(sql)
                    .contains("tenant_id=?")
                    .contains("user_id=?")
                    .contains("is_deleted=0");
        }
        assertThat(jdbc.sqls.get(0)).contains("ORDER BY").containsIgnoringCase("update_time");
    }

    @Test
    @DisplayName("软删：UPDATE is_deleted=1，绝不 DELETE FROM（不物理删）")
    void softDeleteIsLogicalNotPhysical() {
        RecordingJdbc jdbc = new RecordingJdbc(sql -> List.of());
        jdbc.updateResult = 1;

        boolean deleted = new FlowRuntimeRepository(jdbc).softDeleteRuntime(TENANT, USER, RUNTIME);

        assertThat(deleted).isTrue();
        assertThat(jdbc.sqls).anySatisfy(sql -> assertThat(sql)
                .contains("UPDATE ai_flow_runtime")
                .contains("is_deleted=1"));
        assertThat(jdbc.sqls).allSatisfy(sql -> assertThat(sql).doesNotContain("DELETE FROM ai_flow_runtime"));
    }

    @Test
    @DisplayName("清理：按工作流软删，0 行是幂等成功（不抛）")
    void clearIsIdempotentSoftDelete() {
        RecordingJdbc jdbc = new RecordingJdbc(sql -> List.of());
        jdbc.updateResult = 0;

        int cleared = new FlowRuntimeRepository(jdbc).softDeleteByWorkflow(TENANT, USER, WORKFLOW);

        assertThat(cleared).isZero();
        assertThat(jdbc.sqls).anySatisfy(sql -> assertThat(sql)
                .contains("UPDATE ai_flow_runtime")
                .contains("is_deleted=1"));
    }

    @Test
    @DisplayName("runtime 行映射包含 input/output 文本与状态备注")
    void runtimeRowMapsAllProjectionFields() {
        RecordingJdbc jdbc = new RecordingJdbc(sql -> List.of(new FlowRuntimeRepository.RuntimeRow(
                7L, RUNTIME, USER, 41L, "{\"graph\":{}}", "{\"output\":{\"value\":\"x\"}}", 3, "", false)));

        var row = new FlowRuntimeRepository(jdbc).findRuntime(TENANT, RUNTIME).orElseThrow();

        assertThat(row.id()).isEqualTo(7L);
        assertThat(row.workflowId()).isEqualTo(41L);
        assertThat(row.status()).isEqualTo(3);
        assertThat(row.inputJson()).contains("\"graph\"");
    }

    // ------------------------------------------------------------------ fixture

    /** 记录 SQL 的 JdbcTemplate 替身：按 SQL 片段返回预置行，不触真实 DataSource。 */
    static final class RecordingJdbc extends JdbcTemplate {

        private final Function<String, List<?>> responder;
        final List<String> sqls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        int updateResult = 1;

        RecordingJdbc(Function<String, List<?>> responder) {
            this.responder = responder;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... queryArgs) {
            sqls.add(sql);
            args.add(queryArgs);
            return (List<T>) responder.apply(sql);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... queryArgs) {
            sqls.add(sql);
            args.add(queryArgs);
            List<?> rows = responder.apply(sql);
            if (rows.isEmpty()) {
                return null;
            }
            if (requiredType == Long.class) {
                return (T) Long.valueOf(((Number) rows.get(0)).longValue());
            }
            return (T) rows.get(0);
        }

        @Override
        public int update(String sql, Object... updateArgs) {
            sqls.add(sql);
            args.add(updateArgs);
            return updateResult;
        }
    }
}
