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

package com.nageoffer.ai.ragent.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import com.nageoffer.ai.ragent.runtime.exec.RunExecutionGuard;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F14:0/1/3 · 统一账本（§2.2）与零新增护栏（R17/R18）。
 *
 * <p>账本断言：受理写恰一行 {@code ai_agent_run}；预算默认 3000/9/1；spendStep/addTokens
 * 受 max_steps/max_tokens 约束；绑定兼容是纯函数。
 *
 * <p>护栏断言：canonical 仍 56 条且无 {@code agent.supervisor}；迁移最高仍 V42；
 * 网关源码无 supervisor 路由；orchestration 包无 {@code @TableName}、无第二账本写入、
 * 不写 {@code ai_tool_call}/{@code ai_agent_checkpoint}/{@code ai_run_event}。
 */
@Tag("dev")
class SupervisorLedgerTest {

    private static final String TENANT = "t1";
    private static final String RUN = "r1";

    private static RunRecord run() {
        return new RunRecord(TENANT, RUN, "platform:t1:1001", "1001", "agent.supervisor", "RUNNING",
                null, "key", "{}", "{}", "supervisor-v1", 1, 1, "[]", 1, 1, 1, 1,
                null, null, null, null, null, null, null, null, null);
    }

    private static AdmissionRequest request(JsonNode budget) {
        return new AdmissionRequest(1, "agent.supervisor", null, "supervisor-v1",
                CanonicalJson.inputOf("{\"text\":\"q\",\"agents\":[]}", CanonicalJson.strictMapper()),
                List.of(), null, budget, null);
    }

    private static JsonNode json(String text) {
        try {
            return CanonicalJson.strictMapper().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ 绑定行

    @Test
    @DisplayName("受理写恰一行 ai_agent_run：默认预算 3000/9/1、冻结身份 supervisor-v1")
    void admittedWritesOneBindingRowWithFrozenDefaults() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SupervisorLedger ledger = new SupervisorLedger(jdbc);

        ledger.admitted(run(), request(null), "deepseek-flash");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().startsWith("INSERT INTO ai_agent_run"));
        Object[] values = args.getValue();
        assertEquals(TENANT, values[0]);
        assertEquals(RUN, values[1]);
        assertEquals("platform:t1:1001", values[2]);
        assertEquals("supervisor-v1", values[3]);
        assertEquals("2.0.2", values[4]);
        assertEquals("deepseek-flash", values[5]);
        assertEquals("supervisor-tools-v1", values[6]);
        assertEquals(9, values[7]);
        assertEquals(1, values[8]);
        assertEquals(3000, values[9]);
        assertEquals("[]", values[10]);
        assertEquals(9, SupervisorLedger.DEFAULT_MAX_STEPS,
                "F-3: 4 delegations + final = 9 model calls must fit the default budget");
    }

