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

package com.nageoffer.ai.ragent.agent.admin;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * 可选 PostgreSQL 集成测试：仅创建当前连接的临时表，最后回滚，不接触持久业务表
 */
@Tag("dev")
class AgentDashboardReaderTest {

    @BeforeEach
    void setPrincipal() {
        PrincipalContext.set(principal("T-A"));
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void allFiveIssuedQueriesBindOnlyTheCurrentTenant() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var issued = new ArrayList<Map<String, Object>>();
        when(jdbc.queryForMap(anyString(), anyMap())).thenAnswer(invocation -> {
            issued.add(invocation.getArgument(1));
            return Map.of();
        });
        when(jdbc.queryForList(anyString(), anyMap())).thenAnswer(invocation -> {
            issued.add(invocation.getArgument(1));
            return List.of();
        });
        var reader = new AgentDashboardReader(jdbc);
        var window = AgentDashboardWindow.at("24h", "hour",
                Clock.fixed(Instant.parse("2026-09-04T04:00:00Z"), ZoneId.of("Asia/Shanghai")));
        for (String tenant : List.of("T-A", "T-B")) {
            PrincipalContext.set(principal(tenant));
            issued.clear();
            reader.read(window);
            assertThat(issued).hasSize(5).allSatisfy(params -> {
                assertThat(params).containsEntry("tenantId", tenant);
                assertThat(params.values()).doesNotContain(tenant.equals("T-A") ? "T-B" : "T-A");
            });
        }
    }

