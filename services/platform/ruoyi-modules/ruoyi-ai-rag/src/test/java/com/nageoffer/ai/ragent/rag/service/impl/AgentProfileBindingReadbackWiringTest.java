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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService;
import com.nageoffer.ai.ragent.rag.config.OrchestrationMode;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileVO;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileBindingDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileBindingMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3-BIND Stage D ② 根修：{@code list()} 读侧回显该 Agent 的绑定（F09:3）。
 *
 * <p>判据（红先行）：列表行必须携带 {@code bindings}，否则编辑页"读侧回显"无数据源 ⇒
 * 每次保存都会把绑定替换为空集（行为回归级，见 Stage D 阻断回报 D-3）。
 *
 * <ul>
 *   <li>有绑定的行 ⇒ 逐条回显（type 取 {@code RefType} 名、refId 原样）；</li>
 *   <li>确无绑定的行 ⇒ 空列表 {@code []}（不是缺字段、不是伪造成有绑定）；</li>
 *   <li>绑定权威缺席（兼容构造）⇒ {@code null}：读不出就**不冒充**"无绑定"。</li>
 * </ul>
 */
@Tag("dev")
class AgentProfileBindingReadbackWiringTest {

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

    @SuppressWarnings("unchecked")
    private AgentProfileAdminServiceImpl service() {
        ObjectProvider<AgentProfileBindingService.RefLookup> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(refLookup);
        AgentProfileBindingService bindingService = new AgentProfileBindingService(bindingMapper, provider);
        return new AgentProfileAdminServiceImpl(agentProfileMapper, agentPromptMapper, agentPromptResolver,
                cacheManager, orchestrationProperties, bizChangeLogContext, templateRepository, bindingService);
    }

    @BeforeAll
    static void initTableInfo() {
        // list() 的 loadConfiguredSlots 会解析 AgentPromptDO 的列 lambda；纯 Mockito 单元测试需显式注册 TableInfo。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentProfileDO.class);
        TableInfoHelper.initTableInfo(assistant, AgentPromptDO.class);
        TableInfoHelper.initTableInfo(assistant, AgentProfileBindingDO.class);
    }

    @BeforeEach
    void principal() {
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, java.util.Set.of("agent.read"), "jti", "platform", 0, Long.MAX_VALUE));
        when(orchestrationProperties.getMode()).thenReturn(OrchestrationMode.AGENT);
        when(agentPromptMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("列表逐行回显绑定：有绑定的行回显引用，无绑定的行为空集 []（不是缺字段）")
    void listEchoesBindingsPerRow() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(profile("a-1", "有绑定"), profile("a-2", "无绑定")));
        // bindingsOf 每行一次查询：a-1 有两条，a-2 没有。
        when(bindingMapper.selectList(any())).thenReturn(
                List.of(binding("a-1", "SKILL", "skill-a", 0), binding("a-1", "ORCHESTRATION", "REACT", 1)),
                List.of());

        AgentProfileListVO vo = service().list();

        assertThat(vo.getAgents()).hasSize(2);
        AgentProfileVO bound = vo.getAgents().get(0);
        assertThat(bound.getId()).isEqualTo("a-1");
        assertThat(bound.getBindings())
                .as("有绑定的行必须逐条回显（否则编辑页读不到 ⇒ 保存即解绑）")
                .extracting(ref -> ref.getType() + ":" + ref.getRefId())
                .containsExactly("SKILL:skill-a", "ORCHESTRATION:REACT");

        AgentProfileVO unbound = vo.getAgents().get(1);
        assertThat(unbound.getId()).isEqualTo("a-2");
        assertThat(unbound.getBindings())
                .as("确无绑定 ⇒ 空集（可被页面按替换语义安全重发）")
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("绑定权威缺席（兼容构造）：bindings 为 null，不冒充『无绑定』")
    void absentAuthorityYieldsNullNotFakeEmpty() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(profile("a-1", "x")));

        // 7 参兼容构造：绑定权威缺席。
        AgentProfileAdminServiceImpl legacy = new AgentProfileAdminServiceImpl(agentProfileMapper, agentPromptMapper,
                agentPromptResolver, cacheManager, orchestrationProperties, bizChangeLogContext, templateRepository);

        AgentProfileListVO vo = legacy.list();

        assertThat(vo.getAgents().get(0).getBindings())
                .as("读不出绑定不得冒充空集，否则页面会把真实绑定替换掉")
                .isNull();
    }

    // ------------------------------------------------------------------ 夹具

    private static AgentProfileDO profile(String id, String name) {
        return AgentProfileDO.builder().id(id).name(name).builtin(0).active(0).deleted(0).build();
    }

    private static AgentProfileBindingDO binding(String agentId, String type, String refId, int sortOrder) {
        return AgentProfileBindingDO.builder()
                .tenantId(TENANT)
                .agentProfileId(agentId)
                .refType(type)
                .refId(refId)
                .enabled(1)
                .sortOrder(sortOrder)
                .deleted(0)
                .build();
    }
}
