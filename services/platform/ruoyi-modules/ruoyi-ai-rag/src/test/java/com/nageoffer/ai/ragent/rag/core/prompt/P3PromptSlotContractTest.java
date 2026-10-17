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

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.rag.service.impl.AgentProfileAdminServiceImpl;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-AGENT-PROMPT · F09:2 离线判据（槽位表 ↔ 管理 API 对照、写后缓存失效、缺运行变量负例）。
 *
 * <p>三条判据各自钉死"复用件真的按契约工作"：
 * <ol>
 *   <li><b>逐槽对照</b>：{@code AgentPromptSlot} 是槽位元数据唯一权威源，
 *       {@code AgentProfileAdminServiceImpl#loadPrompts} 必须<b>逐槽逐字段</b>与枚举一致
 *       （slotKey/displayName/group/生效判定/未生效原因/必需占位符），且默认值来源在
 *       租户域内置行缺席时取自公共模板域；</li>
 *   <li><b>写后失效</b>：任何槽位写操作落库后必须清本租户缓存（否则改动直到 TTL 过期才生效）；</li>
 *   <li><b>缺运行变量不得原样下发</b>：必需占位符在调用方没给值时，渲染结果里不得残留
 *       字面 {@code {token}}——模型既解释不了它，还可能把它当正文复述；而<b>给值时必须正常替换</b>。</li>
 * </ol>
 */
@Tag("dev")
class P3PromptSlotContractTest {

    private final AgentProfileMapper agentProfileMapper = mock(AgentProfileMapper.class);
    private final AgentPromptMapper agentPromptMapper = mock(AgentPromptMapper.class);
    private final AgentPromptResolver agentPromptResolver = mock(AgentPromptResolver.class);
    private final AgentPromptCacheManager cacheManager = mock(AgentPromptCacheManager.class);
    private final OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
    private final BizChangeLogContext bizChangeLogContext = mock(BizChangeLogContext.class);
    private final PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentProfileDO.class);
        TableInfoHelper.initTableInfo(assistant, AgentPromptDO.class);
    }

    private AgentProfileAdminServiceImpl service() {
        return new AgentProfileAdminServiceImpl(agentProfileMapper, agentPromptMapper, agentPromptResolver,
                cacheManager, orchestrationProperties, bizChangeLogContext, templateRepository);
    }

    // ------------------------------------------------------------------ 判据 1：逐槽对照

    @Test
    @DisplayName("槽位表 ↔ 管理 API 逐槽对照：元数据/生效范围/未生效原因/必需占位符全部一致")
    void managementApiMirrorsTheSlotTableSlotBySlot() {
        when(agentProfileMapper.selectById("a-1"))
                .thenReturn(AgentProfileDO.builder().id("a-1").name("客服").builtin(0).active(0).build());
        when(agentPromptResolver.loadOwnPrompts("a-1")).thenReturn(Map.of());
        // 默认值来源：租户域无内置行 ⇒ 只读模板域补名（与 defaultPrompt 的回落链同口径）
        when(agentProfileMapper.selectOne(any())).thenReturn(null);
        when(templateRepository.findProfiles()).thenReturn(List.of(
                new PublicTemplateRepository.TemplateProfile("t-builtin", "模板内置", "d", "", 1, 0)));

        OrchestrationMode mode = orchestrationProperties.getMode();
        AgentPromptConfigVO config = service().loadPrompts("a-1");

        assertThat(config.getMode()).isEqualTo(mode.name());
        assertThat(config.getDefaultAgentName()).isEqualTo("模板内置");
        assertThat(config.getSlots()).extracting(AgentPromptConfigVO.Slot::getSlotKey)
                .as("管理 API 必须暴露**全部**槽位（缺一个就等于控制台少一栏）")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(AgentPromptSlot.values()).map(Enum::name).toList());

        for (AgentPromptSlot slot : AgentPromptSlot.values()) {
            AgentPromptConfigVO.Slot exposed = config.getSlots().stream()
                    .filter(each -> slot.name().equals(each.getSlotKey()))
                    .findFirst()
                    .orElseThrow();
            boolean effective = slot.isEffectiveIn(mode);
            assertThat(exposed.getDisplayName()).as(slot.name() + " displayName").isEqualTo(slot.getDisplayName());
            assertThat(exposed.getGroup()).as(slot.name() + " group").isEqualTo(slot.getGroup().name());
            assertThat(exposed.getGroupName()).as(slot.name() + " groupName")
                    .isEqualTo(slot.getGroup().getDisplayName());
            assertThat(exposed.getEffective()).as(slot.name() + " effective").isEqualTo(effective);
            assertThat(exposed.getInactiveReason()).as(slot.name() + " inactiveReason")
                    .isEqualTo(effective ? null : slot.getInactiveReason());
            assertThat(exposed.getRequiredPlaceholders()).as(slot.name() + " requiredPlaceholders")
                    .containsExactlyInAnyOrderElementsOf(slot.getRequiredPlaceholders());
        }
    }

    @Test
    @DisplayName("生效范围两模式互补：AGENT 专属槽位在 workflow 下不生效且给出原因，反之亦然")
    void effectiveScopeIsModeSpecific() {
        assertThat(AgentPromptSlot.effectiveIn(OrchestrationMode.WORKFLOW))
                .contains(AgentPromptSlot.SYSTEM_CHAT, AgentPromptSlot.CONVERSATION_SUMMARY,
                        AgentPromptSlot.KB_ANSWER, AgentPromptSlot.RECOMMENDED_QUESTIONS)
                .doesNotContain(AgentPromptSlot.AGENT_MAIN);
        assertThat(AgentPromptSlot.effectiveIn(OrchestrationMode.AGENT))
                .contains(AgentPromptSlot.AGENT_MAIN, AgentPromptSlot.KB_ANSWER,
                        AgentPromptSlot.RECOMMENDED_QUESTIONS)
                .doesNotContain(AgentPromptSlot.SYSTEM_CHAT);
    }

    // ------------------------------------------------------------------ 判据 2：写后失效

    @Test
    @DisplayName("写后失效：保存槽位落库即清本租户缓存（不依赖 TTL 过期）")
    void savingASlotClearsTheTenantCache() {
        when(agentProfileMapper.selectById("a-1"))
                .thenReturn(AgentProfileDO.builder().id("a-1").name("客服").builtin(0).active(0).build());
        when(agentPromptMapper.exists(any())).thenReturn(true);
        AgentPromptSaveRequest request = new AgentPromptSaveRequest();
        request.setContent("最多 {summary_max_chars} 字的摘要");

        service().savePrompt("a-1", AgentPromptSlot.AGENT_CONTEXT_COMPACTION.name(), request);

        verify(cacheManager).clearCache();
    }

    @Test
    @DisplayName("拒绝面：缺必需占位符时**既不落库也不清缓存**（零副作用）")
    void missingRequiredPlaceholderIsRejectedWithoutWriteOrCacheClear() {
        when(agentProfileMapper.selectById("a-1"))
                .thenReturn(AgentProfileDO.builder().id("a-1").name("客服").builtin(0).active(0).build());
        AgentPromptSaveRequest request = new AgentPromptSaveRequest();
        request.setContent("没有占位符的正文");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service().savePrompt("a-1", AgentPromptSlot.AGENT_CONTEXT_COMPACTION.name(), request))
                .isInstanceOf(com.nageoffer.ai.ragent.framework.exception.ClientException.class);

        verify(agentPromptMapper, never()).insert(any(AgentPromptDO.class));
        verify(agentPromptMapper, never()).update(any(), any());
        verify(cacheManager, never()).clearCache();
    }

    // ------------------------------------------------------------------ 判据 3：缺运行变量

    @Test
    @DisplayName("缺运行变量负例：必需占位符没给值时不得把 {token} 原样下发")
    void unfilledRequiredPlaceholderIsNotDeliveredVerbatim() {
        AgentPromptResolver resolver = resolverWith(Map.of(
                AgentPromptSlot.AGENT_CONTEXT_COMPACTION.name(), "最多 {summary_max_chars} 字的摘要"));

        String rendered = resolver.render(AgentPromptSlot.AGENT_CONTEXT_COMPACTION, Map.of());

        assertThat(rendered)
                .as("缺运行变量时字面占位符不得进入下发文本（模型解释不了它，还可能复述它）")
                .doesNotContain("{summary_max_chars}");
        assertThat(rendered).contains("最多").contains("字的摘要");
    }

    @Test
    @DisplayName("给值时必须正常替换（不得因为上面的剥离而连正常渲染一起改掉）")
    void providedRuntimeVariableIsFilledNormally() {
        AgentPromptResolver resolver = resolverWith(Map.of(
                AgentPromptSlot.AGENT_CONTEXT_COMPACTION.name(), "最多 {summary_max_chars} 字的摘要"));

        String rendered = resolver.render(AgentPromptSlot.AGENT_CONTEXT_COMPACTION,
                Map.of("summary_max_chars", "500"));

        assertThat(rendered).isEqualTo("最多 500 字的摘要");
    }

    @Test
    @DisplayName("非必需占位符不受剥离影响：调用方未提供时保持原样（不在本判据的拒绝面内）")
    void nonRequiredPlaceholderIsLeftIntact() {
        AgentPromptResolver resolver = resolverWith(Map.of(
                AgentPromptSlot.SYSTEM_CHAT.name(), "用户问：{question}"));

        String rendered = resolver.render(AgentPromptSlot.SYSTEM_CHAT, Map.of());

        assertThat(rendered).isEqualTo("用户问：{question}");
    }

    /** 只桩缓存这一层：解析内容直接来自缓存快照，不触碰 DB 桩。 */
    private AgentPromptResolver resolverWith(Map<String, String> cachedPrompts) {
        AgentPromptResolver resolver = new AgentPromptResolver(
                mock(AgentProfileMapper.class), mock(AgentPromptMapper.class),
                cacheManager, templateRepository);
        when(cacheManager.getFromCache()).thenReturn(cachedPrompts);
        return resolver;
    }
}
