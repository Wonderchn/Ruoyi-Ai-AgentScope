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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3-BIND：{@code effectiveProfileId()} 与提示词解析**同源**（active 行 → 租户 builtin → 空）。
 */
@Tag("dev")
class AgentPromptResolverEffectiveProfileTest {

    private final AgentProfileMapper agentProfileMapper = mock(AgentProfileMapper.class);
    private final AgentPromptMapper agentPromptMapper = mock(AgentPromptMapper.class);
    private final AgentPromptCacheManager cacheManager = mock(AgentPromptCacheManager.class);
    private final PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);

    private AgentPromptResolver resolver() {
        return new AgentPromptResolver(agentProfileMapper, agentPromptMapper, cacheManager, templateRepository);
    }

    @Test
    @DisplayName("有 active 行 ⇒ 取 active 的 id")
    void activeRowWins() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(profile("agent-active")));

        assertThat(resolver().effectiveProfileId()).contains("agent-active");
    }

    @Test
    @DisplayName("无 active 行 ⇒ 回落租户 builtin 的 id")
    void builtinRowIsFallback() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(), List.of(profile("agent-builtin")));

        assertThat(resolver().effectiveProfileId()).contains("agent-builtin");
    }

    @Test
    @DisplayName("两者皆无 ⇒ 空（消费侧据此 fail-closed，不猜一个档案）")
    void emptyWhenNeitherRowExists() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(), List.of());

        assertThat(resolver().effectiveProfileId()).isEmpty();
    }

    private static AgentProfileDO profile(String id) {
        return AgentProfileDO.builder().id(id).name("n").builtin(0).active(0).deleted(0).build();
    }
}
