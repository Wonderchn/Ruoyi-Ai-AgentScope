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

package com.nageoffer.ai.ragent.runtime.dao;

import com.nageoffer.ai.ragent.authorization.FrozenTableDdl;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F16 op3：{@code listCompletedConversationRuns} / {@code loadCompletedConversationAnswer}
 * 在真实 PostgreSQL 上的精确范围判据。
 *
 * <p>判的就是"历史轮的可复核范围"：同 tenant/member/subject/conversation、
 * SUCCEEDED rag.chat、retrieve/model/conversation-history 三步全 COMPLETED、
 * 排除当前 run、最近 N 条倒序（created_at DESC, run_id DESC）——其余一律不得混入；
 * 答案只认非空 assistant（按 sequence），没有就是空 Optional。
 *
 * <p>表形状从冻结迁移抽出（{@link FrozenTableDdl}），需要
 * {@code -Dragent.conversation.test.jdbc-url=...}（凭据可用
 * {@code -D…jdbc-user} / {@code -D…jdbc-password} 覆盖，缺省 {@code postgres}/空）；
 * 未提供时显式跳过。<b>本类会 DROP/重建相关表，必须连独占隔离库</b>，不得指向共享主库。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = RunLedgerDaoConversationContextTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + RunLedgerDaoConversationContextTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class RunLedgerDaoConversationContextTest {

    static final String URL_PROPERTY = "ragent.conversation.test.jdbc-url";
    static final String USER_PROPERTY = "ragent.conversation.test.jdbc-user";
    static final String PASSWORD_PROPERTY = "ragent.conversation.test.jdbc-password";

    private static final String TENANT = "T-CTX";
    private static final String MEMBER = "platform:T-CTX:31";
    private static final String SUBJECT = "31";
    private static final String CONV = "conv-ctx-1";
    private static final String OTHER_CONV = "conv-ctx-2";
    private static final String CURRENT_RUN = "run-current";

    private static JdbcTemplate jdbc;
    private static RunLedgerDao dao;

    @BeforeAll
    static void setUp() throws IOException {
        String url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                System.getProperty(USER_PROPERTY, "postgres"),
                System.getProperty(PASSWORD_PROPERTY, ""));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS platform");
        // FK 指向 ai_run：删除按"子表在前"、创建按"父表在前"
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_chat_message");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_run_step");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_run");
        for (String ddl : FrozenTableDdl.forTable("ai_run")) {
            jdbc.execute(ddl);
        }
        for (String ddl : FrozenTableDdl.forTable("ai_run_step")) {
            jdbc.execute(ddl);
        }
        for (String ddl : FrozenTableDdl.forTable("ai_chat_message")) {
            jdbc.execute(ddl);
        }
        // 形状锚点：查询依赖的列必须来自冻结迁移
        List<String> runColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='platform'"
                        + " AND table_name='ai_run'", String.class);
        assertThat(runColumns).as("从冻结迁移抽出的 ai_run 形状")
                .contains("tenant_id", "run_id", "member_id", "subject", "action", "status",
                        "input", "policy_version", "acl_version", "created_at");
        dao = new RunLedgerDao(jdbc);
    }

    @BeforeEach
    void clearRows() {
        jdbc.execute("DELETE FROM platform.ai_chat_message");
        jdbc.execute("DELETE FROM platform.ai_run_step");
        jdbc.execute("DELETE FROM platform.ai_run");
    }

    // ---------------------------------------------------------------- fixtures

    private static void seedRun(String tenant, String runId, String member, String subject,
                                String status, String conversationId, Instant createdAt) {
        jdbc.update("INSERT INTO platform.ai_run (tenant_id, run_id, member_id, subject, action, status, "
                        + "input, policy_version, acl_version, created_at) "
                        + "VALUES (?,?,?,?,'rag.chat',?,?::jsonb,1,1,?)",
                tenant, runId, member, subject, status,
                "{\"text\":\"" + runId + "\",\"conversationId\":\"" + conversationId + "\"}",
                Timestamp.from(createdAt));
    }

    private static void seedRun(String runId, String status, String conversationId, Instant createdAt) {
        seedRun(TENANT, runId, MEMBER, SUBJECT, status, conversationId, createdAt);
    }

    private static void seedStep(String runId, String stepId, String state) {
        jdbc.update("INSERT INTO platform.ai_run_step (tenant_id, run_id, step_id, attempt, "
                        + "checkpoint_version, step_name, state, fence) VALUES (?,?,?,1,1,?,?,1)",
                TENANT, runId, stepId, stepId, state);
    }

    /** 完整可重放轮的三个完成步骤。 */
    private static void seedCompleteSteps(String runId) {
        seedStep(runId, "retrieve", "COMPLETED");
        seedStep(runId, "model", "COMPLETED");
        seedStep(runId, "conversation-history", "COMPLETED");
    }

    private static void seedAnswer(String runId, int sequence, String role, String content) {
        jdbc.update("INSERT INTO platform.ai_chat_message (tenant_id, message_id, run_id, sequence, role, content) "
                        + "VALUES (?,?,?,?,?,?)",
                TENANT, "msg-" + runId + "-" + sequence, runId, sequence, role, content);
    }

    private static List<String> listRunIds(String excludingRunId, int limit) {
        return dao.listCompletedConversationRuns(TENANT, MEMBER, SUBJECT, CONV, excludingRunId, limit)
                .stream().map(RunRecord::runId).toList();
    }

    // ---------------------------------------------------------------- the query scope

    @Test
    @DisplayName("只收录同会话三步完整且 SUCCEEDED 的 rag.chat 轮：成员/租户/会话/状态/步骤任一不符都不混入")
    void onlyCompleteSucceededTurnsInTheSameConversationAreEligible() {
        Instant base = Instant.parse("2026-10-15T02:00:00Z");
        seedRun("run-complete-1", "SUCCEEDED", CONV, base);
        seedCompleteSteps("run-complete-1");
        // 步骤不全：缺 conversation-history ⇒ 不是可重放轮（写路径上线前的历史 run 保守排除）
        seedRun("run-no-history", "SUCCEEDED", CONV, base.plusSeconds(60));
        seedStep("run-no-history", "retrieve", "COMPLETED");
        seedStep("run-no-history", "model", "COMPLETED");
        // retrieve STARTED 不算完整（同一 (run, step, attempt, checkpoint) 只插一行 STARTED）
        seedRun("run-started-step", "SUCCEEDED", CONV, base.plusSeconds(120));
        seedStep("run-started-step", "retrieve", "STARTED");
        seedStep("run-started-step", "model", "COMPLETED");
        seedStep("run-started-step", "conversation-history", "COMPLETED");
        // 非 SUCCEEDED
        seedRun("run-failed", "FAILED", CONV, base.plusSeconds(180));
        seedCompleteSteps("run-failed");
        seedRun("run-running", "RUNNING", CONV, base.plusSeconds(240));
        seedCompleteSteps("run-running");
        // 同租户其他成员 / 其他租户
        seedRun(TENANT, "run-other-member", "platform:T-CTX:99", SUBJECT, "SUCCEEDED", CONV, base.plusSeconds(300));
        seedCompleteSteps("run-other-member");
        seedRun("T-OTHER", "run-other-tenant", MEMBER, SUBJECT, "SUCCEEDED", CONV, base.plusSeconds(360));
        seedCompleteSteps("run-other-tenant");
        // 其他会话
        seedRun("run-other-conv", "SUCCEEDED", OTHER_CONV, base.plusSeconds(420));
        seedCompleteSteps("run-other-conv");
        // 当前 run 自身（即使完整也不得混入）
        seedRun(CURRENT_RUN, "SUCCEEDED", CONV, base.plusSeconds(480));
        seedCompleteSteps(CURRENT_RUN);
        // 第二完整轮（最近）
        seedRun("run-complete-2", "SUCCEEDED", CONV, base.plusSeconds(540));
        seedCompleteSteps("run-complete-2");

        assertThat(listRunIds(CURRENT_RUN, 8))
                .as("最近 N 轮按 created_at DESC, run_id DESC")
                .containsExactly("run-complete-2", "run-complete-1");
        assertThat(listRunIds(CURRENT_RUN, 1))
                .as("limit 收敛到最近一轮")
                .containsExactly("run-complete-2");
    }

    @Test
    @DisplayName("同时间戳按 run_id 稳定倒序")
    void sameTimestampFallsBackToRunIdDescending() {
        Instant same = Instant.parse("2026-10-15T03:00:00Z");
        for (String runId : List.of("run-z", "run-a", "run-m")) {
            seedRun(runId, "SUCCEEDED", CONV, same);
            seedCompleteSteps(runId);
        }

        assertThat(listRunIds(CURRENT_RUN, 8)).containsExactly("run-z", "run-m", "run-a");
    }

    @Test
    @DisplayName("畸形/缺失 input 的 run 不匹配会话（不出现在任何会话结果里）")
    void runsWithoutConversationIdAreNotEligibleForAnyConversation() {
        Instant base = Instant.parse("2026-10-15T04:00:00Z");
        jdbc.update("INSERT INTO platform.ai_run (tenant_id, run_id, member_id, subject, action, status, "
                        + "input, policy_version, acl_version, created_at) VALUES (?,?,?,?,'rag.chat','SUCCEEDED',"
                        + " '{\"text\":\"无会话\"}'::jsonb,1,1,?)",
                TENANT, "run-no-conv", MEMBER, SUBJECT, Timestamp.from(base));
        seedCompleteSteps("run-no-conv");

        assertThat(listRunIds(CURRENT_RUN, 8)).isEmpty();
    }

    // ---------------------------------------------------------------- the answer

    @Test
    @DisplayName("答案：非空 assistant 按 sequence 取第一条；user 行/空内容不冒充答案；没有即空")
    void answerIsTheFirstNonBlankAssistantMessage() {
        seedRun("run-answer", "SUCCEEDED", CONV, Instant.parse("2026-10-15T05:00:00Z"));
        seedAnswer("run-answer", 1, "user", "问题");
        seedAnswer("run-answer", 2, "assistant", "   ");
        seedAnswer("run-answer", 3, "assistant", "最终答案");

        assertThat(dao.loadCompletedConversationAnswer(TENANT, "run-answer")).contains("最终答案");
        assertThat(dao.loadCompletedConversationAnswer(TENANT, "run-answer-missing")).isEmpty();
    }
}
