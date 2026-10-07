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

package com.nageoffer.ai.ragent.agent.runtime;

import com.nageoffer.ai.ragent.runtime.CanonicalJson;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RW-20 · {@code agent.run} 受理输入契约（冻结形状）的完整负例集（F10/F15）。
 *
 * <p>{@link AgentContract#validateShape} 是 {@code POST /api/ai/v1/runs} 上 {@code agent.run}
 * 的**唯一入口校验**：它决定"什么样的 Agent 请求算合法"。工作台要构造请求体，
 * 就必须知道这份契约里哪些字段<b>不许出现</b>、哪些上限是硬上限。
 *
 * <p>本类只覆盖既有实现里已经生效、但此前没有被逐条钉住的那几条（既有的
 * {@code AgentContractTest} 保持不动，本类不修改它）：
 * <ul>
 *   <li>输入对象<b>只允许</b> {@code text/mode/ticket/inheritActionId} 四个字段（无 endpoint、
 *       无凭据、无模型名、无工具名——那些都由服务端冻结目录决定）；</li>
 *   <li>{@code text} 非空且 ≤ 4096；{@code ticket.title} ≤ 120、{@code ticket.details} ≤ 1024
 *       且形状只允许这两个字段；</li>
 *   <li>{@code resourceRefs} 必须非空且**全部**是 {@code knowledge_base}
 *       （空授权集合不得扩全库；异种资源不得混入同一 Agent 运行的授权面）；</li>
 *   <li>{@code read} 模式**不得**携带 {@code ticket}/{@code inheritActionId}；
 *       {@code sandbox} 模式**必须**携带形状合法的 {@code ticket}；</li>
 *   <li>{@code inheritActionId} 必须形如 {@code act-<32 位小写十六进制>}，且**必须**同时带
 *       {@code retryOf}（显式继承不是"悄悄复用上一次的副作用"）。</li>
 * </ul>
 */
@Tag("dev")
class AgentContractShapeTest {

    private static AdmissionRequest request(String input, String version, String refs, String retry)
            throws Exception {
        return CanonicalJson.strictMapper().readValue(
                "{\"schemaVersion\":1,\"action\":\"agent.run\",\"agentVersion\":\"" + version
                        + "\",\"input\":" + input + ",\"resourceRefs\":" + refs
                        + (retry == null ? "" : ",\"retryOf\":\"" + retry + "\"") + "}",
                AdmissionRequest.class);
    }

    private static final String KB = "[{\"type\":\"knowledge_base\",\"id\":\"kb1\"}]";
    private static final String ACTION_ID = "act-0123456789abcdef0123456789abcdef";

    private static void assertRejected(String input, String refs, String retry) throws Exception {
        RunApiException e = assertThrows(RunApiException.class,
                () -> AgentContract.validateShape(request(input, "core-v1", refs, retry)));
        assertEquals(RunErrorCode.BAD_REQUEST, e.errorCode());
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    // ------------------------------------------------------------ 合法形状

    @Test
    @DisplayName("合法的 read / sandbox / 显式继承三种形状都放行")
    void wellFormedInputsAreAccepted() throws Exception {
        assertDoesNotThrow(() -> AgentContract.validateShape(
                request("{\"text\":\"q\",\"mode\":\"read\"}", "core-v1", KB, null)));
        assertDoesNotThrow(() -> AgentContract.validateShape(
                request("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"t\",\"details\":\"d\"}}",
                        "core-v1", KB, null)));
        assertDoesNotThrow(() -> AgentContract.validateShape(
                request("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"t\",\"details\":\"d\"},"
                                + "\"inheritActionId\":\"" + ACTION_ID + "\"}",
                        "core-v1", KB, "run-parent")));
    }

    // ------------------------------------------------------------ 输入对象形状

    @Test
    @DisplayName("输入只允许 text/mode/ticket/inheritActionId —— 任何「由客户端指定执行面」的字段都拒绝")
    void unknownInputFieldsAreRejected() throws Exception {
        for (String field : new String[]{"url", "apiKey", "credential", "model", "tool", "tools",
                "endpoint", "provider", "operationKey", "target", "workspace", "command", "budget",
                "systemPrompt", "ragent", "sandbox"}) {
            assertRejected("{\"text\":\"q\",\"mode\":\"read\",\"" + field + "\":\"x\"}", KB, null);
        }
    }

    @Test
    @DisplayName("text 非文本 / 空白 / 超过 4096 一律拒绝")
    void textLimitsAreEnforced() throws Exception {
        assertRejected("{\"text\":123,\"mode\":\"read\"}", KB, null);
        assertRejected("{\"mode\":\"read\"}", KB, null);
        assertRejected("{\"text\":\"   \",\"mode\":\"read\"}", KB, null);
        assertRejected("{\"text\":\"" + repeat("a", 4097) + "\",\"mode\":\"read\"}", KB, null);
        assertDoesNotThrow(() -> AgentContract.validateShape(
                request("{\"text\":\"" + repeat("a", 4096) + "\",\"mode\":\"read\"}", "core-v1", KB, null)));
    }

    @Test
    @DisplayName("mode 只允许 read / sandbox，缺席或其它值一律拒绝")
    void modeMustBeFrozen() throws Exception {
        assertRejected("{\"text\":\"q\"}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"write\"}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"READ\"}", KB, null);
    }

    // ------------------------------------------------------------ 资源面

    @Test
    @DisplayName("resourceRefs 必须非空且全部是 knowledge_base —— 空授权集合不得扩全库")
    void resourceRefsMustBeNonEmptyKnowledgeBases() throws Exception {
        for (String refs : new String[]{"[]", "null",
                "[{\"type\":\"document\",\"id\":\"d1\"}]",
                "[{\"type\":\"knowledge_base\",\"id\":\"kb1\"},{\"type\":\"document\",\"id\":\"d1\"}]"}) {
            assertThrows(RunApiException.class,
                    () -> AgentContract.validateShape(request("{\"text\":\"q\",\"mode\":\"read\"}", "core-v1", refs, null)),
                    "refs=" + refs);
        }
    }

    @Test
    @DisplayName("agentVersion 必须精确是 core-v1，不得有前后空白或大小写漂移")
    void agentVersionIsExact() throws Exception {
        for (String version : new String[]{"core-v2", "CORE-V1", " core-v1", "core-v1 ", "", "p3-core-v1"}) {
            assertThrows(RunApiException.class, () -> AgentContract.validateShape(
                    request("{\"text\":\"q\",\"mode\":\"read\"}", version, KB, null)), "version=" + version);
        }
    }

    // ------------------------------------------------------------ 模式与 ticket

    @Test
    @DisplayName("read 模式不得携带 ticket / inheritActionId")
    void readModeRejectsTicketAndInheritance() throws Exception {
        assertRejected("{\"text\":\"q\",\"mode\":\"read\",\"ticket\":{\"title\":\"t\",\"details\":\"d\"}}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"read\",\"inheritActionId\":\"" + ACTION_ID + "\"}",
                KB, "run-parent");
    }

    @Test
    @DisplayName("sandbox 模式必须携带形状合法的 ticket")
    void sandboxModeRequiresTicket() throws Exception {
        assertRejected("{\"text\":\"q\",\"mode\":\"sandbox\"}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{}}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"t\"}}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"t\",\"details\":\"d\",\"url\":\"x\"}}",
                KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"\",\"details\":\"d\"}}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"t\",\"details\":\"\"}}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"" + repeat("t", 121)
                + "\",\"details\":\"d\"}}", KB, null);
        assertRejected("{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"t\",\"details\":\""
                + repeat("d", 1025) + "\"}}", KB, null);
    }

    @Test
    @DisplayName("显式继承：id 形状必须精确，且必须同时带 retryOf")
    void inheritanceRequiresExactIdShapeAndRetryOf() throws Exception {
        String sandbox = "{\"text\":\"q\",\"mode\":\"sandbox\",\"ticket\":{\"title\":\"t\",\"details\":\"d\"},";
        for (String bad : new String[]{"act-0123", "act-0123456789ABCDEF0123456789abcdef",
                "0123456789abcdef0123456789abcdef", "act-0123456789abcdef0123456789abcde",
                "act-0123456789abcdef0123456789abcdef0"}) {
            assertRejected(sandbox + "\"inheritActionId\":\"" + bad + "\"}", KB, "run-parent");
        }
        // 形状合法但没有 retryOf：显式继承必须指名父 run
        assertRejected(sandbox + "\"inheritActionId\":\"" + ACTION_ID + "\"}", KB, null);
        // 非文本的 inheritActionId
        assertRejected(sandbox + "\"inheritActionId\":12345}", KB, "run-parent");
    }
}
