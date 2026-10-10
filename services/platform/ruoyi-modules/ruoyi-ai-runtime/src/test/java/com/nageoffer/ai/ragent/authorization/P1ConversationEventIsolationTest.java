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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.api.Tag;

/**
 * P1.3d 护栏：会话/run/事件读取的租户成员作用域。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li><b>SQL 文本</b>：三条读路径的租户/成员条件恒在、事件按 (tenant, run, seq)
 *       且不提供裸 eventId 查询、run 仓储无任何写方法；</li>
 *   <li><b>行为</b>：跨租户 run 查询返回 empty（他租户行在复合条件下取不到，
 *       与不存在同外显）；事件分页参数按 (tenant, run, afterSeq, limit) 顺序绑定；</li>
 *   <li><b>边界</b>：分页越界/缺 scope 一律 ClientException，不发 SQL。</li>
 * </ol>
 */
@Tag("dev")
class P1ConversationEventIsolationTest {

    // ---------------------------------------------------------------- SQL 文本

    private static Path locate(String... segments) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path p = dir.resolve("services").resolve("platform").resolve("ruoyi-modules")
                    .resolve("ruoyi-ai-runtime").resolve("src")
                    .resolve("main").resolve("java").resolve("com").resolve("nageoffer")
                    .resolve("ai").resolve("ragent").resolve("authorization");
            for (String s : segments) {
                p = p.resolve(s);
            }
            if (Files.isRegularFile(p)) {
                return p;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + String.join("/", segments));
    }

    private static String source(String file) throws IOException {
        return Files.readString(locate(file), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("会话读：全部查询恒带 tenant_id + member_id，跨租户与不存在同外显")
    void conversationSqlIsTenantMemberScoped() throws IOException {
        String src = source("TenantConversationReadRepository.java");
        long tenantConditions = src.split("tenant_id = \\? AND member_id = \\?", -1).length - 1;
        assertThat(tenantConditions)
                .as("三条查询（会话/消息/计数）都必须带 (tenant, member) 复合条件")
                .isGreaterThanOrEqualTo(3);
        assertThat(src).contains("AND deleted = 0");
    }

    @Test
    @DisplayName("run 读：按 (tenant_id, run_id) 复合定位，且没有任何写方法")
    void runSqlIsCompositeAndReadOnly() throws IOException {
        String src = source("TenantRunReadRepository.java");
        assertThat(src).contains("WHERE tenant_id = ? AND run_id = ?");
        assertThat(src).doesNotContain("INSERT").doesNotContain("UPDATE").doesNotContain("DELETE");
    }

    @Test
    @DisplayName("事件读：按 (tenant, run, seq) 升序分页；不存在裸 eventId 定位")
    void eventSqlRequiresRunScope() throws IOException {
        String src = source("TenantEventReadRepository.java");
        assertThat(src).contains("WHERE tenant_id = ? AND run_id = ? AND seq > ?");
        assertThat(src).contains("ORDER BY seq ASC");
        // 裸 eventId/裸 seq 查询不存在：主键含租户，事件不能凭单个 id 被取走
        assertThat(src.replace("run_id", "")).doesNotContain("WHERE tenant_id = ? AND seq");
    }

    // ---------------------------------------------------------------- 行为

    @Test
    @DisplayName("跨租户 run：查询返回 empty，与不存在同外显（不抛差异化的错误）")
    void crossTenantRunIsIndistinguishableFromAbsent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // varargs 逐项匹配：Mockito 5 对 varargs 展开记录，逐元素 matcher 是唯一稳妥形式
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of());
        TenantRunReadRepository repo = new TenantRunReadRepository(jdbc);

        assertThat(repo.findRun("T2", "run-t1-1")).isEmpty();
    }

    @Test
    @DisplayName("事件分页：(tenant, run, afterSeq, limit) 按序绑定，limit 上界 200")
    void eventPagingBindsScopeFirst() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(List.of());
        TenantEventReadRepository repo = new TenantEventReadRepository(jdbc);

        repo.listEvents("T1", "run-t1-1", 5L, 50);

        verify(jdbc).query(anyString(), any(RowMapper.class),
                eq("T1"), eq("run-t1-1"), eq(5L), eq(50));
    }

    @Test
    @DisplayName("边界拒绝：坏分页/缺 scope 一律 ClientException（零 SQL）")
    void pagingBoundsAndScopeAreEnforced() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantEventReadRepository events = new TenantEventReadRepository(jdbc);
        TenantConversationReadRepository conversations = new TenantConversationReadRepository(jdbc);

        assertThatThrownBy(() -> events.listEvents("T1", "run-1", 0, 201))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> events.listEvents("", "run-1", 0, 10))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> conversations.listMessages("T1", "platform:T1:1", "1", "conv-1", -1, 10))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> conversations.findConversation("T1", "platform:T1:1", null))
                .isInstanceOf(ClientException.class);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }
}
