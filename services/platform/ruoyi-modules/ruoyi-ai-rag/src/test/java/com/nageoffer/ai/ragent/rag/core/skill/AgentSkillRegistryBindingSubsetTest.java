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

package com.nageoffer.ai.ragent.rag.core.skill;

import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3-BIND / F11:3：运行期技能按**绑定子集**过滤（掩码三段的运行侧半边）。
 *
 * <p>判据：
 * <ul>
 *   <li><b>空绑定 ⇒ 空子集，绝不等于全量</b>（现状 {@code listEnabled()} 全量是缺陷本体）；</li>
 *   <li>子集 = 已启用技能 ∩ 该 Agent 的绑定；</li>
 *   <li>绑定前/解绑后 ⇒ 不可见（{@code findByCodeFor} 视为不存在，不泄露存在性）；</li>
 *   <li>跨 Agent 不串用：A 的绑定不得让 B 可见；</li>
 *   <li>绑定权威缺席 ⇒ fail-closed（空子集，不放行全量）。</li>
 * </ul>
 */
@Tag("dev")
class AgentSkillRegistryBindingSubsetTest {

    private static final String TENANT = "T1";

    private final AgentSkillMapper agentSkillMapper = mock(AgentSkillMapper.class);
    private final AgentSkillCacheManager cacheManager = mock(AgentSkillCacheManager.class);
    private final AgentProfileBindingService bindingService = mock(AgentProfileBindingService.class);

    private AgentSkillRegistry registry() {
        when(cacheManager.getFromCache()).thenReturn(null);
        when(agentSkillMapper.selectList(any())).thenReturn(List.of(
                skill("skill-a", 1), skill("skill-b", 2), skill("skill-c", 3)));
        return new AgentSkillRegistry(agentSkillMapper, cacheManager, bindingService);
    }

    @Test
    @DisplayName("空绑定 ⇒ 空子集（不是全量）：F11:3 的核心断言")
    void emptyBindingYieldsEmptySubsetNotWholeCatalog() {
        when(bindingService.boundSkillCodes("agent-1")).thenReturn(Set.of());

        AgentSkillRegistry registry = registry();
        assertThat(registry.listEnabledFor("agent-1")).isEmpty();
        assertThat(registry.findByCodeFor("agent-1", "skill-a")).isNull();
        // 与"全量"对照：不带 Agent 的旧入口仍是全量（其他调用方语义不变）
        assertThat(registry.listEnabled()).hasSize(3);
    }

    @Test
    @DisplayName("绑定子集 = 已启用技能 ∩ 绑定集合（顺序按 sortOrder）")
    void boundSubsetIsIntersectedWithEnabledSkills() {
        when(bindingService.boundSkillCodes("agent-1")).thenReturn(Set.of("skill-c", "skill-a"));

        assertThat(registry().listEnabledFor("agent-1"))
                .extracting(AgentSkill::skillCode)
                .containsExactly("skill-a", "skill-c");
    }

    @Test
    @DisplayName("绑定前/解绑后不可见：未绑定的技能 findByCodeFor 视为不存在")
    void unboundSkillIsInvisible() {
        when(bindingService.boundSkillCodes("agent-1")).thenReturn(Set.of("skill-a"));

        AgentSkillRegistry registry = registry();
        assertThat(registry.findByCodeFor("agent-1", "skill-a")).isNotNull();
        assertThat(registry.findByCodeFor("agent-1", "skill-b"))
                .as("未绑定的技能必须视为不存在（不泄露存在性）").isNull();
    }

    @Test
    @DisplayName("跨 Agent 不串用：A 的绑定不得让 B 可见")
    void crossAgentSubsetDoesNotLeak() {
        when(bindingService.boundSkillCodes("agent-1")).thenReturn(Set.of("skill-a"));
        when(bindingService.boundSkillCodes("agent-2")).thenReturn(Set.of("skill-b"));

        AgentSkillRegistry registry = registry();
        assertThat(registry.listEnabledFor("agent-2")).extracting(AgentSkill::skillCode).containsExactly("skill-b");
        assertThat(registry.findByCodeFor("agent-2", "skill-a")).isNull();
    }

    @Test
    @DisplayName("绑定权威缺席 ⇒ fail-closed：空子集，不放行全量")
    void missingBindingAuthorityFailsClosed() {
        AgentSkillRegistry registry = new AgentSkillRegistry(agentSkillMapper, cacheManager, null);
        when(cacheManager.getFromCache()).thenReturn(null);
        when(agentSkillMapper.selectList(any())).thenReturn(List.of(skill("skill-a", 1)));

        assertThat(registry.listEnabledFor("agent-1")).isEmpty();
        assertThat(registry.findByCodeFor("agent-1", "skill-a")).isNull();
    }

    private static AgentSkillDO skill(String code, int sortOrder) {
        return AgentSkillDO.builder()
                .id(code)
                .skillCode(code)
                .name(code)
                .description("d")
                .content("c")
                .enabled(1)
                .sortOrder(sortOrder)
                .deleted(0)
                .build();
    }
}
