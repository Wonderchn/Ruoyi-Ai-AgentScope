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

package com.nageoffer.ai.ragent.agent.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F14:2 市场只读投影（V43 {@code platform.ai_multi_agent_app}）。
 *
 * <p>本类钉住"市场能看到什么"：
 * <ul>
 *   <li>每组查询（搜索/详情/受理复核）都带同一组可见性谓词
 *       {@code tenant_id=? AND is_public=1 AND is_enable=1 AND deleted=0}；
 *       可选关键词只能叠加过滤，不能放宽边界；</li>
 *   <li>投影只含 uuid/name/description/mode/agents，不带 user_id/租户/开关列；</li>
 *   <li>库内名册形状不合法时市场按"不可用"收敛（列表跳过、详情 404），不把坏数据投影给客户端；</li>
 *   <li>分页边界：current&lt;1 → 1，size&lt;1 → 默认 12，上限 100。</li>
 * </ul>
 */
@Tag("dev")
class MultiAgentAppServiceTest {

    private static final String TENANT = "t1";
    private static final String APP_ID = "0123456789abcdef0123456789abcdef";
    private static final String AGENTS = "[{\"name\":\"analyst\",\"instruction\":\"analyze\"},"
            + "{\"name\":\"critic\",\"instruction\":\"critique\"}]";

    private static MultiAgentAppService service(JdbcTemplate jdbc) {
        return new MultiAgentAppService(jdbc);
    }

    private static MultiAgentAppService.Row row(String uuid, String agentsJson) {
        return new MultiAgentAppService.Row(uuid, "应用-" + uuid, "描述", "SUPERVISOR", agentsJson);
    }

    @SuppressWarnings("unchecked")
    private static void stubQuery(JdbcTemplate jdbc, List<MultiAgentAppService.Row> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(rows);
    }

    private static void stubCount(JdbcTemplate jdbc, long total) {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(total);
    }

    @Test
    @DisplayName("搜索 SQL 固定可见性谓词 + 只投影公开字段，关键词只能叠加")
    void searchPinsVisibilityAndProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, List.of(row(APP_ID, AGENTS)));
        stubCount(jdbc, 1L);

        MultiAgentAppService.AppPage page = service(jdbc).search(TENANT, "季度", 2, 12);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("tenant_id=?")
                .contains("is_public=1")
                .contains("is_enable=1")
                .contains("deleted=0")
                .contains("name")
                .contains("agents::text")
                .doesNotContain("user_id")
                .doesNotContain("select *");
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.current()).isEqualTo(2L);
        assertThat(page.size()).isEqualTo(12L);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).uuid()).isEqualTo(APP_ID);
        assertThat(page.records().get(0).agents()).extracting(MultiAgentAppService.AppAgent::name)
                .containsExactly("analyst", "critic");
    }

    @Test
    @DisplayName("分页边界：current=0→1，size=0→12，size>100→100，offset 按页计算")
    void paginationIsBounded() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, List.of());
        stubCount(jdbc, 0L);
        service(jdbc).search(TENANT, "", 0, 0);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(anyString(), any(RowMapper.class), args.capture());
        Object[] values = args.getValue();
        assertThat(values).contains(0L); // offset
        assertThat(values).contains(12); // limit（默认）
        assertThat(values[0]).isEqualTo(TENANT);
        assertThat(values[1]).isEqualTo("");

        JdbcTemplate capped = mock(JdbcTemplate.class);
        stubQuery(capped, List.of());
        stubCount(capped, 0L);
        service(capped).search(TENANT, "k", 1, 500);
        ArgumentCaptor<Object[]> cappedArgs = ArgumentCaptor.forClass(Object[].class);
        verify(capped).query(anyString(), any(RowMapper.class), cappedArgs.capture());
        assertThat(cappedArgs.getValue()).contains(100);
    }

    @Test
    @DisplayName("详情：不可见（不存在/跨租户/私有/停用/软删）→ null，由控制器统一 404")
    void getPublicIsNullWhenInvisible() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, List.of());

        assertThat(service(jdbc).getPublic(TENANT, APP_ID)).isNull();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("tenant_id=?")
                .contains("uuid=?")
                .contains("is_public=1")
                .contains("is_enable=1")
                .contains("deleted=0");
    }

    @Test
    @DisplayName("详情：可见行投影 uuid/name/description/mode/agents（无所有者/租户列）")
    void getPublicProjectsMarketFields() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, List.of(row(APP_ID, AGENTS)));

        MultiAgentAppService.AppView view = service(jdbc).getPublic(TENANT, APP_ID);

        assertThat(view).isNotNull();
        assertThat(view.uuid()).isEqualTo(APP_ID);
        assertThat(view.name()).isEqualTo("应用-" + APP_ID);
        assertThat(view.description()).isEqualTo("描述");
        assertThat(view.mode()).isEqualTo("SUPERVISOR");
        assertThat(view.agents()).extracting(MultiAgentAppService.AppAgent::instruction)
                .containsExactly("analyze", "critique");
    }

    @Test
    @DisplayName("受理复核 findVisible：与市场详情同一谓词；命中返回 uuid+原始名册")
    void findVisibleSharesMarketPredicate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, List.of(row(APP_ID, AGENTS)));

        Optional<MultiAgentAppService.VisibleApp> visible = service(jdbc).findVisible(TENANT, APP_ID);

        assertThat(visible).isPresent();
        assertThat(visible.get().uuid()).isEqualTo(APP_ID);
        assertThat(visible.get().agentsJson()).isEqualTo(AGENTS);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("tenant_id=?")
                .contains("uuid=?")
                .contains("is_public=1")
                .contains("is_enable=1")
                .contains("deleted=0");
    }

    @Test
    @DisplayName("库内名册坏形状：列表跳过、详情不可用，不投影半截数据")
    void malformedStoredRosterIsNotProjected() {
        String bad = "[{\"name\":\"BAD\",\"instruction\":\"x\"},{\"name\":\"critic\",\"instruction\":\"c\"}]";
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc, List.of(row("bad", bad), row(APP_ID, AGENTS)));
        stubCount(jdbc, 2L);

        MultiAgentAppService.AppPage page = service(jdbc).search(TENANT, "", 1, 12);
        assertThat(page.records()).extracting(MultiAgentAppService.AppView::uuid)
                .containsExactly(APP_ID);

        JdbcTemplate detail = mock(JdbcTemplate.class);
        stubQuery(detail, List.of(row("bad", bad)));
        assertThat(service(detail).getPublic(TENANT, "bad")).isNull();
    }
}