    @Test
    @DisplayName("显式预算按受理校验后的值落行（不覆盖为默认）")
    void admittedKeepsExplicitBudget() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new SupervisorLedger(jdbc).admitted(run(),
                request(json("{\"maxTokens\":1000,\"maxSteps\":6,\"maxToolCalls\":2}")), "model-1");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertEquals(6, args.getValue()[7]);
        assertEquals(2, args.getValue()[8]);
        assertEquals(1000, args.getValue()[9]);
    }

    @Test
    @DisplayName("绑定兼容是纯函数：身份/引擎/目录/模型/checkpoint 五条同时成立")
    void compatibilityRequiresAllFrozenFacts() {
        SupervisorLedger.Binding compatible = new SupervisorLedger.Binding("platform:t1:1001", "supervisor-v1",
                "2.0.2", "model-1", "supervisor-tools-v1", 8, 1, 3000, 0, 1);
        assertTrue(SupervisorLedger.isCompatible(compatible, "model-1"));
        assertFalse(SupervisorLedger.isCompatible(compatible, "other-model"));
        assertFalse(SupervisorLedger.isCompatible(compatible, null));
        assertFalse(SupervisorLedger.isCompatible(new SupervisorLedger.Binding("platform:t1:1001", "core-v1",
                "2.0.2", "model-1", "supervisor-tools-v1", 8, 1, 3000, 0, 1), "model-1"));
        assertFalse(SupervisorLedger.isCompatible(new SupervisorLedger.Binding("platform:t1:1001", "supervisor-v1",
                "old-engine", "model-1", "supervisor-tools-v1", 8, 1, 3000, 0, 1), "model-1"));
        assertFalse(SupervisorLedger.isCompatible(new SupervisorLedger.Binding("platform:t1:1001", "supervisor-v1",
                "2.0.2", "model-1", "p3-tools-v1", 8, 1, 3000, 0, 1), "model-1"));
        assertFalse(SupervisorLedger.isCompatible(new SupervisorLedger.Binding("platform:t1:1001", "supervisor-v1",
                "2.0.2", "model-1", "supervisor-tools-v1", 8, 1, 3000, 0, 2), "model-1"));
    }

    @Test
    @DisplayName("绑定读取：非唯一/成员不符 → 404（不猜第二行）")
    void bindingReadbackRejectsMissingOrForeignRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SupervisorLedger ledger = new SupervisorLedger(jdbc);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<SupervisorLedger.Binding>>any(),
                any(Object[].class))).thenAnswer(invocation -> List.of(new SupervisorLedger.Binding(
                "platform:t1:1001", "supervisor-v1", "2.0.2", "model-1", "supervisor-tools-v1", 8, 1, 3000, 0, 1)));
        assertEquals("model-1", ledger.binding(run()).model());

        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<SupervisorLedger.Binding>>any(),
                any(Object[].class))).thenReturn(List.of());
        assertThrows(RunApiException.class, () -> ledger.binding(run()));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<SupervisorLedger.Binding>>any(),
                any(Object[].class))).thenReturn(List.of(new SupervisorLedger.Binding("other-member",
                "supervisor-v1", "2.0.2", "model-1", "supervisor-tools-v1", 8, 1, 3000, 0, 1)));
        assertThrows(RunApiException.class, () -> ledger.binding(run()));
    }

    // ------------------------------------------------------------ 预算

    @Test
    @DisplayName("spendStep 在 max_steps 内 +1；超限 → BUDGET_EXCEEDED（fence 受控）")
    void spendStepEnforcesMaxSteps() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SupervisorLedger ledger = new SupervisorLedger(jdbc);
        RunExecutionGuard guard = guard();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        ledger.spendStep(guard);
        verify(jdbc).update(ArgumentMatchers.contains("steps_used=steps_used+1"), any(Object[].class));

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        RunApiException exceeded = assertThrows(RunApiException.class, () -> ledger.spendStep(guard));
        assertEquals(RunErrorCode.BUDGET_EXCEEDED, exceeded.errorCode());
    }

    @Test
    @DisplayName("addTokens 超 max_tokens → BUDGET_EXCEEDED；负数 → RUN_STATE_CONFLICT")
    void addTokensEnforcesMaxTokens() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SupervisorLedger ledger = new SupervisorLedger(jdbc);
        RunExecutionGuard guard = guard();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        ledger.addTokens(guard, 10);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        RunApiException exceeded = assertThrows(RunApiException.class, () -> ledger.addTokens(guard, 10));
        assertEquals(RunErrorCode.BUDGET_EXCEEDED, exceeded.errorCode());
        RunApiException negative = assertThrows(RunApiException.class, () -> ledger.addTokens(guard, -1));
        assertEquals(RunErrorCode.RUN_STATE_CONFLICT, negative.errorCode());
    }

    private static RunExecutionGuard guard() {
        RunExecutionGuard guard = mock(RunExecutionGuard.class);
        when(guard.tenantId()).thenReturn(TENANT);
        when(guard.runId()).thenReturn(RUN);
        when(guard.commitAtomic(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        return guard;
    }

    // ------------------------------------------------------------ R17：零新增共享面

    @Test
    @DisplayName("R17：canonical 仍 56 条、无 agent.supervisor；迁移最高 V42；网关无 supervisor 路由")
    void zeroNewCanonicalMigrationOrRoute() throws Exception {
        Set<String> actions = org.ruoyi.ai.api.action.AiCanonicalAction.knownActions();
        assertEquals(56, actions.size());
        assertFalse(actions.contains("agent.supervisor"));
        assertFalse(actions.contains("supervisor.run"));

        Path migrations = platformRoot().resolve("docs/script/sql/postgres");
        assertTrue(Files.isDirectory(migrations), "migration directory must be readable: " + migrations);
        int max = 0;
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                assertFalse(name.toLowerCase(Locale.ROOT).contains("supervisor"),
                        "no supervisor migration may be added: " + name);
                if (name.matches("V\\d+__.*\\.sql")) {
                    max = Math.max(max, Integer.parseInt(name.substring(1, name.indexOf("__"))));
                }
            }
        }
        assertEquals(42, max, "migration version must stay frozen at V42");

        Path gateway = platformRoot().resolve(
                "ruoyi-modules/ruoyi-ai-integration/src/main/java/org/ruoyi/aiintegration/web/AiGatewayController.java");
        assertTrue(Files.isRegularFile(gateway), "gateway source must be readable: " + gateway);
        assertFalse(Files.readString(gateway, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)
                .contains("supervisor"), "no new gateway ROUTES may mention supervisor");
    }

    // ------------------------------------------------------------ R18：无第二账本

    @Test
    @DisplayName("R18：orchestration 包无 @TableName、无第二账本/事件表写入、step/event 只经 guard")
    void zeroSecondLedgerOrProjectionTable() throws Exception {
        Path sources = platformRoot().resolve(
                "ruoyi-modules/ruoyi-ai-agent/src/main/java/com/nageoffer/ai/ragent/agent/orchestration");
        assertTrue(Files.isDirectory(sources));
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name = file.getFileName().toString();
                assertFalse(source.contains("@TableName"), name + " must not declare an entity/projection table");
                assertFalse(source.contains("run.subagent_"), name + " must not invent subagent events");
                for (String forbidden : List.of("INSERT INTO ai_tool_call", "UPDATE ai_tool_call",
                        "INSERT INTO ai_agent_checkpoint", "INSERT INTO ai_run_event", "INSERT INTO ai_run_step",
                        "UPDATE ai_run_step", "INSERT INTO ai_run ", "UPDATE ai_run ", "INSERT INTO ai_run(",
                        "UPDATE ai_run SET")) {
                    assertFalse(source.contains(forbidden), name + " must not write " + forbidden);
                }
            }
        }
        Path executor = sources.resolve("SupervisorRunExecutor.java");
        String executorSource = Files.readString(executor, StandardCharsets.UTF_8);
        assertTrue(executorSource.contains("guard.commitStep("));
        assertTrue(executorSource.contains("guard.appendEvent("));
        assertFalse(executorSource.contains("JdbcTemplate"));
        assertFalse(executorSource.contains("ai_agent_run"), "step/event writes must go through the guard");
    }

    /**
     * 定位 platform 模块根：兼容 Maven 的模块工作目录与直接跑测试的工作目录。
     *
     * <p>从 {@code user.dir} 逐级上溯，命中 {@code docs/script/sql/postgres}（platform 根）
     * 或 {@code services/platform/docs/script/sql/postgres}（工作区根）即返回。
     */
    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("docs/script/sql/postgres"))) {
                return candidate;
            }
            if (Files.isDirectory(candidate.resolve("services/platform/docs/script/sql/postgres"))) {
                return candidate.resolve("services/platform");
            }
        }
        throw new IllegalStateException("platform root not found from " + dir);
    }
}
