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

package com.nageoffer.ai.ragent.shortdrama;

import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F21 仓库 SQL 判据：每条语句的租户/属主谓词与润色 CAS 条件都必须真实出现在
 * 下发给数据库的 SQL 与绑定参数里。
 *
 * <p><b>为什么需要可执行 SQL 判据。</b>服务层单测 mock 掉仓库后，"仓库有没有写
 * {@code tenant_id} 谓词"完全不可见；把谓词删掉，所有既有测试仍然绿。本判据用一个
 * 捕获 SQL/参数的 {@link JdbcTemplate} 子类执行真实仓库方法，断言的是<b>下发语句的
 * 字节</b>，而不是服务层意图。
 *
 * <p><b>不连数据库。</b>子类拦截 {@code query/queryForObject/update}，返回空结果或
 * 固定计数；不解析行映射，也不访问 DataSource。
 */
@Tag("dev")
class ShortDramaRepositorySqlTest {

    private static final String TENANT = DramaTestSupport.TENANT;

    private final CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
    private final ShortDramaRepository repository = new ShortDramaRepository(jdbc);

    @Test
    @DisplayName("项目按 ID 读取：id + user_id + tenant_id 三个谓词与绑定必须同时存在")
    void findProjectPinsIdUserTenant() {
        repository.findProject(7L, 42L, TENANT);
        assertThat(normalize(jdbc.lastSql()))
                .contains("FROM platform.ai_short_drama_project")
                .contains("WHERE id = ?")
                .contains("user_id = ?")
                .contains("tenant_id = ?");
        assertThat(jdbc.lastArgs()).containsExactly(7L, 42L, TENANT);
    }

