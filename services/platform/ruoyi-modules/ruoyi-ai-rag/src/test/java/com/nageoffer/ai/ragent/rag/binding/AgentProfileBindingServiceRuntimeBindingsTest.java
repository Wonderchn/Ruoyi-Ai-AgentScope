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

package com.nageoffer.ai.ragent.rag.binding;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.BindingReference;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefLookup;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefType;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RuntimeBindings;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileBindingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileBindingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F09:3-C：运行期绑定视图（{@link AgentProfileBindingService#runtimeBindings(String)}）离线判据。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li><b>一次读取取齐四类</b>：MODEL/ORCHESTRATION/SKILL/MCP 共用一个 {@code bindingsOf} 查询快照；</li>
 *   <li><b>多条取 sortOrder 首条</b>：MODEL/ORCHESTRATION 有重复绑定时按读取顺序取首条（并 WARN）；</li>
 *   <li><b>缺省语义</b>：无 ORCHESTRATION 行 ⇒ {@code REACT}；无任何绑定 ⇒ 空集合 + 空模型；</li>
 *   <li><b>版本一致</b>：{@code bindingVersion} 与既有确定性哈希同源。</li>
 * </ol>
 */
@Tag("dev")
class AgentProfileBindingServiceRuntimeBindingsTest {

    private static final String TENANT = "T1";
    private static final String AGENT = "agent-1";

    private final AgentProfileBindingMapper bindingMapper = mock(AgentProfileBindingMapper.class);

    private AgentProfileBindingService service() {
        return new AgentProfileBindingService(bindingMapper, provider());
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of("agent.write"), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("四类取齐且只读一次：MODEL/ORCH/SKILL/MCP 来自同一绑定快照")
    void collectsAllFourTypesFromOneRead() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(
                row(RefType.MODEL, "deepseek-chat", 0),
                row(RefType.ORCHESTRATION, "PLAN_ACT", 1),
                row(RefType.SKILL, "skill-a", 2),
                row(RefType.SKILL, "skill-b", 3),
                row(RefType.MCP, "mcp-a", 4)));

        RuntimeBindings bindings = service().runtimeBindings(AGENT);

        assertThat(bindings.modelId()).contains("deepseek-chat");
        assertThat(bindings.orchestrationMode()).isEqualTo("PLAN_ACT");
        assertThat(bindings.skillCodes()).containsExactlyInAnyOrder("skill-a", "skill-b");
        assertThat(bindings.mcpToolIds()).containsExactly("mcp-a");
        assertThat(bindings.bindingVersion()).isEqualTo(service().bindingVersion(List.of(
                new BindingReference(RefType.MODEL, "deepseek-chat"),
                new BindingReference(RefType.ORCHESTRATION, "PLAN_ACT"),
                new BindingReference(RefType.SKILL, "skill-a"),
                new BindingReference(RefType.SKILL, "skill-b"),
                new BindingReference(RefType.MCP, "mcp-a"))));
        verify(bindingMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("缺省：无 ORCHESTRATION ⇒ REACT；无 MODEL ⇒ 空 Optional；空绑定 ⇒ 空集合")
    void defaultsToReactAndEmptyCollectionsWhenUnbound() {
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        RuntimeBindings bindings = service().runtimeBindings(AGENT);

        assertThat(bindings.modelId()).isEmpty();
        assertThat(bindings.orchestrationMode()).isEqualTo("REACT");
        assertThat(bindings.skillCodes()).isEmpty();
        assertThat(bindings.mcpToolIds()).isEmpty();
        assertThat(bindings.bindingVersion()).isEqualTo(service().bindingVersion(List.of()));
    }

    @Test
    @DisplayName("多条 MODEL/ORCHESTRATION：按 sortOrder 首条生效（运行侧不静默混用）")
    void takesFirstModelAndOrchestrationWhenMultipleBound() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(
                row(RefType.MODEL, "model-first", 0),
                row(RefType.MODEL, "model-second", 1),
                row(RefType.ORCHESTRATION, "PLAN_ACT", 2),
                row(RefType.ORCHESTRATION, "REACT", 3)));

        RuntimeBindings bindings = service().runtimeBindings(AGENT);

        assertThat(bindings.modelId()).contains("model-first");
        assertThat(bindings.orchestrationMode()).isEqualTo("PLAN_ACT");
    }

    @Test
    @DisplayName("未知编排码（历史脏行）：读取即拒绝，不回落默认模式")
    void unknownOrchestrationModeFailsClosedOnRead() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(
                row(RefType.ORCHESTRATION, "SOME_SCRIPT", 0)));

        assertThatThrownBy(() -> service().runtimeBindings(AGENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOME_SCRIPT");
    }

    private static AgentProfileBindingDO row(RefType type, String refId, int sortOrder) {
        return AgentProfileBindingDO.builder()
                .id(type.name() + ":" + refId)
                .tenantId(TENANT)
                .agentProfileId(AGENT)
                .refType(type.name())
                .refId(refId)
                .enabled(1)
                .sortOrder(sortOrder)
                .deleted(0)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RefLookup> provider() {
        ObjectProvider<RefLookup> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
