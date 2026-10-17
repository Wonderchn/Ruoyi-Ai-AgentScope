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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileBindingDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileBindingMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P3-BIND Stage B 第二段：保存路径接入绑定权威（F09:3）。
 *
 * <p>判据（红先行）：绑定引用**先校验后落库** ⇒ 引用不合法时**Agent 行都不该被写**
 * （零部分保存的端到端半边）；合法时 Agent 行与绑定行一起落；`bindings=null` 保持既有行为。
 */
@Tag("dev")
class AgentProfileBindingSaveWiringTest {

    private static final String TENANT = "T1";

    private final AgentProfileMapper agentProfileMapper = mock(AgentProfileMapper.class);
    private final AgentPromptMapper agentPromptMapper = mock(AgentPromptMapper.class);
    private final AgentPromptResolver agentPromptResolver = mock(AgentPromptResolver.class);
    private final AgentPromptCacheManager cacheManager = mock(AgentPromptCacheManager.class);
    private final OrchestrationProperties orchestrationProperties = mock(OrchestrationProperties.class);
    private final BizChangeLogContext bizChangeLogContext = mock(BizChangeLogContext.class);
    private final PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);
    private final AgentProfileBindingMapper bindingMapper = mock(AgentProfileBindingMapper.class);
    private final AgentProfileBindingService.RefLookup refLookup = mock(AgentProfileBindingService.RefLookup.class);

    private AgentProfileAdminServiceImpl service() {
        AgentProfileBindingService bindingService = new AgentProfileBindingService(bindingMapper, lookupProvider());
        return new AgentProfileAdminServiceImpl(agentProfileMapper, agentPromptMapper, agentPromptResolver,
                cacheManager, orchestrationProperties, bizChangeLogContext, templateRepository, bindingService);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<AgentProfileBindingService.RefLookup> lookupProvider() {
        ObjectProvider<AgentProfileBindingService.RefLookup> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(refLookup);
        return provider;
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
    @DisplayName("新增：绑定引用非法 ⇒ 拒绝且**不写 Agent 行**（零部分保存）")
    void createRejectsInvalidBindingBeforePersistingProfile() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of());
        when(refLookup.existsEnabled(anyString(), any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service().create(request("新助手", binding("SKILL", "skill-x"))))
                .isInstanceOf(ClientException.class);

        verify(agentProfileMapper, never()).insert(any(AgentProfileDO.class));
        verify(bindingMapper, never()).insert(any(AgentProfileBindingDO.class));
        verify(bindingMapper, never()).delete(any());
    }

    @Test
    @DisplayName("更新：绑定引用非法 ⇒ 拒绝且不写 Agent 行")
    void updateRejectsInvalidBindingBeforeProfileUpdate() {
        when(agentProfileMapper.selectById("agent-1")).thenReturn(editable("agent-1"));
        when(agentProfileMapper.selectList(any())).thenReturn(List.of());
        when(refLookup.existsEnabled(anyString(), any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service().update("agent-1", request("改名", binding("MCP", "mcp-x"))))
                .isInstanceOf(ClientException.class);

        verify(agentProfileMapper, never()).update(any());
        verify(bindingMapper, never()).insert(any(AgentProfileBindingDO.class));
    }

    @Test
    @DisplayName("新增：合法绑定 ⇒ Agent 行与绑定行都落，且绑定挂在新 id 上")
    void createWithValidBindingPersistsProfileAndBindings() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of());
        when(refLookup.existsEnabled(anyString(), any(), anyString())).thenReturn(true);
        doAnswer(inv -> {
            ((AgentProfileDO) inv.getArgument(0)).setId("agent-new");
            return 1;
        }).when(agentProfileMapper).insert(any(AgentProfileDO.class));

        String id = service().create(request("新助手", binding("SKILL", "skill-a"), binding("ORCHESTRATION", "REACT")));

        assertThat(id).isEqualTo("agent-new");
        ArgumentCaptor<AgentProfileBindingDO> rows = ArgumentCaptor.forClass(AgentProfileBindingDO.class);
        verify(bindingMapper, org.mockito.Mockito.times(2)).insert(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(row -> assertThat(row.getAgentProfileId()).isEqualTo("agent-new"));
        assertThat(rows.getAllValues().get(0).getRefType()).isEqualTo("SKILL");
        assertThat(rows.getAllValues().get(1).getRefType()).isEqualTo("ORCHESTRATION");
    }

    @Test
    @DisplayName("不传 bindings：保持既有行为，零绑定交互")
    void absentBindingsKeepsLegacyBehaviour() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of());

        service().create(request("新助手", null));

        verify(agentProfileMapper).insert(any(AgentProfileDO.class));
        verifyNoInteractions(bindingMapper);
    }

    // ------------------------------------------------------------------ 夹具

    private static AgentProfileSaveRequest request(String name, AgentProfileSaveRequest.BindingRef... refs) {
        AgentProfileSaveRequest request = new AgentProfileSaveRequest();
        request.setName(name);
        request.setDescription("描述");
        request.setAvatar("avatar-1");
        request.setBindings(refs == null ? null : List.of(refs));
        return request;
    }

    private static AgentProfileSaveRequest.BindingRef binding(String type, String refId) {
        AgentProfileSaveRequest.BindingRef ref = new AgentProfileSaveRequest.BindingRef();
        ref.setType(type);
        ref.setRefId(refId);
        return ref;
    }

    private static AgentProfileDO editable(String id) {
        return AgentProfileDO.builder().id(id).name("旧名").builtin(0).active(0).deleted(0).build();
    }
}