    @Test
    void missingPrincipalFailsBeforeAnyJdbcQuery() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        PrincipalContext.clear();
        assertThatThrownBy(() -> new AgentDashboardReader(jdbc).read(null)).isInstanceOf(ClientException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void realPostgresAggregationHandlesBoundariesDeletedRowsMissingBlocksAndPartialCoverage() throws Exception {
        String url = System.getProperty("ragent.dashboard.test.jdbc-url");
        assumeTrue(url != null, "Set ragent.dashboard.test.jdbc-url to run PostgreSQL integration test");
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.execute("CREATE TEMP TABLE t_user (id text, create_time timestamp, deleted smallint)");
                jdbc.execute("CREATE TEMP TABLE t_agent_conversation (id text, user_id text, conversation_id text, create_time timestamp, deleted smallint, tenant_id text NOT NULL DEFAULT 'T-A')");
                jdbc.execute("CREATE TEMP TABLE t_agent_message (id text, user_id text, conversation_id text, role text, message_status text, blocks jsonb, create_time timestamp, deleted smallint, tenant_id text NOT NULL DEFAULT 'T-A')");
                jdbc.execute("CREATE TEMP TABLE t_agent_context_compaction (create_time timestamp, context_chars_before int, context_chars_after int, tenant_id text NOT NULL DEFAULT 'T-A')");
                jdbc.execute("CREATE TEMP TABLE t_agent_memory (create_time timestamp, invalid_at timestamp, tenant_id text NOT NULL DEFAULT 'T-A')");
                jdbc.update("INSERT INTO t_user VALUES ('u1', '2026-09-01', 0), ('u2', '2026-09-04', 0), ('deleted', '2026-09-04', 1)");
                jdbc.update("INSERT INTO t_agent_conversation VALUES ('1', 'u1', 'c1', '2026-09-04', 0), ('2', 'u2', 'old', '2026-09-01', 0), ('3', 'u1', 'gone', '2026-09-04', 1)");
                jdbc.update("""
                        INSERT INTO t_agent_message VALUES
                          ('1','u1','c1','user','NORMAL',NULL,'2026-09-03 12:00',0),
                          ('2','u1','c1','assistant','NORMAL','[{"kind":"tool","name":"search_knowledge","status":"done"},{"kind":"tool","name":"write","displayName":"写操作","status":"failed"},{"kind":"confirm","status":"approved","calls":[{"name":"submit_leave","displayName":"提交请假"}]}]','2026-09-04 09:00',0),
                          ('3','u2','old','assistant','INTERRUPTED','[{"kind":"tool","name":"search_knowledge","status":"interrupted"},{"kind":"confirm","status":"denied","calls":[{"name":"submit_leave","displayName":"提交请假"},{"name":"write","displayName":"写操作"}]},{"kind":"confirm","status":"expired"}]','2026-09-04 09:30',0),
                          ('4','u2','old','assistant','AWAITING_CONFIRM','[{"kind":"confirm","status":"pending"}]','2026-09-04 10:00',0),
                          ('5','u2','old','assistant','FUTURE_STATUS',NULL,'2026-09-04 11:00',0),
                          ('6','u2','old','assistant','NORMAL','[]','2026-09-04 11:30',0),
                          ('7','u2','old','assistant','NORMAL','{"legacy":true}','2026-09-04 11:45',0),
                          ('8','u2','old','user','NORMAL',NULL,'2026-09-03 11:59',0),
                          ('9','u1','c1','assistant','NORMAL','[{"kind":"tool","name":"deleted_tool","status":"done"}]','2026-09-04 09:00',1),
                          ('10','u1','c1','assistant','NORMAL',NULL,'2026-09-04 12:00',0)
                        """);
                jdbc.update("INSERT INTO t_agent_context_compaction VALUES ('2026-09-04', 1000, 300), ('2026-09-04', 3000, 1500), ('2026-09-04', 0, 0), ('2026-09-01', 100, 10)");
                jdbc.update("INSERT INTO t_agent_memory VALUES ('2026-09-01', NULL), ('2026-09-04', NULL), ('2026-09-01', '2026-09-04'), ('2026-09-01', '2026-09-02')");
                // 对方租户使用相同 user/conversation/id，确保 tenant 过滤而不是碰巧靠 ID 分开。
                jdbc.update("INSERT INTO t_agent_conversation VALUES ('1','u1','c1','2026-09-04',0,'T-B')");
                jdbc.update("""
                        INSERT INTO t_agent_message VALUES
                          ('1','u1','c1','assistant','NORMAL','[{"kind":"tool","name":"tenant_b_tool","status":"done"},{"kind":"confirm","status":"denied","calls":[{"name":"tenant_b_tool"}]}]','2026-09-04 09:00',0,'T-B'),
                          ('2','u1','c1','assistant','NORMAL',NULL,'2026-09-03 11:59',0,'T-B')
                        """);
                jdbc.update("INSERT INTO t_agent_context_compaction VALUES ('2026-09-04',9000,900,'T-B')");
                jdbc.update("INSERT INTO t_agent_memory VALUES ('2026-09-04',NULL,'T-B')");

                var reader = new AgentDashboardReader(new NamedParameterJdbcTemplate(jdbc));
                var clock = Clock.fixed(Instant.parse("2026-09-04T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
                var service = new AgentDashboardService(reader, clock);
                var overview = service.loadOverview("24h");
                assertThat(overview.getKpis().getTotalUsers().getValue()).isEqualTo(2);
                assertThat(overview.getKpis().getSessions24h().getValue()).isEqualTo(1);
                assertThat(overview.getKpis().getMessages24h().getValue()).isEqualTo(7);
                assertThat(overview.getKpis().getTotalMessages().getValue()).isEqualTo(8);
                assertThat(overview.getKpis().getActiveSessions().getValue()).isEqualTo(2);
                assertThat(overview.getKpis().getActiveUsers().getValue()).isEqualTo(2);

                var performance = service.loadPerformance("24h");
                // 6 条回复里只有 3 条落了轨迹：其中 1 条只有确认块（直接回答）、1 条调 1 次、1 条调 2 次
                // 上周期一条助手回复都没有，环比只能留空，不能写成 +100%
                assertThat(performance.replies())
                        .isEqualTo(new AgentDashboardPerformance.Replies(6, 0, null, 3, 1, 1, 1, 3, 1, 1, 1));
                assertThat(performance.tools().total()).isEqualTo(3);
                assertThat(performance.tools().done()).isEqualTo(1);
                assertThat(performance.tools().failed()).isEqualTo(1);
                assertThat(performance.tools().interrupted()).isEqualTo(1);
                assertThat(performance.tools().knowledgeSearchCalls()).isEqualTo(2);
                assertThat(performance.tools().callsPerRecordedReply()).isEqualTo(1.0);
                assertThat(performance.tools().topTools()).hasSize(2);
                // search_knowledge 两次调用一次 done 一次 interrupted，成功率分母是全部调用而不是 done + failed
                // lastCallAt 取的是最近一次调用所在回复的创建时间，两条里较晚的那条是 09:30
                assertThat(performance.tools().topTools().get(0))
                        .isEqualTo(new AgentDashboardPerformance.ToolCount("search_knowledge", "search_knowledge", 2, 1, 0, 50.0,
                                epochMillis("2026-09-04T09:30:00", clock.getZone())));
                assertThat(performance.tools().topTools().get(1))
                        .isEqualTo(new AgentDashboardPerformance.ToolCount("write", "写操作", 1, 0, 1, 0.0,
                                epochMillis("2026-09-04T09:00:00", clock.getZone())));
                // 4 张确认卡，但被裁决的两张里裹着 3 次调用：卡是裁决单位，调用才是明细单位
                assertThat(performance.confirmations().total()).isEqualTo(4);
                assertThat(performance.confirmations().topTools()).containsExactly(
                        new AgentDashboardPerformance.ConfirmToolCount("submit_leave", "提交请假", 2, 1, 1),
                        new AgentDashboardPerformance.ConfirmToolCount("write", "写操作", 1, 0, 1));
                assertThat(performance.confirmations())
                        .isEqualTo(new AgentDashboardPerformance.Confirmations(4, 1, 1, 1, 1, 0, 50.0,
                                performance.confirmations().topTools()));
                // 窗口外那条压缩记录（100→10）不能进绝对值，否则和 55% 对不上；
                // 窗口内那条 0→0 算一次压缩但没记字符数，所以两个分母必须分开：3 次压缩里只有 2 次能求平均
                assertThat(performance.memory()).isEqualTo(new AgentDashboardPerformance.Memory(3, 2, 55.0, 4000, 1800, 2, 1, 1));
                var messageTrend = service.loadTrends("messages", "24h", "hour");
                assertThat(messageTrend.getSeries().get(0).getData())
                        .hasSize(24).extracting("value").contains(1.0, 2.0, 3.0);
                // 上周期只有 09-03 11:59 那条，前移 24 小时后必须落在末桶（09-04 11:00）而不是原位
                assertThat(messageTrend.getSeries().get(1).getData()).hasSize(24);
                assertThat(messageTrend.getSeries().get(1).getData().get(23).getValue()).isEqualTo(1.0);
                assertThat(messageTrend.getSeries().get(1).getData().stream()
                        .mapToDouble(point -> point.getValue()).sum()).isEqualTo(1.0);
                double toolTrendTotal = service.loadTrends("tools", "24h", "hour").getSeries().get(0).getData()
                        .stream().mapToDouble(point -> point.getValue()).sum();
                assertThat(toolTrendTotal).isEqualTo(3.0);
                assertThat(service.loadTrends("messages", "7d", "day").getSeries().get(0).getData()).hasSize(8);
                PrincipalContext.set(principal("T-B"));
                var tenantB = service.loadOverview("24h");
                assertThat(tenantB.getKpis().getTotalUsers().getValue()).isEqualTo(1);
                assertThat(tenantB.getKpis().getTotalMessages().getValue()).isEqualTo(2);
                assertThat(tenantB.getKpis().getMessages24h().getValue()).isEqualTo(1);
                assertThat(tenantB.getKpis().getSessions24h().getValue()).isEqualTo(1);
                var performanceB = service.loadPerformance("24h");
                assertThat(performanceB.replies().total()).isEqualTo(1);
                assertThat(performanceB.replies().previousTotal()).isEqualTo(1);
                assertThat(performanceB.tools().topTools()).extracting("name").containsExactly("tenant_b_tool");
                assertThat(performanceB.confirmations().topTools()).extracting("name").containsExactly("tenant_b_tool");
                assertThat(performanceB.memory()).isEqualTo(new AgentDashboardPerformance.Memory(1,1,90.0,9000,900,1,1,0));
                assertThat(service.loadTrends("messages", "24h", "hour").getSeries().get(0).getData()
                        .stream().mapToDouble(point -> point.getValue()).sum()).isEqualTo(1.0);
                assertThat(service.loadTrends("messages", "24h", "hour").getSeries().get(1).getData()
                        .stream().mapToDouble(point -> point.getValue()).sum()).isEqualTo(1.0);
                PrincipalContext.set(principal("T-A"));
                assertThat(service.loadOverview("24h")).isSameAs(overview);
            } finally {
                connection.rollback();
            }
        }
    }

    private static long epochMillis(String localDateTime, ZoneId zone) {
        return LocalDateTime.parse(localDateTime).atZone(zone).toInstant().toEpochMilli();
    }

    private static ExecutionPrincipal principal(String tenantId) {
        return new ExecutionPrincipal(tenantId, "1", "platform:" + tenantId + ":1", 1, 1,
                Set.of("monitor.read"), "dashboard-reader-test", "test", 1, Long.MAX_VALUE);
    }
}
