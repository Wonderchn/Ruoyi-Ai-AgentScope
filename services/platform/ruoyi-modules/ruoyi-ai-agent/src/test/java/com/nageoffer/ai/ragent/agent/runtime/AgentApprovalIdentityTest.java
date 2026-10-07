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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RW-20 · F15「审批版本与参数 hash」的客户端可核对契约（{@code POST /api/ai/v1/runs/{id}/approvals}）。
 *
 * <p>审批接口是"人确认一次受控写"的唯一入口，因此它的判定必须能被<b>独立</b>验证，
 * 而不是只能靠完整容器 + 真库间接覆盖。本判据钉住两个纯判定：
 * <ul>
 *   <li>{@link AgentActionController#approvalRequestWellFormed}：请求体必须<b>恰好</b>六个字段
 *       （{@code actionId/argsHash/toolVersion/target/approvalVersion/decision}）、
 *       {@code approvalVersion} 是整数、{@code decision ∈ {ALLOW, DENY}}。
 *       缺字段<b>不</b>由服务端补 —— "调用方必须复述它看到的那条提案"正是审批语义本身；</li>
 *   <li>{@link AgentActionController#approvalMatchesProposal}：{@code argsHash}/{@code toolVersion}/
 *       {@code target}/{@code approvalVersion} 逐项相等且工具是受控写工具 {@code sandbox_ticket}。
 *       <b>参数 hash 变了就必须拒</b>：否则"批准时看到的参数"与"执行时用的参数"可以不同。</li>
 * </ul>
 *
 * <p>外显分工（未在本判据断言、由控制器按固定顺序执行并记入 RW-20 报告）：
 * 不属于本人或不属于本 run ⇒ <b>404</b>（不泄露存在性）；缺字段/非法 decision ⇒ <b>400</b>；
 * 其余任何"提案对不上"或"状态/过期不匹配" ⇒ <b>409</b>。**按 HTTP 409 判"审批过期"是错的**——
 * 409 下至少有 {@code VERSION_CONFLICT} / {@code RUN_STATE_CONFLICT} / {@code APPROVAL_EXPIRED} 三种成因。
 */
@Tag("dev")
class AgentApprovalIdentityTest {

    private static final String ACTION_ID = "act-0123456789abcdef0123456789abcdef";
    private static final String ARGS_HASH = "b7d1b0a1c2d3e4f5";
    private static final String TOOL_VERSION = "v1";
    private static final String TARGET = "sandbox";

    private static JsonNode request(String json) {
        return AgentModelAdapter.parse(json);
    }

    private static String body(String decision, String argsHash, String toolVersion, String target,
                               int approvalVersion) {
        return "{\"actionId\":\"" + ACTION_ID + "\",\"argsHash\":\"" + argsHash
                + "\",\"toolVersion\":\"" + toolVersion + "\",\"target\":\"" + target
                + "\",\"approvalVersion\":" + approvalVersion + ",\"decision\":\"" + decision + "\"}";
    }

    private static AgentLedger.Action proposal(String tool, String argsHash, String toolVersion,
                                               String target, int approvalVersion) {
        return new AgentLedger.Action("t1", ACTION_ID, "platform:t1:1001", "run-1", tool, toolVersion,
                "{\"title\":\"t\",\"details\":\"d\"}", argsHash, target, "op-1", approvalVersion,
                "PROPOSED", null, null, 1L);
    }

    private static AgentLedger.Action sandboxProposal() {
        return proposal("sandbox_ticket", ARGS_HASH, TOOL_VERSION, TARGET, 1);
    }

    // ------------------------------------------------------------ 请求体形状

    @Test
    @DisplayName("恰好六个字段 + 整数 approvalVersion + ALLOW/DENY 才算合法请求体")
    void wellFormedRequiresExactlySixFields() {
        assertTrue(AgentActionController.approvalRequestWellFormed(request(body("ALLOW", ARGS_HASH, TOOL_VERSION, TARGET, 1))));
        assertTrue(AgentActionController.approvalRequestWellFormed(request(body("DENY", ARGS_HASH, TOOL_VERSION, TARGET, 1))));
    }

    @Test
    @DisplayName("缺字段 / 非整数 approvalVersion / 小写或未知 decision ⇒ 一律非法（服务端不替调用方补）")
    void malformedRequestsAreRejected() {
        assertFalse(AgentActionController.approvalRequestWellFormed(request(
                "{\"actionId\":\"" + ACTION_ID + "\",\"argsHash\":\"" + ARGS_HASH
                        + "\",\"toolVersion\":\"" + TOOL_VERSION + "\",\"target\":\"" + TARGET
                        + "\",\"decision\":\"ALLOW\"}")), "缺 approvalVersion");
        assertFalse(AgentActionController.approvalRequestWellFormed(request(body("ALLOW", ARGS_HASH, TOOL_VERSION, TARGET, 1)
                .replace("\"decision\":\"ALLOW\"", "\"decision\":\"ALLOW\",\"extra\":1"))), "多一个字段");
        assertFalse(AgentActionController.approvalRequestWellFormed(request(
                body("ALLOW", ARGS_HASH, TOOL_VERSION, TARGET, 1).replace("\"approvalVersion\":1",
                        "\"approvalVersion\":\"1\""))), "approvalVersion 不是整数");
        assertFalse(AgentActionController.approvalRequestWellFormed(request(body("allow", ARGS_HASH, TOOL_VERSION, TARGET, 1))),
                "decision 大小写必须精确");
        assertFalse(AgentActionController.approvalRequestWellFormed(request(body("MAYBE", ARGS_HASH, TOOL_VERSION, TARGET, 1))),
                "未知 decision");
        assertFalse(AgentActionController.approvalRequestWellFormed(request("{}")));
        assertFalse(AgentActionController.approvalRequestWellFormed(null));
    }

    // ------------------------------------------------------------ 提案身份

    @Test
    @DisplayName("逐项相等且工具是 sandbox_ticket 才算指向同一条提案")
    void matchesOnlyWhenEveryProposalFieldAgrees() {
        assertTrue(AgentActionController.approvalMatchesProposal(sandboxProposal(),
                request(body("ALLOW", ARGS_HASH, TOOL_VERSION, TARGET, 1))));
    }

    @Test
    @DisplayName("参数 hash 变了必须拒（批准时看到的参数 ≠ 执行时用的参数）")
    void changedArgsHashIsRejected() {
        assertFalse(AgentActionController.approvalMatchesProposal(sandboxProposal(),
                request(body("ALLOW", "00000000deadbeef", TOOL_VERSION, TARGET, 1))));
    }

    @Test
    @DisplayName("toolVersion / target / approvalVersion 任一项不符即拒（含乐观锁代际）")
    void anyOtherFieldMismatchIsRejected() {
        assertFalse(AgentActionController.approvalMatchesProposal(sandboxProposal(),
                request(body("ALLOW", ARGS_HASH, "v2", TARGET, 1))), "toolVersion");
        assertFalse(AgentActionController.approvalMatchesProposal(sandboxProposal(),
                request(body("ALLOW", ARGS_HASH, TOOL_VERSION, "other-target", 1))), "target");
        assertFalse(AgentActionController.approvalMatchesProposal(sandboxProposal(),
                request(body("ALLOW", ARGS_HASH, TOOL_VERSION, TARGET, 2))), "approvalVersion");
    }

    @Test
    @DisplayName("非受控写工具不得走审批路径；提案/请求缺失一律拒（fail-closed，不因 NULL 变成 500）")
    void nonSandboxToolAndNullsAreRejected() {
        assertFalse(AgentActionController.approvalMatchesProposal(
                proposal("kb_search", ARGS_HASH, TOOL_VERSION, TARGET, 1),
                request(body("ALLOW", ARGS_HASH, TOOL_VERSION, TARGET, 1))), "kb_search 不是受控写工具");
        // 列的 NULL 不得被解引用成 500：读不出可比对的字段与比对不上在安全后果上一致。
        assertFalse(AgentActionController.approvalMatchesProposal(
                proposal("sandbox_ticket", null, TOOL_VERSION, TARGET, 1),
                request(body("ALLOW", ARGS_HASH, TOOL_VERSION, TARGET, 1))));
        assertFalse(AgentActionController.approvalMatchesProposal(null,
                request(body("ALLOW", ARGS_HASH, TOOL_VERSION, TARGET, 1))));
        assertFalse(AgentActionController.approvalMatchesProposal(sandboxProposal(), null));
    }
}
