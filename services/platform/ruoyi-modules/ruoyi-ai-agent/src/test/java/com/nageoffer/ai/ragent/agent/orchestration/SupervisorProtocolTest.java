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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F14:0/1/3 · Supervisor 冻结协议（§2.1/§2.3）。
 *
 * <p>覆盖 R1（输入/名册白名单）、R8（父非法 JSON）、R9（子工具字段）与
 * 父/子严格 JSON 的重复键/尾随 token/上限。执行器只信任这里的解析结果。
 */
@Tag("dev")
class SupervisorProtocolTest {

    private static final String VALID_INPUT = "{\"text\":\"question\",\"agents\":["
            + "{\"name\":\"analyst\",\"instruction\":\"analyze\"},"
            + "{\"name\":\"critic\",\"instruction\":\"critique\"}]}";

    private static JsonNode json(String text) {
        try {
            return CanonicalJson.strictMapper().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SupervisorProtocol.Roster roster() {
        return SupervisorProtocol.input(VALID_INPUT).roster();
    }

    private static void assertBad(Runnable action) {
        RunApiException exception = assertThrows(RunApiException.class, action::run);
        assertEquals(RunErrorCode.BAD_REQUEST, exception.errorCode());
    }

    private static void assertViolation(String code, Runnable action) {
        SupervisorProtocol.Violation violation = assertThrows(SupervisorProtocol.Violation.class, action::run);
        assertEquals(code, violation.errorCode());
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    // ------------------------------------------------------------ 受理输入（R1）

    @Test
    @DisplayName("合法输入：text + 2..3 个唯一 alias 组成名册")
    void validInputIsAccepted() {
        SupervisorProtocol.validateInput(json(VALID_INPUT));
        SupervisorProtocol.Input input = SupervisorProtocol.input(VALID_INPUT);
        assertEquals("question", input.text());
        assertEquals(2, input.roster().agents().size());
        assertEquals("analyst", input.roster().agents().get(0).name());
        assertTrue(input.roster().contains("critic"));
        assertFalse(input.roster().contains("ghost"));
    }

    @Test
    @DisplayName("顶层只允许 text/agents/appId：模型/凭据/工具/预算等客户端字段一律 400")
    void unknownInputFieldsAreRejected() {
        for (String field : new String[]{"model", "provider", "tool", "tools", "resourceRefs", "budget",
                "systemPrompt", "mode", "agentVersion", "action", "maxSteps"}) {
            assertBad(() -> SupervisorProtocol.validateInput(json(
                    "{\"text\":\"q\",\"agents\":[{\"name\":\"analyst\",\"instruction\":\"a\"},"
                            + "{\"name\":\"critic\",\"instruction\":\"c\"}],\"" + field + "\":\"x\"}")));
        }
    }

    @Test
    @DisplayName("名册 2..3 个、alias 形状唯一、instruction 非空 ≤1024")
    void rosterShapeIsFrozen() {
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"q\",\"agents\":[{\"name\":\"analyst\",\"instruction\":\"a\"}]}")));
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"q\",\"agents\":[{\"name\":\"a1\",\"instruction\":\"a\"},"
                        + "{\"name\":\"a2\",\"instruction\":\"a\"},{\"name\":\"a3\",\"instruction\":\"a\"},"
                        + "{\"name\":\"a4\",\"instruction\":\"a\"}]}")));
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"q\",\"agents\":[{\"name\":\"analyst\",\"instruction\":\"a\"},"
                        + "{\"name\":\"analyst\",\"instruction\":\"b\"}]}")));
        for (String name : new String[]{"Analyst", "a", "1analyst", "analyst!", repeat("a", 17)}) {
            assertBad(() -> SupervisorProtocol.validateInput(json(
                    "{\"text\":\"q\",\"agents\":[{\"name\":\"" + name + "\",\"instruction\":\"a\"},"
                            + "{\"name\":\"critic\",\"instruction\":\"c\"}]}")));
        }
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"q\",\"agents\":[{\"name\":\"analyst\",\"instruction\":\"  \"},"
                        + "{\"name\":\"critic\",\"instruction\":\"c\"}]}")));
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"q\",\"agents\":[{\"name\":\"analyst\",\"instruction\":\""
                        + repeat("i", 1025) + "\"},{\"name\":\"critic\",\"instruction\":\"c\"}]}")));
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"q\",\"agents\":[{\"name\":\"analyst\",\"instruction\":\"a\",\"model\":\"x\"},"
                        + "{\"name\":\"critic\",\"instruction\":\"c\"}]}")));
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"q\",\"agents\":{\"name\":\"analyst\"}}")));
        assertBad(() -> SupervisorProtocol.validateInput(json("{\"agents\":[]}")));
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"" + repeat("t", 4097) + "\",\"agents\":["
                        + "{\"name\":\"analyst\",\"instruction\":\"a\"},"
                        + "{\"name\":\"critic\",\"instruction\":\"c\"}]}")));
    }

    // ------------------------------------------------------------ 父输出（R8）

    @Test
    @DisplayName("父输出严格 JSON：重复键/尾随 token/未知字段/未知 kind 一律 INVALID_ROUTE")
    void parentStrictJsonIsEnforced() {
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"delegate\",\"agent\":\"analyst\",\"task\":\"t\",\"reason\":\"r\","
                        + "\"kind\":\"final\",\"answer\":\"a\"}", roster()));
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"final\",\"answer\":\"a\"} {}", roster()));
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"delegate\",\"agent\":\"analyst\",\"task\":\"t\",\"reason\":\"r\",\"model\":\"x\"}",
                roster()));
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"broadcast\",\"agent\":\"analyst\"}", roster()));
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(
                "\"just text\"", roster()));
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(null, roster()));
    }

    @Test
    @DisplayName("父委派：alias 必须在名册内，task ≤4096，reason ≤512，未知 alias 不回落")
    void parentDelegationFieldsAreFrozen() {
        SupervisorProtocol.ParentDecision decision = SupervisorProtocol.parseParentReply(
                "{\"kind\":\"delegate\",\"agent\":\"analyst\",\"task\":\"do it\",\"reason\":\"because\"}", roster());
        assertEquals("delegate", decision.kind());
        assertEquals("analyst", decision.agent());
        assertEquals("do it", decision.task());
        assertViolation(SupervisorProtocol.UNKNOWN_AGENT, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"delegate\",\"agent\":\"ghost\",\"task\":\"t\",\"reason\":\"r\"}", roster()));
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"delegate\",\"agent\":\"analyst\",\"task\":\"" + repeat("t", 4097)
                        + "\",\"reason\":\"r\"}", roster()));
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"delegate\",\"agent\":\"analyst\",\"task\":\"t\",\"reason\":\""
                        + repeat("r", 513) + "\"}", roster()));
    }

    @Test
    @DisplayName("父最终：answer 非空 ≤16384；空回答是 EMPTY_REPLY，超长是 INVALID_ROUTE")
    void parentFinalBounds() {
        assertEquals("answer", SupervisorProtocol.parseParentReply(
                "{\"kind\":\"final\",\"answer\":\"answer\"}", roster()).answer());
        assertViolation(SupervisorProtocol.EMPTY_REPLY, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"final\",\"answer\":\"   \"}", roster()));
        assertViolation(SupervisorProtocol.INVALID_ROUTE, () -> SupervisorProtocol.parseParentReply(
                "{\"kind\":\"final\",\"answer\":\"" + repeat("a", 16385) + "\"}", roster()));
    }

    // ------------------------------------------------------------ 子输出（R9）

    @Test
    @DisplayName("子输出只允许 final；任何工具字段/工具意图 → CHILD_TOOL_DENIED")
    void childToolIntentIsDenied() {
        for (String tool : new String[]{"tool", "tools", "tool_call", "toolCall", "tool_calls", "args",
                "arguments", "function", "functions"}) {
            assertViolation(SupervisorProtocol.CHILD_TOOL_DENIED, () -> SupervisorProtocol.parseChildReply(
                    "{\"kind\":\"final\",\"answer\":\"a\",\"" + tool + "\":" + ("args".equals(tool)
                            || "arguments".equals(tool) ? "{}" : "\"kb_search\"") + "}"));
        }
        assertViolation(SupervisorProtocol.CHILD_TOOL_DENIED, () -> SupervisorProtocol.parseChildReply(
                "{\"kind\":\"tool\",\"tool\":\"kb_search\",\"args\":{}}"));
    }

    @Test
    @DisplayName("子输出形状：重复键/尾随/委派/超长 → CHILD_INVALID；空回答 → EMPTY_REPLY")
    void childShapeIsFrozen() {
        assertEquals("child answer", SupervisorProtocol.parseChildReply(
                "{\"kind\":\"final\",\"answer\":\"child answer\"}").answer());
        assertViolation(SupervisorProtocol.CHILD_INVALID, () -> SupervisorProtocol.parseChildReply(
                "{\"kind\":\"final\",\"answer\":\"a\",\"kind\":\"final\"}"));
        assertViolation(SupervisorProtocol.CHILD_INVALID, () -> SupervisorProtocol.parseChildReply(
                "{\"kind\":\"final\",\"answer\":\"a\"} trailing"));
        assertViolation(SupervisorProtocol.CHILD_INVALID, () -> SupervisorProtocol.parseChildReply(
                "{\"kind\":\"delegate\",\"agent\":\"critic\",\"task\":\"t\",\"reason\":\"r\"}"));
        assertViolation(SupervisorProtocol.CHILD_INVALID, () -> SupervisorProtocol.parseChildReply(
                "{\"kind\":\"final\",\"answer\":\"" + repeat("a", 8193) + "\"}"));
        assertViolation(SupervisorProtocol.CHILD_INVALID, () -> SupervisorProtocol.parseChildReply(
                "{\"kind\":\"final\",\"answer\":123}"));
        assertViolation(SupervisorProtocol.EMPTY_REPLY, () -> SupervisorProtocol.parseChildReply(
                "{\"kind\":\"final\",\"answer\":\"\"}"));
    }

    // ------------------------------------------------------------ transcript / ref / prompt

    @Test
    @DisplayName("transcript = admitted text + [child alias] answer；route ref 带 taskHash 与 policy")
    void transcriptAndRefsMatchTheFrozenShape() {
        List<String[]> transcript = List.of(new String[]{"analyst", "first"}, new String[]{"critic", "second"});
        String prompt = SupervisorProtocol.parentUserPrompt("question", transcript);
        assertTrue(prompt.startsWith("Admitted input: question"));
        assertTrue(prompt.contains("[child analyst] first"));
        assertTrue(prompt.contains("[child critic] second"));
        assertEquals(prompt.length(), SupervisorProtocol.transcriptLength("question", transcript));

        Map<String, Object> route = SupervisorProtocol.routeRef("analyst", "do it", "reason", "call-1", "model-1");
        assertEquals("analyst", route.get("agent"));
        assertEquals(SupervisorProtocol.hash("do it"), route.get("taskHash"));
        assertEquals("reason", route.get("reason"));
        assertEquals(SupervisorProtocol.POLICY, route.get("policy"));
        assertEquals("call-1", route.get("callId"));
        assertEquals("model-1", route.get("model"));

        Map<String, Object> child = SupervisorProtocol.childRef("answer", "call-2", "deepseek", "model-1");
        assertEquals("answer", child.get("answer"));
        assertEquals("call-2", child.get("callId"));
        assertEquals("deepseek", child.get("provider"));
        assertEquals("model-1", child.get("model"));

        Map<String, Object> fin = SupervisorProtocol.finalRef("done",
                List.of(SupervisorProtocol.delegation("analyst", "child:analyst:1", "call-2")), false);
        assertEquals("done", fin.get("answer"));
        assertEquals(false, fin.get("synthetic"));
        assertEquals(SupervisorProtocol.ENGINE, fin.get("engineVersion"));
        assertEquals(SupervisorProtocol.VERSION, fin.get("supervisorVersion"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> delegations = (List<Map<String, Object>>) fin.get("delegations");
        assertEquals("child:analyst:1", delegations.get(0).get("stepId"));
    }

    @Test
    @DisplayName("提示词只列名册 alias 与 instruction；子提示词明确禁止工具与委派")
    void promptsListOnlyAdmittedAgents() {
        String parent = SupervisorProtocol.parentSystemPrompt(roster());
        assertTrue(parent.contains("analyst"));
        assertTrue(parent.contains("critic"));
        assertTrue(parent.contains("delegate"));
        assertTrue(parent.contains("Never use tools"));
        String child = SupervisorProtocol.childSystemPrompt(new SupervisorProtocol.Agent("analyst", "analyze"));
        assertTrue(child.contains("analyst"));
        assertTrue(child.contains("analyze"));
        assertTrue(child.contains("Never call tools"));
        assertTrue(child.contains("never delegate"));
    }
}
