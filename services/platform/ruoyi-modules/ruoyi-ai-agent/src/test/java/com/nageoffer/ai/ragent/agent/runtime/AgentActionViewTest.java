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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-037A：工具动作视图（{@code GET /runs/{id}/actions} 的元素形状）的字段契约。
 *
 * <p>这个视图是 F15「工具过程」与 UNKNOWN 核对流程的**唯一数据来源**。它此前只暴露 10 个字段，
 * 把 {@code result}（工具实际做了什么）与 {@code operationKey}（副作用动作的幂等标识）丢了——
 * 于是返回一条 {@code SUCCEEDED} 的动作也看不出结果。
 *
 * <p>本判据盯三件事：新增的两列真的在；NULL 不被伪装成空对象；
 * 以及**记录组件必须逐项登记**——{@link AgentLedger.Action} 加字段时，
 * 要么暴露它、要么在 {@link #NOT_EXPOSED} 里写明为什么不暴露，不允许静默漏掉。
 */
@Tag("dev")
class AgentActionViewTest {

    /** 视图**刻意不暴露**的记录组件（其余必须全部出现在视图里）。 */
    private static final Set<String> NOT_EXPOSED = Set.of(
            "tenant",  // 内部租户标识，调用方上下文里已有
            "id",      // 与 actionId 同值，暴露两次只会让消费方困惑
            "member",  // 就是调用者本人（端点已按 member 限定）
            "runId");  // 就是路径变量 id

    private static AgentLedger.Action action(String result, String args) {
        return new AgentLedger.Action(
                "T1", "act-1", "platform:T1:11", "run-1",
                "sandbox_ticket", "p3-core-v1", args, "a".repeat(64), "ticket:42", "op-key-1",
                3, "SUCCEEDED", result, "ext-9", 7L);
    }

    @Test
    @DisplayName("视图暴露 result 与 operationKey：SUCCEEDED 的动作必须能看出结果")
    void viewExposesResultAndOperationKey() {
        Map<String, Object> view = AgentActionController.toolActionView(
                action("{\"ticketId\":\"t-1\",\"status\":\"issued\"}", "{\"mode\":\"sandbox\"}"));

        assertThat(view).containsEntry("result", Map.of("ticketId", "t-1", "status", "issued"));
        assertThat(view).containsEntry("operationKey", "op-key-1");
        assertThat(view).containsEntry("args", Map.of("mode", "sandbox"));
        assertThat(view)
                .containsEntry("actionId", "act-1")
                .containsEntry("tool", "sandbox_ticket")
                .containsEntry("toolVersion", "p3-core-v1")
                .containsEntry("target", "ticket:42")
                .containsEntry("approvalVersion", 3)
                .containsEntry("state", "SUCCEEDED")
                .containsEntry("externalId", "ext-9")
                .containsEntry("version", 7L);
        assertThat(view).containsKey("argsHash");
    }

    @Test
    @DisplayName("结果还没出来时 result 是 null，不是空对象（NULL 不伪装）")
    void nullResultStaysNull() {
        Map<String, Object> pending = AgentActionController.toolActionView(
                action(null, "{\"mode\":\"sandbox\"}"));
        assertThat(pending).containsEntry("result", null);
        assertThat(pending.get("result")).as("不能把 NULL 变成空 Map").isNull();

        Map<String, Object> blank = AgentActionController.toolActionView(
                action("   ", "{\"mode\":\"sandbox\"}"));
        assertThat(blank.get("result")).as("空白结果同样按缺失处理").isNull();
    }

    @Test
    @DisplayName("记录组件必须逐项登记：新增字段不能静默不出现在视图里")
    void everyRecordComponentIsEitherExposedOrRegistered() {
        Set<String> components = new TreeSet<>();
        for (RecordComponent component : AgentLedger.Action.class.getRecordComponents()) {
            components.add(component.getName());
        }
        assertThat(components)
                .as("锚点：必须真的读到 Action 的记录组件")
                .hasSizeGreaterThanOrEqualTo(15)
                .contains("result", "operationKey", "args", "state");

        Set<String> exposed = new LinkedHashSet<>(AgentActionController.toolActionView(
                action("{}", "{}")).keySet());
        assertThat(exposed)
                .as("锚点：视图必须有内容")
                .hasSizeGreaterThanOrEqualTo(12);

        Set<String> undecided = new TreeSet<>(components);
        undecided.removeAll(exposed);
        undecided.removeAll(NOT_EXPOSED);
        assertThat(undecided)
                .as("AgentLedger.Action 新增了记录组件而视图既没暴露、也没登记为不暴露。"
                        + "请判断它是否属于 F15 的工具过程：属于就加进 toolActionView，"
                        + "不属于就加进 NOT_EXPOSED 并写明理由。")
                .isEmpty();

        assertThat(exposed).as("暴露与不暴露两个集合不应重叠").doesNotContainAnyElementsOf(NOT_EXPOSED);
    }
}
