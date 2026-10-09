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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R12 卡6 · F-W3-1：「取槽位默认值」的公共模板域回退。
 *
 * <p><b>为什么必须有这一条。</b>内置智能体与其 10 个提示词槽位的种子行在 V2 起就转入保留租户
 * {@code __public_template__}（P1.3a 模板域）；控制台读端做的是普通租户过滤查询 ⇒
 * 租户内恒查不到内置行，「取槽位默认值」恒返回空体（W3 复核 P1：e13 返回 {@code data:""}）。
 * 修复后：租户内自配优先，缺位时经 {@link PublicTemplateRepository} 按保留租户值显式回退。
 * 少了本判据，删掉回退不会有任何测试变红。
 */
@Tag("dev")
class AgentProfileAdminServiceImplDefaultPromptTest {

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
    @DisplayName("租户内无内置行：经公共模板域回退返回内置默认内容（卡6 判据）")
    void fallsBackToPublicTemplateDomain() {
        when(agentProfileMapper.selectOne(any())).thenReturn(null);
        when(templateRepository.findProfiles()).thenReturn(List.of(
                new PublicTemplateRepository.TemplateProfile("t-builtin", "内置", "d", "", 1, 0)));
        when(templateRepository.findPrompts("t-builtin")).thenReturn(List.of(
                new PublicTemplateRepository.TemplatePrompt("p-1", "t-builtin", "SYSTEM_CHAT", "内置系统提示词内容")));

        assertThat(service().defaultPrompt("SYSTEM_CHAT")).isEqualTo("内置系统提示词内容");
    }

    @Test
    @DisplayName("租户内自配优先：命中即返回，不触碰模板域")
    void tenantLocalPromptWinsWithoutTemplateRead() {
        AgentProfileDO builtin = new AgentProfileDO();
        builtin.setId("local-builtin");
        builtin.setBuiltin(1);
        when(agentProfileMapper.selectOne(any())).thenReturn(builtin);
        when(agentPromptResolver.loadOwnPrompts("local-builtin")).thenReturn(Map.of("SYSTEM_CHAT", "租户内自配"));

        assertThat(service().defaultPrompt("SYSTEM_CHAT")).isEqualTo("租户内自配");
        verify(templateRepository, never()).findProfiles();
    }

    @Test
    @DisplayName("模板域亦无行：返回空体（不编造默认值）")
    void emptyTemplateDomainReturnsEmpty() {
        when(agentProfileMapper.selectOne(any())).thenReturn(null);
        when(templateRepository.findProfiles()).thenReturn(List.of());

        assertThat(service().defaultPrompt("SYSTEM_CHAT")).isEmpty();
    }

    @Test
    @DisplayName("未知槽位：仍拒绝（槽位白名单语义不变）")
    void unknownSlotStillRejected() {
        assertThatThrownBy(() -> service().defaultPrompt("not_a_slot"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("未知的提示词");
        verify(templateRepository, never()).findProfiles();
    }
}
