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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.rag.service.impl.AgentProfileAdminServiceImpl;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F09-A1 ① · 「查询该智能体的槽位配置」的 defaultAgentName 模板域补名。
 *
 * <p><b>缺陷</b>：{@code loadPrompts} 的 {@code defaultAgentName} 取自 {@code loadBuiltin()}
 * 的租户域查询——内置智能体行自 V7 起在保留租户 {@code __public_template__}
 * （P1.3a 模板域），租户过滤链看不到 ⇒ 租户态恒为 null，控制台「从默认复制」文案
 * 拿不到内置智能体名。与 R12 卡6 {@code defaultPrompt} 同源遮蔽，修法同口径：
 * 租户域优先，缺席时经 {@link PublicTemplateRepository} 只读补名。
 *
 * <p>少了本判据，删掉模板域回退不会有任何测试变红。
 */
@Tag("dev")
class AgentProfileAdminServiceImplDefaultAgentNameTest {

    private final AgentProfileMapper agentProfileMapper = mock(AgentProfileMapper.class);
    private final AgentPromptMapper agentPromptMapper = mock(AgentPromptMapper.class);
    private final AgentPromptResolver agentPromptResolver = mock(AgentPromptResolver.class);
    private final AgentPromptCacheManager cacheManager = mock(AgentPromptCacheManager.class);
    private final OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
    private final BizChangeLogContext bizChangeLogContext = mock(BizChangeLogContext.class);
    private final PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);

    private AgentProfileAdminServiceImpl service() {
        return new AgentProfileAdminServiceImpl(agentProfileMapper, agentPromptMapper, agentPromptResolver,
                cacheManager, orchestrationProperties, bizChangeLogContext, templateRepository);
    }

    @Test
    @DisplayName("租户内无内置行：defaultAgentName 经公共模板域补名（现为 null 即红）")
    void fallsBackToTemplateDomainForDefaultAgentName() {
        when(agentProfileMapper.selectById("a-1")).thenReturn(profile("a-1", 0, "我的智能体"));
        when(agentPromptResolver.loadOwnPrompts("a-1")).thenReturn(Map.of());
        when(agentProfileMapper.selectOne(any())).thenReturn(null);
        when(templateRepository.findProfiles()).thenReturn(List.of(
                new PublicTemplateRepository.TemplateProfile("t-plain", "模板普通行", "d", "", 0, 0),
                new PublicTemplateRepository.TemplateProfile("t-builtin", "内置智能体", "d", "", 1, 0)));

        AgentPromptConfigVO config = service().loadPrompts("a-1");

        assertThat(config.getDefaultAgentName())
                .as("租户态内置行缺席时必须经模板域补名（恒 null 即同源遮蔽）")
                .isEqualTo("内置智能体");
    }

    @Test
    @DisplayName("租户内已有内置行：本域名称优先，不触碰模板域")
    void tenantLocalBuiltinNameWinsWithoutTemplateRead() {
        when(agentProfileMapper.selectById("a-1")).thenReturn(profile("a-1", 0, "我的智能体"));
        when(agentPromptResolver.loadOwnPrompts("a-1")).thenReturn(Map.of());
        when(agentProfileMapper.selectOne(any())).thenReturn(profile("b-1", 1, "租户内内置"));

        AgentPromptConfigVO config = service().loadPrompts("a-1");

        assertThat(config.getDefaultAgentName()).isEqualTo("租户内内置");
        verify(templateRepository, never()).findProfiles();
    }

    @Test
    @DisplayName("两域皆无内置行：defaultAgentName 为 null（不编造名字）")
    void noBuiltinAnywhereYieldsNull() {
        when(agentProfileMapper.selectById("a-1")).thenReturn(profile("a-1", 0, "我的智能体"));
        when(agentPromptResolver.loadOwnPrompts("a-1")).thenReturn(Map.of());
        when(agentProfileMapper.selectOne(any())).thenReturn(null);
        when(templateRepository.findProfiles()).thenReturn(List.of());

        AgentPromptConfigVO config = service().loadPrompts("a-1");

        assertThat(config.getDefaultAgentName()).isNull();
    }

    private static AgentProfileDO profile(String id, int builtin, String name) {
        AgentProfileDO profile = new AgentProfileDO();
        profile.setId(id);
        profile.setName(name);
        profile.setBuiltin(builtin);
        profile.setActive(0);
        return profile;
    }
}
