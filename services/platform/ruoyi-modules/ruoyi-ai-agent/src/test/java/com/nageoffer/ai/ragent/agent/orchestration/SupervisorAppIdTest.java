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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F14:2：Supervisor 受理输入新增可选 {@code appId} 的白名单/形状判据。
 *
 * <p>D2 的语义是"无 appId 旧行为不变，有 appId 才复核"。因此这里只钉形状：
 * <ul>
 *   <li>{@code appId} 合法形状放行，未出现时旧输入逐字节等价；</li>
 *   <li>非字符串/空白/超长/非法字符一律 400（形状错误在受理事务之前拒绝）；</li>
 *   <li>未知字段仍然 400——新增一个键不等于放开顶层键集；</li>
 *   <li>执行期 {@code input()} 只取 text/agents，appId 不进入名册/提示词。</li>
 * </ul>
 */
@Tag("dev")
class SupervisorAppIdTest {

    private static final String APP_ID = "0123456789abcdef0123456789abcdef";
    private static final String AGENTS = "[{\"name\":\"analyst\",\"instruction\":\"analyze\"},"
            + "{\"name\":\"critic\",\"instruction\":\"critique\"}]";

    private static JsonNode input(String appIdLiteral) {
        String suffix = appIdLiteral == null ? "" : ",\"appId\":" + appIdLiteral;
        return json("{\"text\":\"q\",\"agents\":" + AGENTS + suffix + "}");
    }

    private static JsonNode json(String literal) {
        try {
            return CanonicalJson.strictMapper().readTree(literal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("appId 可选：省略与合法 32 位 hex 都放行")
    void appIdIsOptionalAndShapeChecked() {
        SupervisorProtocol.validateInput(input(null));
        SupervisorProtocol.validateInput(input("\"" + APP_ID + "\""));
    }

    @Test
    @DisplayName("appId 非空字符串以外的形状一律 400")
    void invalidAppIdIsRejected() {
        for (String literal : new String[]{
                "\"\"", "\"   \"", "123", "true", "{\"x\":\"y\"}", "[]",
                "\"0123456789abcdef0123456789abcdef0\"",
                "\"0123456789abcdef/0123456789abcdef\"",
                "\"0123456789abcdef 0123456789abcdef\""}) {
            assertBad(() -> SupervisorProtocol.validateInput(input(literal)));
        }
    }

    @Test
    @DisplayName("新增 appId 不放宽其它顶层字段：model/tools 等仍 400")
    void appIdDoesNotOpenOtherFields() {
        assertBad(() -> SupervisorProtocol.validateInput(json(
                "{\"text\":\"q\",\"agents\":" + AGENTS + ",\"appId\":\"" + APP_ID + "\",\"model\":\"x\"}")));
    }

    @Test
    @DisplayName("执行期 input() 只解析 text/agents：appId 不进入名册与提示词")
    void runtimeInputIgnoresAppId() {
        SupervisorProtocol.Input parsed = SupervisorProtocol.input(
                "{\"text\":\"q\",\"agents\":" + AGENTS + ",\"appId\":\"" + APP_ID + "\"}");

        assertEquals("q", parsed.text());
        assertEquals(2, parsed.roster().agents().size());
        assertTrue(parsed.roster().contains("analyst"));
        assertTrue(parsed.roster().contains("critic"));
    }

    @Test
    @DisplayName("roster(JsonNode) 与受理同一套约束：2..3 个、alias 唯一且合法")
    void rosterHelperMirrorsAdmissionRules() {
        assertEquals(2, SupervisorProtocol.roster(input(null).get("agents")).agents().size());
        assertBad(() -> SupervisorProtocol.roster(jsonArray(
                "[{\"name\":\"analyst\",\"instruction\":\"a\"}]")));
        assertBad(() -> SupervisorProtocol.roster(jsonArray(
                "[{\"name\":\"analyst\",\"instruction\":\"a\"},"
                        + "{\"name\":\"analyst\",\"instruction\":\"b\"}]")));
        assertBad(() -> SupervisorProtocol.roster(jsonArray(
                "[{\"name\":\"Analyst\",\"instruction\":\"a\"},"
                        + "{\"name\":\"critic\",\"instruction\":\"c\"}]")));
    }

    private static JsonNode jsonArray(String literal) {
        try {
            return CanonicalJson.strictMapper().readTree(literal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertBad(Runnable body) {
        RunApiException exception = assertThrows(RunApiException.class, body::run);
        assertEquals(RunErrorCode.BAD_REQUEST, exception.errorCode());
    }
}
