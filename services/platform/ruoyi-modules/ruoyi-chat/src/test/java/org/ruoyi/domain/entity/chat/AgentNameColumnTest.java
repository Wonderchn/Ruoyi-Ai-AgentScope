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

package org.ruoyi.domain.entity.chat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.ruoyi.domain.entity.agent.Agent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-039B：{@code ai_agent_profile} 的**两个名称列**与平台写入路径。
 *
 * <p>WP-039A 的孪生列护栏在**源码扫描层**抓到了 {@code Agent.agentName} 落到旧列
 * （{@code agent_name}）而不是统一列 {@code name}，并已修复；但那次修复
 * **没有在真实 PostgreSQL 上验证过行为**（行为层只由会话表的同构判据间接证明）。
 * 本判据补上这一环：把"为什么这条映射是错的"用真库事实钉死。
 *
 * <p>与 {@link ConversationTitleColumnTest} 同构：统一列 {@code name} 是
 * {@code VARCHAR(64) NOT NULL} 且**无默认值**，旧列 {@code agent_name}（V9 追加）被保留。
 * 于是"字段名按驼峰约定落到旧列"的后果有两条：新增智能体插入失败；改名只改旧列，
 * 而 AI 侧 {@code AgentProfileDO} 读的是 {@code name}。
 *
 * <p>真库部分需要 {@code -Dragent.conversation.test.jdbc-url=...}；未提供时显式跳过。
 */
@Tag("dev")
class AgentNameColumnTest {

    static final String URL_PROPERTY = "ragent.conversation.test.jdbc-url";

    @Test
    @DisplayName("平台实体的智能体名称必须显式映射到统一列 name")
    void platformEntityMapsNameToTheUnifiedColumn() throws Exception {
        Field field = Agent.class.getDeclaredField("agentName");
        TableField mapping = field.getAnnotation(TableField.class);

        assertThat(mapping)
                .as("agentName 必须显式标注 @TableField(\"name\")。不标注时按驼峰约定落到 agent_name"
                        + "（V9 保留的旧平台列），而 AI 侧 AgentProfileDO 与统一读路径都用 name")
                .isNotNull();
        assertThat(mapping.value()).isEqualTo("name");
        assertThat(Agent.class.getAnnotation(TableName.class).value())
                .as("锚点：实体确实映射到统一智能体表")
                .isEqualTo("ai_agent_profile");
    }

    @Test
    @DisplayName("真库：不写 name 的插入必然失败（name 是 NOT NULL 且无默认值）")
    @EnabledIfSystemProperty(named = URL_PROPERTY, matches = ".+",
            disabledReason = "需要隔离 PostgreSQL：-D" + URL_PROPERTY + "=jdbc:postgresql://host:port/db")
    void insertingWithoutNameFails() throws IOException {
        JdbcTemplate jdbc = prepare();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO platform.ai_agent_profile (id, agent_name, agent_show, agent_describe,"
                        + " create_time, update_time, deleted, tenant_id)"
                        + " VALUES (?,?,?,?, now(), now(), 0, ?)",
                "a-no-name", "只有平台列", "1", "描述", "T-AGENT"))
                .as("name 是 VARCHAR(64) NOT NULL 且无默认值：省略它必然违反非空约束")
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("真库：改 agent_name 不会移动 name —— AI 侧读的是 name，所以改名不可见")
    @EnabledIfSystemProperty(named = URL_PROPERTY, matches = ".+",
            disabledReason = "需要隔离 PostgreSQL：-D" + URL_PROPERTY + "=jdbc:postgresql://host:port/db")
    void updatingAgentNameLeavesTheUnifiedNameUnchanged() throws IOException {
        JdbcTemplate jdbc = prepare();
        jdbc.update("INSERT INTO platform.ai_agent_profile (id, name, agent_name, create_time,"
                        + " update_time, deleted, tenant_id) VALUES (?,?,?, now(), now(), 0, ?)",
                "a-1", "原名称", "原名称", "T-AGENT");

        jdbc.update("UPDATE platform.ai_agent_profile SET agent_name = ? WHERE id = ?", "平台改的", "a-1");
        assertThat(jdbc.queryForObject(
                "SELECT agent_name FROM platform.ai_agent_profile WHERE id='a-1'", String.class))
                .as("旧列确实改了").isEqualTo("平台改的");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM platform.ai_agent_profile WHERE id='a-1'", String.class))
                .as("但 AI 侧与统一读路径用的 name 没动 —— 这就是'改名不可见'的原因")
                .isEqualTo("原名称");

        jdbc.update("UPDATE platform.ai_agent_profile SET name = ? WHERE id = ?", "统一列改的", "a-1");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM platform.ai_agent_profile WHERE id='a-1'", String.class))
                .as("改 name 才是 AI 侧看得见的那一列").isEqualTo("统一列改的");
    }

    // ------------------------------------------------------------------ helpers

    private static JdbcTemplate prepare() throws IOException {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).isNotBlank();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS platform");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_agent_profile CASCADE");
        for (String ddl : FrozenTableDdl.ddlOf("ai_agent_profile")) {
            jdbc.execute(ddl);
        }
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_agent_profile'", String.class))
                .as("从冻结迁移抽出的形状必须同时含两个名称列")
                .contains("name", "agent_name");
        assertThat(jdbc.queryForMap(
                "SELECT is_nullable, column_default FROM information_schema.columns WHERE"
                        + " table_schema='platform' AND table_name='ai_agent_profile' AND column_name='name'"))
                .as("name 是 NOT NULL 且无默认值——这正是省略它的插入会失败的原因")
                .containsEntry("is_nullable", "NO");
        return jdbc;
    }
}
