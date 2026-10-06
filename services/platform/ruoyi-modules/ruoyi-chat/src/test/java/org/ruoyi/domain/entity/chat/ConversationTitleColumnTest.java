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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-038A：{@code ai_conversation} 的**两个标题列**与平台写入路径。
 *
 * <p>背景：WP-024 把旧 旧平台会话表的 {@code session_title} 映射到统一表的 {@code title}（identity 别名），
 * 并**保留** {@code session_title} 作为 V9 追加列（"平台侧独有列，来自旧平台会话表（已并入 ai_conversation）"）。
 * 于是同一张表上有两个标题列：
 *
 * <ul>
 *   <li>{@code title}（V7，{@code VARCHAR(128) NOT NULL}，**无默认值**）——统一读路径
 *       （{@code TenantConversationReadRepository}）与 AI 资源面都用它；</li>
 *   <li>{@code session_title}（V9 追加，{@code varchar(255)}）——旧平台列。</li>
 * </ul>
 *
 * <p>而平台实体 {@link ChatSession} 的字段叫 {@code sessionTitle}，按 MyBatis-Plus 的驼峰约定
 * 会落到 {@code session_title}。后果是**两个用户可见的问题**，本判据把它们钉住：
 * <ol>
 *   <li>平台侧新增会话（{@code POST /system/session}）从不写 {@code title}，而它是 NOT NULL 且无默认值
 *       → 插入直接失败；</li>
 *   <li>平台侧重命名（{@code PUT /system/session}，正是工作台实际调用的那条）只改 {@code session_title}，
 *       而工作台自己的会话列表与 AI 资源面读的是 {@code title} → 改名"看起来没生效"。</li>
 * </ol>
 *
 * <p>真库部分需要 {@code -Dragent.conversation.test.jdbc-url=...}；未提供时显式跳过。
 * 映射断言不需要数据库，始终执行。
 */
@Tag("dev")
class ConversationTitleColumnTest {

    static final String URL_PROPERTY = "ragent.conversation.test.jdbc-url";

    private static final String TENANT = "T-TITLE";
    private static final String MEMBER = "platform:T-TITLE:31";

    // ------------------------------------------------------------------ 映射层（无需数据库）

    @Test
    @DisplayName("平台实体的会话标题必须显式映射到统一读路径用的 title 列")
    void platformEntityMapsTitleToTheUnifiedColumn() throws Exception {
        Field field = ChatSession.class.getDeclaredField("sessionTitle");
        TableField mapping = field.getAnnotation(TableField.class);

        assertThat(mapping)
                .as("sessionTitle 必须显式标注 @TableField(\"title\")。"
                        + "不标注时 MyBatis-Plus 按驼峰约定落到 session_title——"
                        + "那是 V9 保留的旧平台列，而统一读路径（TenantConversationReadRepository）"
                        + "与工作台会话列表都读 title，于是改名会写到一个没人读的列上。")
                .isNotNull();
        assertThat(mapping.value())
                .as("标题列必须是统一读路径用的 title")
                .isEqualTo("title");

        assertThat(ChatSession.class.getAnnotation(TableName.class).value())
                .as("锚点：实体确实映射到统一会话表")
                .isEqualTo("ai_conversation");
    }

    // ------------------------------------------------------------------ 真库层

    @Test
    @DisplayName("真库：不写 title 的插入必然失败（title 是 NOT NULL 且无默认值）")
    @EnabledIfSystemProperty(named = URL_PROPERTY, matches = ".+",
            disabledReason = "需要隔离 PostgreSQL：-D" + URL_PROPERTY + "=jdbc:postgresql://host:port/db")
    void insertingWithoutTitleFails() throws IOException {
        JdbcTemplate jdbc = prepare();
        // 这就是"平台实体按约定只写 session_title"时数据库收到的形状：没有 title
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO platform.ai_conversation (id, conversation_id, user_id, session_title,"
                        + " create_time, update_time, deleted, tenant_id, member_id)"
                        + " VALUES (?,?,?,?, now(), now(), 0, ?, ?)",
                "c-no-title", "conv-no-title", "31", "只有平台列", TENANT, MEMBER))
                .as("title 是 VARCHAR(128) NOT NULL 且无默认值：省略它必然违反非空约束")
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("真库：改 session_title 不会移动 title —— 两个入口写不同列，读路径只看 title")
    @EnabledIfSystemProperty(named = URL_PROPERTY, matches = ".+",
            disabledReason = "需要隔离 PostgreSQL：-D" + URL_PROPERTY + "=jdbc:postgresql://host:port/db")
    void updatingSessionTitleLeavesTheReadPathTitleUnchanged() throws IOException {
        JdbcTemplate jdbc = prepare();
        jdbc.update("INSERT INTO platform.ai_conversation (id, conversation_id, user_id, title,"
                        + " session_title, create_time, update_time, deleted, tenant_id, member_id)"
                        + " VALUES (?,?,?,?,?, now(), now(), 0, ?, ?)",
                "c-1", "conv-title-1", "31", "原标题", "原标题", TENANT, MEMBER);

        // 平台路径（PUT /system/session）按约定只改 session_title
        jdbc.update("UPDATE platform.ai_conversation SET session_title = ? WHERE id = ?", "平台改的", "c-1");

        assertThat(jdbc.queryForObject(
                "SELECT session_title FROM platform.ai_conversation WHERE id='c-1'", String.class))
                .as("平台列确实改了")
                .isEqualTo("平台改的");
        assertThat(jdbc.queryForObject(
                "SELECT title FROM platform.ai_conversation WHERE id='c-1'", String.class))
                .as("但统一读路径读的 title 没动 —— 这就是'改名看起来没生效'的原因")
                .isEqualTo("原标题");

        // 对照：AI 资源面（WP-034B 的 rename）改的是 title，读路径立刻可见
        jdbc.update("UPDATE platform.ai_conversation SET title = ? WHERE id = ?", "资源面改的", "c-1");
        assertThat(jdbc.queryForObject(
                "SELECT title FROM platform.ai_conversation WHERE id='c-1'", String.class))
                .as("改 title 才是统一读路径看得见的那一列")
                .isEqualTo("资源面改的");
    }

    // ------------------------------------------------------------------ helpers

    private static JdbcTemplate prepare() throws IOException {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).isNotBlank();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "postgres", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS platform");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_message CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_conversation CASCADE");
        for (String ddl : frozenConversationDdl()) {
            jdbc.execute(ddl);
        }
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_conversation'", String.class))
                .as("从冻结迁移抽出的形状必须同时含两个标题列")
                .contains("title", "session_title");
        assertThat(jdbc.queryForMap(
                "SELECT is_nullable, column_default FROM information_schema.columns WHERE"
                        + " table_schema='platform' AND table_name='ai_conversation' AND column_name='title'"))
                .as("title 是 NOT NULL 且无默认值——这正是省略它的插入会失败的原因")
                .containsEntry("is_nullable", "NO");
        return jdbc;
    }

    /**
     * 从冻结迁移抽出 {@code ai_conversation} 的 DDL。
     *
     * <p>注意：`ruoyi-ai-runtime` 测试侧已有一份等价的 {@code FrozenTableDdl}，
     * 但测试源码不跨模块共享（本仓库没有 test-jar 约定）。这里保留一份最小实现；
     * "把它提升为共享测试工具"登记在 WP-037B 的待办里，不在本包顺手改构建配置。
     */
    private static List<String> frozenConversationDdl() throws IOException {
        return FrozenTableDdl.ddlOf("ai_conversation");
    }
}
