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

package com.nageoffer.ai.ragent.media.task;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复核缺口（变异 a）：{@link JdbcMediaTaskRepository} 的 <b>JDBC owner 谓词</b>必须有回归判据。
 *
 * <p>为什么不是 FakeRepository：{@link MediaTaskServiceTest} 的 Fake 自己实现 owner 过滤，
 * 删除真实 JDBC SQL 里的 {@code owner_member_id=?} 时它仍然全绿（复核实测 17/17）。
 * 本测试直接构造真实 {@link JdbcMediaTaskRepository}，只把 {@link JdbcTemplate#query}
 * 换成可捕获 SQL/绑定参数的子类（不连库、不引入 Testcontainers）。
 *
 * <p>为什么能抓住变异：{@code COLUMNS} 的 SELECT 列表里也有 {@code owner_member_id}
 * （但形态是逗号，不是 {@code =?}）。因此断言必须钉在谓词形态
 * {@code owner_member_id=?} 与 WHERE 连续片段上；只删 WHERE 里的 owner 谓词时，
 * 这两条同时变红，而仅把参数少传一个时绑定顺序断言变红。
 */
@Tag("dev")
class JdbcMediaTaskRepositoryOwnerScopeTest {

    private static final String TENANT = "T1";
    private static final String OWNER = "platform:T1:1001";
    private static final String TASK_ID = "mt-owner-scope";

    private final MediaTaskRecord sample = new MediaTaskRecord(
            TENANT, TASK_ID, OWNER, "1001", "image", "Tongyiwanx", "wanx-test",
            "req-1", null, 1, MediaTaskStatus.RUNNING, "provider-1", "a red fox",
            "1024*1024", 7, null, null, null, null, null, null, null, null, null, null,
            Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-19T00:00:01Z"));

    private final CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate(sample);
    private final JdbcMediaTaskRepository repository = new JdbcMediaTaskRepository(jdbc);

    @Test
    void findOwnedBindsTenantOwnerAndTaskInTheWhereClause() {
        Optional<MediaTaskRecord> found = repository.findOwned(TENANT, OWNER, TASK_ID);

        assertThat(found).isPresent();
        CapturedQuery query = jdbc.last();
        String sql = normalize(query.sql());
        assertThat(sql)
                .as("findOwned 必须同时限定 tenant 与 owner（删掉 owner 谓词即红）")
                .contains("from platform.ai_media_task")
                .contains("where tenant_id=? and owner_member_id=?")
                .contains("task_id=?")
                .contains("owner_member_id=?");
        assertThat(query.args())
                .as("绑定顺序必须是 tenant → owner → task（少传/换序即红）")
                .containsExactly(TENANT, OWNER, TASK_ID);
    }

    @Test
    void findByRequestIdBindsTenantOwnerAndRequest() {
        repository.findByRequestId(TENANT, OWNER, "req-1");

        CapturedQuery query = jdbc.last();
        assertThat(normalize(query.sql()))
                .contains("where tenant_id=? and owner_member_id=?")
                .contains("request_id=?");
        assertThat(query.args()).containsExactly(TENANT, OWNER, "req-1");
    }

    @Test
    void listOwnedBindsTenantAndOwnerBeforeTheBoundedLimit() {
        repository.listOwned(TENANT, OWNER, 20);

        CapturedQuery query = jdbc.last();
        String sql = normalize(query.sql());
        assertThat(sql)
                .contains("where tenant_id=? and owner_member_id=?")
                .contains("order by created_at desc");
        // tenant, owner, bounded limit(20)
        assertThat(query.args()).containsExactly(TENANT, OWNER, 20);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private record CapturedQuery(String sql, List<Object> args) {
    }

    /**
     * 只替换取数结果并记录 SQL/参数；SQL 文本仍由被测仓储真实生成。
     *
     * <p>不 mock JdbcTemplate 的 query 重载（Mockito 对 varargs 的匹配容易失真），
     * 也不用真实 PG（本窗口没有可用的 DB 租约）；子类覆写能逐字保留 SQL 形状。
     */
    private static final class CapturingJdbcTemplate extends JdbcTemplate {

        private final MediaTaskRecord row;
        private final List<CapturedQuery> queries = new ArrayList<>();

        private CapturingJdbcTemplate(MediaTaskRecord row) {
            this.row = row;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) throws DataAccessException {
            queries.add(new CapturedQuery(sql, args == null ? List.of() : new ArrayList<>(Arrays.asList(args))));
            @SuppressWarnings("unchecked")
            List<T> typed = (List<T>) List.of(row);
            return typed;
        }

        private CapturedQuery last() {
            return queries.get(queries.size() - 1);
        }
    }
}
