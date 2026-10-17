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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileVO;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
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

import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F09:0 新增读取面的判据：<b>详情</b>与<b>导出</b>。
 *
 * <p>三件事各自钉死（缺任一条就不算完成）：
 * <ol>
 *   <li><b>详情</b>与列表同一投影（{@code AgentProfileVO}）；不存在即拒绝，且<b>零副作用</b>；</li>
 *   <li><b>导出</b>字段集合是<b>恰好</b>文档化的 11 个键，槽位只给 {@code slotKey + masked:true}，
 *       提示词正文/任何配置事实<b>不出现</b>（"无敏感配置泄漏"的可断言形式）；</li>
 *   <li><b>筛选一致</b>：{@code keyword} 与页面列表同口径（名称/描述、大小写不敏感），
 *       且导出与列表同序（内置在前）。</li>
 * </ol>
 *
 * <p>判据纪律：本类只读断言一律同时验证"没写"（{@code never()} 掉 insert/update/delete），
 * 因为"看起来只是读"的实现在本仓历史上正是越权写入的来源。
 */
@Tag("dev")
class AgentProfileAdminServiceImplDetailExportTest {

    /** 导出行的**恰好**字段集（多一个就是泄漏面、少一个就是契约破损）。 */
    private static final Set<String> EXPORT_KEYS = Set.of(
            "id", "name", "description", "avatar", "builtin", "active",
            "effectiveSlots", "inactiveSlots", "createTime", "updateTime", "configuredSlots");

    private static final String PROMPT_SECRET = "机密提示词正文-绝不出现在导出里";

    private final AgentProfileMapper agentProfileMapper = mock(AgentProfileMapper.class);
    private final AgentPromptMapper agentPromptMapper = mock(AgentPromptMapper.class);
    private final AgentPromptResolver agentPromptResolver = mock(AgentPromptResolver.class);
    private final AgentPromptCacheManager cacheManager = mock(AgentPromptCacheManager.class);
    private final OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
    private final BizChangeLogContext bizChangeLogContext = mock(BizChangeLogContext.class);
    private final PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 本类直接驱动实现（不起 Spring），而 {@code Wrappers.lambdaQuery(...).select(...)/.eq(...)}
     * 依赖实体的 {@code TableInfo} 缓存；单测里必须显式初始化，否则 MyBatis-Plus 会抛
     * "can not find lambda cache"。与 {@code KnowledgeBaseNameUniquenessTest} 同一手法。
     */
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

    // ------------------------------------------------------------------ 详情

    @Test
    @DisplayName("详情：单条读取与列表同投影（含生效/失效槽位守恒），且不写任何表")
    void detailProjectsSingleAgentWithoutWriting() {
        when(agentProfileMapper.selectById("a-1")).thenReturn(profile("a-1", "客服助手", 0, 0, 1_700_000_000_000L));
        when(agentPromptMapper.selectList(any())).thenReturn(List.of(
                prompt("a-1", "SYSTEM_CHAT"),
                prompt("a-1", "AGENT_MAIN"),
                prompt("a-1", "认不出的历史槽位")));

        AgentProfileVO detail = service().detail("a-1");

        assertThat(detail.getId()).isEqualTo("a-1");
        assertThat(detail.getName()).isEqualTo("客服助手");
        assertThat(detail.getDescription()).isEqualTo("描述-a-1");
        assertThat(detail.getAvatar()).isEqualTo("avatar-a-1");
        assertThat(detail.getBuiltin()).isFalse();
        assertThat(detail.getActive()).isFalse();
        assertThat(detail.getCreateTime()).isEqualTo(new Date(1_700_000_000_000L));
        // 认不出的 slot_key 不计入任何一边（与 list() 的 loadConfiguredSlots 同口径）
        assertThat((long) detail.getEffectiveSlots() + detail.getInactiveSlots())
                .as("已配置槽位总数必须守恒：SYSTEM_CHAT + AGENT_MAIN（认不出的键被丢弃）")
                .isEqualTo(2L);
        verifyNoWrites();
    }

