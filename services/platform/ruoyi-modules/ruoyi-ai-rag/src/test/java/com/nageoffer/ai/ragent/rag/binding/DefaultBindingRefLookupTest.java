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

import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefType;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P3-BIND Stage C 前置：{@code RefLookup} 生产适配（既有资产面在线核引用）。
 *
 * <p>判据：SKILL = 存在且 {@code enabled=1}；MCP = 意图树存在该 {@code mcp_tool_id} 且启用；
 * **MODEL 保持 fail-closed**（模型目录不在 rag 侧：本层无法核验 ⇒ 拒绝而非放行，见 Stage A §A.5）。
 */
@Tag("dev")
class DefaultBindingRefLookupTest {

    private static final String TENANT = "T1";

    private final AgentSkillMapper agentSkillMapper = mock(AgentSkillMapper.class);
    private final IntentNodeMapper intentNodeMapper = mock(IntentNodeMapper.class);

    private DefaultBindingRefLookup lookup() {
        return new DefaultBindingRefLookup(agentSkillMapper, intentNodeMapper);
    }

    @Test
    @DisplayName("SKILL：存在且启用 ⇒ 通过")
    void acceptsEnabledSkill() {
        when(agentSkillMapper.selectCount(any())).thenReturn(1L);
        assertThat(lookup().existsEnabled(TENANT, RefType.SKILL, "skill-a")).isTrue();
    }

    @Test
    @DisplayName("SKILL：不存在或已停用 ⇒ 拒绝")
    void rejectsMissingOrDisabledSkill() {
        when(agentSkillMapper.selectCount(any())).thenReturn(0L);
        assertThat(lookup().existsEnabled(TENANT, RefType.SKILL, "skill-a")).isFalse();
    }

    @Test
    @DisplayName("MCP：意图树存在该工具且启用 ⇒ 通过")
    void acceptsEnabledMcpNode() {
        when(intentNodeMapper.selectCount(any())).thenReturn(2L);
        assertThat(lookup().existsEnabled(TENANT, RefType.MCP, "mcp-a")).isTrue();
    }

    @Test
    @DisplayName("MCP：不存在或停用 ⇒ 拒绝")
    void rejectsMissingOrDisabledMcpNode() {
        when(intentNodeMapper.selectCount(any())).thenReturn(0L);
        assertThat(lookup().existsEnabled(TENANT, RefType.MCP, "mcp-a")).isFalse();
    }

    @Test
    @DisplayName("MODEL：端口缺席 ⇒ fail-closed 拒绝（不触达任何资产查询）")
    void modelIsFailClosedWhenPortAbsent() {
        assertThat(lookup().existsEnabled(TENANT, RefType.MODEL, "model-1")).isFalse();
        verifyNoInteractions(agentSkillMapper, intentNodeMapper);
    }

    @Test
    @DisplayName("MODEL：端口到场 ⇒ 按端口结论（M-1）")
    void modelConsultsPortWhenPresent() {
        AgentProfileBindingService.ModelRefLookup port = mock(AgentProfileBindingService.ModelRefLookup.class);
        when(port.existsEnabled(TENANT, "model-1")).thenReturn(true);
        when(port.existsEnabled(TENANT, "model-x")).thenReturn(false);

        DefaultBindingRefLookup lookup = new DefaultBindingRefLookup(agentSkillMapper, intentNodeMapper, provider(port));
        assertThat(lookup.existsEnabled(TENANT, RefType.MODEL, "model-1")).isTrue();
        assertThat(lookup.existsEnabled(TENANT, RefType.MODEL, "model-x")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<AgentProfileBindingService.ModelRefLookup> provider(
            AgentProfileBindingService.ModelRefLookup lookup) {
        org.springframework.beans.factory.ObjectProvider<AgentProfileBindingService.ModelRefLookup> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(lookup);
        return provider;
    }

    @Test
    @DisplayName("空引用/空类型：直接拒绝，不查库")
    void rejectsBlankReferenceWithoutQuerying() {
        assertThat(lookup().existsEnabled(TENANT, RefType.SKILL, "  ")).isFalse();
        assertThat(lookup().existsEnabled(TENANT, null, "skill-a")).isFalse();
        verify(agentSkillMapper, never()).selectCount(any());
    }
}
