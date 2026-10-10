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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.rag.service.impl.AgentProfileAdminServiceImpl;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F09-A1 ④ · 提示词变量契约（{@code assertPlaceholdersPresent}）后端判据。
 *
 * <p>此前只有前端预检（{@code agentAdmin.ts}）与页面用例，后端保存守卫
 * （{@code AgentProfileAdminServiceImpl#assertPlaceholdersPresent}）无 Java 用例：
 * 删掉拒绝不会有任何后端测试变红。本用例钉死——
 * ① 缺必需占位符：点名缺失项拒绝，且不落库、不清缓存；② 多个缺失一次列全（排序后）；
 * ③ 空白内容=恢复回落，校验放行；④ 占位符齐全/无必需占位符的槽位正常落库；
 * ⑤ 未知槽位先于占位符校验拒绝（白名单语义不变）。
 */
@Tag("dev")
class AgentProfileAdminServiceImplPlaceholderContractTest {

    private static final String AGENT_ID = "a-1";

    private final AgentProfileMapper agentProfileMapper = mock(AgentProfileMapper.class);
    private final AgentPromptMapper agentPromptMapper = mock(AgentPromptMapper.class);
    private final AgentPromptResolver agentPromptResolver = mock(AgentPromptResolver.class);
    private final AgentPromptCacheManager cacheManager = mock(AgentPromptCacheManager.class);
    private final OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
    private final BizChangeLogContext bizChangeLogContext = mock(BizChangeLogContext.class);
    private final PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);

    /**
     * lambdaUpdate 的列解析需要 TableInfo（与 F07 词映射契约用例同款夹具）
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentPromptDO.class);
    }

    @BeforeEach
    void editableAgentExists() {
        AgentProfileDO profile = new AgentProfileDO();
        profile.setId(AGENT_ID);
        profile.setBuiltin(0);
        when(agentProfileMapper.selectById(AGENT_ID)).thenReturn(profile);
    }

    private AgentProfileAdminServiceImpl service() {
        return new AgentProfileAdminServiceImpl(agentProfileMapper, agentPromptMapper, agentPromptResolver,
                cacheManager, orchestrationProperties, bizChangeLogContext, templateRepository);
    }

    @Test
    @DisplayName("缺必需占位符：拒绝并点名缺失项，不落库、不清缓存")
    void missingRequiredPlaceholderIsRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service().savePrompt(AGENT_ID, "AGENT_MEMORY_EXTRACTION",
                content("{existing_memories} 与 {recent_turns}")))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("长期记忆抽取")
                .hasMessageContaining("{memory_max_chars}");

        verify(agentProfileMapper).selectById(AGENT_ID);
        verifyNoInteractions(agentPromptMapper);
        verifyNoInteractions(cacheManager);
    }

    @Test
    @DisplayName("多个必需占位符缺失：一次点名全部（排序后逐字）")
    void multipleMissingPlaceholdersAreListedSorted() {
        assertThatThrownBy(() -> service().savePrompt(AGENT_ID, "RECOMMENDED_QUESTIONS",
                content("{question} 之外都没写")))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("追问推荐")
                .hasMessageContaining("{answer}、{chunks}、{count}");

        verifyNoInteractions(cacheManager);
    }

    @Test
    @DisplayName("空白内容=恢复回落：占位符校验放行并写入清空（不误拦）")
    void blankContentSkipsPlaceholderCheckAndRestoresFallback() {
        when(agentPromptMapper.exists(any())).thenReturn(true);

        service().savePrompt(AGENT_ID, "AGENT_CONTEXT_COMPACTION", content("   "));

        verify(agentPromptMapper).update(any());
        verify(cacheManager).clearCache();
    }

    @Test
    @DisplayName("占位符齐全：正常落库并清缓存")
    void fullPlaceholdersPassAndWrite() {
        when(agentPromptMapper.exists(any())).thenReturn(false);

        service().savePrompt(AGENT_ID, "AGENT_MEMORY_CONSOLIDATION",
                content("{existing_memories} / {target_chars} 规则"));

        verify(agentPromptMapper).insert(any(AgentPromptDO.class));
        verify(cacheManager).clearCache();
    }

    @Test
    @DisplayName("无必需占位符的槽位：任意内容放行（契约只约束声明过的占位符）")
    void slotWithoutPlaceholdersAcceptsArbitraryContent() {
        when(agentPromptMapper.exists(any())).thenReturn(false);

        service().savePrompt(AGENT_ID, "SYSTEM_CHAT", content("这里没有大括号也不拦"));

        verify(agentPromptMapper).insert(any(AgentPromptDO.class));
        verify(cacheManager).clearCache();
    }

    @Test
    @DisplayName("未知槽位：先于占位符校验拒绝（白名单语义不变）")
    void unknownSlotStillRejected() {
        assertThatThrownBy(() -> service().savePrompt(AGENT_ID, "not_a_slot", content("随便什么内容")))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("未知的提示词");

        verify(agentProfileMapper).selectById(AGENT_ID);
        verifyNoInteractions(agentPromptMapper);
        verifyNoInteractions(cacheManager);
    }

    private static AgentPromptSaveRequest content(String content) {
        AgentPromptSaveRequest request = new AgentPromptSaveRequest();
        request.setContent(content);
        return request;
    }
}