    @Test
    @DisplayName("项目列表：user_id + tenant_id 谓词与绑定")
    void listProjectsPinsUserTenant() {
        repository.listProjects(42L, TENANT);
        assertThat(normalize(jdbc.lastSql()))
                .contains("WHERE user_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs()).containsExactly(42L, TENANT);
    }

    @Test
    @DisplayName("项目更新：id + user_id + tenant_id 谓词与绑定")
    void updateProjectPinsIdUserTenant() {
        repository.updateProject(1L, 42L, TENANT, "改名", "简介", "active", "realistic");
        assertThat(normalize(jdbc.lastSql()))
                .contains("WHERE id = ? AND user_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs())
                .containsExactly("改名", "简介", "active", "realistic", 1L, 42L, TENANT);
    }

    @Test
    @DisplayName("脚本按 ID 读取：id + project_id + tenant_id 谓词与绑定")
    void findScriptPinsIdProjectTenant() {
        repository.findScript(20L, 1L, TENANT);
        assertThat(normalize(jdbc.lastSql()))
                .contains("FROM platform.ai_short_drama_script")
                .contains("WHERE id = ? AND project_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs()).containsExactly(20L, 1L, TENANT);
    }

    @Test
    @DisplayName("最新脚本：project_id + tenant_id + LIMIT 1")
    void findLatestScriptPinsProjectTenant() {
        repository.findLatestScript(1L, TENANT);
        assertThat(normalize(jdbc.lastSql()))
                .contains("WHERE project_id = ? AND tenant_id = ?")
                .contains("ORDER BY id DESC LIMIT 1");
        assertThat(jdbc.lastArgs()).containsExactly(1L, TENANT);
    }

    @Test
    @DisplayName("脚本插入：tenant_id 列与绑定必须存在（不靠 DDL 默认租户）")
    void insertScriptWritesExplicitTenant() {
        repository.insertScript(1L, TENANT, "剧本", "正文", "大纲", "短剧", "ai");
        String sql = normalize(jdbc.lastSql());
        assertThat(sql)
                .contains("INSERT INTO platform.ai_short_drama_script")
                .contains("tenant_id");
        Object[] args = jdbc.lastArgs();
        assertThat(args).hasSize(8);
        assertThat(args[0]).isInstanceOf(Long.class);
        assertThat(args[1]).isEqualTo(1L);
        assertThat(args[7]).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("润色 CAS：project_id + tenant_id + update_time = ? 条件与绑定必须同时存在")
    void updateScriptPolishedPinsProjectTenantAndUpdateTime() {
        Timestamp observed = Timestamp.from(Instant.parse("2026-10-19T00:00:00Z"));
        repository.updateScriptPolished(20L, 1L, TENANT, observed,
                "润色稿", "润色正文", "润色大纲", "短剧");
        assertThat(normalize(jdbc.lastSql()))
                .contains("WHERE id = ? AND project_id = ? AND tenant_id = ? AND update_time = ?");
        assertThat(jdbc.lastArgs())
                .containsExactly("润色稿", "润色正文", "润色大纲", "短剧",
                        20L, 1L, TENANT, observed);
    }

    @Test
    @DisplayName("资产读取：角色/场景按 project_id + tenant_id 限域")
    void assetReadsPinProjectTenant() {
        repository.listCharacters(1L, TENANT);
        assertThat(normalize(jdbc.lastSql()))
                .contains("FROM platform.ai_short_drama_character")
                .contains("WHERE project_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs()).containsExactly(1L, TENANT);

        repository.listLocations(1L, TENANT);
        assertThat(normalize(jdbc.lastSql()))
                .contains("FROM platform.ai_short_drama_location")
                .contains("WHERE project_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs()).containsExactly(1L, TENANT);

        repository.listStoryboards(1L, 20L, TENANT);
        assertThat(normalize(jdbc.lastSql()))
                .contains("FROM platform.ai_short_drama_storyboard")
                .contains("WHERE project_id = ? AND script_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs()).containsExactly(1L, 20L, TENANT);
    }

    @Test
    @DisplayName("分镜规则更新：id + project_id + script_id + tenant_id 四重限域")
    void storyboardRuleUpdatesPinAllBounds() {
        repository.updateStoryboardPhotography(50L, 1L, 20L, TENANT, "{\"shotType\":\"中景\"}");
        assertThat(normalize(jdbc.lastSql()))
                .contains("WHERE id = ? AND project_id = ? AND script_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs())
                .containsExactly("{\"shotType\":\"中景\"}", 50L, 1L, 20L, TENANT);

        repository.updateStoryboardActing(50L, 1L, 20L, TENANT, "{\"emotion\":\"克制\"}");
        assertThat(normalize(jdbc.lastSql()))
                .contains("WHERE id = ? AND project_id = ? AND script_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs())
                .containsExactly("{\"emotion\":\"克制\"}", 50L, 1L, 20L, TENANT);
    }

    @Test
    @DisplayName("计数：project_id + tenant_id 谓词与绑定")
    void countsPinProjectTenant() {
        repository.countStoryboards(1L, TENANT);
        assertThat(normalize(jdbc.lastSql()))
                .contains("SELECT count(*)")
                .contains("WHERE project_id = ? AND tenant_id = ?");
        assertThat(jdbc.lastArgs()).containsExactly(1L, TENANT);
    }

    @Test
    @DisplayName("级联删除：六条子表语句都带 tenant_id，项目面都带 project_id")
    void cascadeDeletesPinTenantEverywhere() {
        repository.deleteProjectChildren(1L, TENANT);
        List<String> sqls = jdbc.allSql();
        assertThat(sqls).hasSize(6);
        for (String sql : sqls) {
            assertThat(normalize(sql)).contains("tenant_id = ?");
        }
        for (Object[] args : jdbc.allArgs()) {
            assertThat(args).contains(TENANT);
        }
        assertThat(normalize(sqls.get(0))).contains("ai_short_drama_character_appearance");
        assertThat(normalize(sqls.get(1))).contains("ai_short_drama_storyboard");
        assertThat(normalize(sqls.get(2))).contains("ai_short_drama_character");
        assertThat(normalize(sqls.get(3))).contains("ai_short_drama_location");
        assertThat(normalize(sqls.get(4))).contains("ai_short_drama_audio");
        assertThat(normalize(sqls.get(5))).contains("ai_short_drama_script");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** 捕获 SQL 与绑定参数；不执行、不连库。 */
    static final class CapturingJdbcTemplate extends JdbcTemplate {

        private record Call(String sql, Object[] args) {
        }

        private final List<Call> calls = new ArrayList<>();
        private List<?> queryResult = List.of();
        private Long countResult = 0L;
        private int updateResult = 1;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            calls.add(new Call(sql, args));
            @SuppressWarnings("unchecked")
            List<T> result = (List<T>) queryResult;
            return result;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            calls.add(new Call(sql, args));
            @SuppressWarnings("unchecked")
            T result = (T) countResult;
            return result;
        }

        @Override
        public int update(String sql, Object... args) {
            calls.add(new Call(sql, args));
            return updateResult;
        }

        String lastSql() {
            return calls.get(calls.size() - 1).sql();
        }

        Object[] lastArgs() {
            return calls.get(calls.size() - 1).args();
        }

        List<String> allSql() {
            return calls.stream().map(Call::sql).toList();
        }

        List<Object[]> allArgs() {
            return calls.stream().map(Call::args).toList();
        }
    }
}