    @Test
    @DisplayName("详情：不存在 ⇒ ClientException，零副作用（不写、不回落空对象）")
    void detailMissingIsRejectedWithoutSideEffects() {
        when(agentProfileMapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service().detail("nope"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("智能体不存在");

        verifyNoWrites();
        verify(agentPromptMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------------ 导出

    @Test
    @DisplayName("导出：恰好 11 个字段、槽位只有 slotKey+masked、正文与配置事实一律不出现")
    void exportIsFieldExactAndMasked() throws Exception {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-plain", "迁移前自定义", 0, 0, 1_700_000_000_002L),
                profile("a-builtin", "内置助手", 1, 1, 1_700_000_000_001L)));
        when(agentPromptMapper.selectList(any())).thenReturn(List.of(
                promptWithContent("a-plain", "SYSTEM_CHAT", PROMPT_SECRET)));

        String ndjson = service().exportDefinition(null);

        assertThat(ndjson).doesNotContain(PROMPT_SECRET);
        assertThat(ndjson).doesNotContain("content");

        List<String> lines = ndjson.lines().toList();
        assertThat(lines).hasSize(2);
        // 与列表同序：内置在前（createTime 更晚也仍在前）
        assertThat(json.readTree(lines.get(0)).get("id").asText()).isEqualTo("a-builtin");
        assertThat(json.readTree(lines.get(1)).get("id").asText()).isEqualTo("a-plain");

        for (String line : lines) {
            JsonNode row = json.readTree(line);
            Set<String> keys = new HashSet<>();
            row.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as("导出字段集合必须恰好是文档化的 11 个键").isEqualTo(EXPORT_KEYS);
        }

        JsonNode plain = json.readTree(lines.get(1));
        assertThat(plain.get("configuredSlots")).hasSize(1);
        assertThat(plain.get("configuredSlots").get(0).get("slotKey").asText()).isEqualTo("SYSTEM_CHAT");
        assertThat(plain.get("configuredSlots").get(0).get("masked").asBoolean()).isTrue();
        assertThat(plain.get("createTime").asText()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_002L).toString());
        verifyNoWrites();
    }

    @Test
    @DisplayName("导出：keyword 与列表同口径（名称/描述、大小写不敏感）；无命中导出空文本")
    void exportHonoursTheSameFilterAsTheList() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-plain", "Customer Care", 0, 0, 1_700_000_000_002L),
                profile("a-other", "迁移前自定义", 0, 0, 1_700_000_000_003L)));
        when(agentPromptMapper.selectList(any())).thenReturn(List.of());

        assertThat(service().exportDefinition("customer").lines().toList()).hasSize(1);
        assertThat(service().exportDefinition("  CUSTOMER  ").lines().toList()).hasSize(1);
        // 描述也参与匹配（描述-a-plain 含 "plain"）
        assertThat(service().exportDefinition("plain").lines().toList()).hasSize(1);
        assertThat(service().exportDefinition("没有这个词")).isEmpty();
        verifyNoWrites();
    }

    // ------------------------------------------------------------------ 台账子项①④（既有 CRUD 语义的零副作用补验）

    @Test
    @DisplayName("子项①：内置行不可改（update 拒绝，且一次写都没有）")
    void builtinRowCannotBeUpdated() {
        when(agentProfileMapper.selectById("b-1")).thenReturn(profile("b-1", "内置助手", 1, 0, 1_700_000_000_000L));
        AgentProfileSaveRequest request = new AgentProfileSaveRequest();
        request.setName("改名试试");

        assertThatThrownBy(() -> service().update("b-1", request))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("内置智能体不可编辑或删除");
        verifyNoWrites();
    }

    @Test
    @DisplayName("子项①：激活中禁删（delete 拒绝；不删槽位、不清缓存）")
    void activeRowCannotBeDeleted() {
        when(agentProfileMapper.selectById("a-active")).thenReturn(profile("a-active", "当前启用", 0, 1, 1_700_000_000_000L));

        assertThatThrownBy(() -> service().delete("a-active"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("正在激活中");
        verifyNoWrites();
        verify(cacheManager, never()).clearCache();
    }

    @Test
    @DisplayName("子项④：非法名称 / 重复名称 ⇒ 拒绝且零副作用（一次 insert 都不发生）")
    void invalidOrDuplicateNameIsRejectedWithoutWriting() {
        AgentProfileSaveRequest blank = new AgentProfileSaveRequest();
        blank.setName("   ");
        assertThatThrownBy(() -> service().create(blank))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("名称不能为空");

        when(agentProfileMapper.exists(any())).thenReturn(true);
        AgentProfileSaveRequest duplicate = new AgentProfileSaveRequest();
        duplicate.setName("已有名字");
        assertThatThrownBy(() -> service().create(duplicate))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("名称已存在");

        verifyNoWrites();
    }

    // ------------------------------------------------------------------ helpers

    private void verifyNoWrites() {
        verify(agentProfileMapper, never()).insert(any(AgentProfileDO.class));
        verify(agentProfileMapper, never()).updateById(any(AgentProfileDO.class));
        verify(agentProfileMapper, never()).update(any(), any());
        verify(agentProfileMapper, never()).deleteById(anyString());
        verify(agentPromptMapper, never()).insert(any(AgentPromptDO.class));
        verify(agentPromptMapper, never()).update(any(), any());
        verify(agentPromptMapper, never()).delete(any());
    }

    private static AgentProfileDO profile(String id, String name, int builtin, int active, long createTime) {
        AgentProfileDO profile = new AgentProfileDO();
        profile.setId(id);
        profile.setName(name);
        profile.setDescription("描述-" + id);
        profile.setAvatar("avatar-" + id);
        profile.setBuiltin(builtin);
        profile.setActive(active);
        profile.setCreateTime(new Date(createTime));
        profile.setUpdateTime(new Date(createTime));
        return profile;
    }

    private static AgentPromptDO prompt(String agentId, String slotKey) {
        return promptWithContent(agentId, slotKey, "配置内容");
    }

    private static AgentPromptDO promptWithContent(String agentId, String slotKey, String content) {
        AgentPromptDO prompt = new AgentPromptDO();
        prompt.setAgentId(agentId);
        prompt.setSlotKey(slotKey);
        prompt.setContent(content);
        return prompt;
    }
}
