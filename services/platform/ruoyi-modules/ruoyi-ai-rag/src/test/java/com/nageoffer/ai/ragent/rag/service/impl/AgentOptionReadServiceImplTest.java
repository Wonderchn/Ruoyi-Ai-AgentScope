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

import com.nageoffer.ai.ragent.rag.controller.vo.AgentOptionVO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F09:4-A 用户端智能体发现面读端的真值表判据。
 *
 * <p>本类钉的是 {@code AgentOptionReadServiceImpl} 的三件事，任何一件回退都必须有测试变红：
 * <ol>
 *   <li><b>executable 真值表</b>：有激活行 ⇒ 仅激活行；无激活有租户内置 ⇒ 仅租户内置；
 *       两域皆无 ⇒ 模板内置（若存在）才是唯一可执行项；三处皆无 ⇒ 全部 {@code false}
 *       （降级但如实，不编造可执行项）；</li>
 *   <li><b>模板域只在租户域没有内置行时才读</b>（本域优先、缺席才回退；读法是
 *       {@code findProfiles()} 上筛 {@code builtin==1}）；</li>
 *   <li><b>列表口径</b>：租户行"内置在前 → createTime 升序（nullsLast）→ id 升序"，
 *       模板兜底行末尾追加。</li>
 * </ol>
 *
 * <p><b>口径取舍已显式登记</b>：第 3 条中"模板行位置"存在备选口径 R1（模板行按
 * builtin 在前并入统一排序）。本实现取 R2（末尾追加），依据与影响面见
 * {@code AgentOptionReadServiceImpl} 类注释；若裁决定为 R1，只需改实现里那一处追加。
 */
@Tag("dev")
class AgentOptionReadServiceImplTest {

    private final AgentProfileMapper agentProfileMapper = mock(AgentProfileMapper.class);
    private final PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);

    private AgentOptionReadServiceImpl service() {
        return new AgentOptionReadServiceImpl(agentProfileMapper, templateRepository);
    }

    @Test
    @DisplayName("有激活租户行：仅该行为 executable；模板兜底行仍按 R2 追加在末尾且不可执行（不夺权）")
    void activeRowIsTheOnlyExecutableAndTemplateRowDoesNotTakeOver() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-custom", "迁移前自定义", 0, 0, date(2)),
                profile("a-active", "当前启用", 0, 1, date(3))));
        when(templateRepository.findProfiles()).thenReturn(List.of(
                new PublicTemplateRepository.TemplateProfile("t-builtin", "模板内置", "d", "", 1, 0)));

        List<AgentOptionVO> options = service().listOptions();

        assertThat(ids(options)).containsExactly("a-custom", "a-active", "t-builtin");
        assertThat(executable(options)).containsExactly(false, true, false);
        verify(agentProfileMapper).selectList(any());
    }

    @Test
    @DisplayName("无激活行、有租户内置行：内置行排在最前且是唯一 executable，模板域不读")
    void tenantBuiltinLeadsTheListAndIsTheOnlyExecutable() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-custom", "普通行", 0, 0, date(1)),
                profile("a-builtin", "租户内置", 1, 0, date(5))));

        List<AgentOptionVO> options = service().listOptions();

        // 「内置在前」压过 createTime：builtin(createTime=5) 仍排在 custom(createTime=1) 之前
        assertThat(ids(options)).containsExactly("a-builtin", "a-custom");
        assertThat(executable(options)).containsExactly(true, false);
        verify(templateRepository, never()).findProfiles();
    }

    @Test
    @DisplayName("无激活、无租户内置：模板内置行末尾追加且是唯一 executable")
    void templateBuiltinIsAppendedAndOnlyExecutableWhenTenantHasNeither() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-custom", "普通行", 0, 0, date(1))));
        when(templateRepository.findProfiles()).thenReturn(List.of(
                new PublicTemplateRepository.TemplateProfile("t-plain", "模板普通行", "d", "", 0, 0),
                new PublicTemplateRepository.TemplateProfile("t-builtin", "模板内置", "d", "", 1, 0)));

        List<AgentOptionVO> options = service().listOptions();

        // R2 口径：租户行在前，模板兜底行追加在末尾（不与租户行混排）
        assertThat(ids(options)).containsExactly("a-custom", "t-builtin");
        assertThat(executable(options)).containsExactly(false, true);
    }

    @Test
    @DisplayName("三处皆无内置/激活：全部 false，不编造行也不丢行")
    void noExecutableAnywhereYieldsAllFalseWithoutFabricatingRows() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-custom", "普通行", 0, 0, date(1))));
        when(templateRepository.findProfiles()).thenReturn(List.of(
                new PublicTemplateRepository.TemplateProfile("t-plain", "模板普通行", "d", "", 0, 0)));

        List<AgentOptionVO> options = service().listOptions();

        assertThat(ids(options)).containsExactly("a-custom");
        assertThat(executable(options)).containsExactly(false);
    }

    @Test
    @DisplayName("激活行与租户内置行并存：builtin 在前但 executable 仍只给激活行")
    void activeRowWinsOverTenantBuiltinRow() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-active", "当前启用", 0, 1, date(4)),
                profile("a-builtin", "租户内置", 1, 0, date(2))));

        List<AgentOptionVO> options = service().listOptions();

        assertThat(ids(options)).containsExactly("a-builtin", "a-active");
        assertThat(executable(options)).containsExactly(false, true);
        verify(templateRepository, never()).findProfiles();
    }

    @Test
    @DisplayName("同一 createTime 的租户行按 id 升序（确定性尾键，不依赖数据库返回序）")
    void sameCreateTimeBreaksTiesById() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profile("a-2", "第二", 0, 0, date(1)),
                profile("a-1", "第一", 0, 0, date(1))));

        List<AgentOptionVO> options = service().listOptions();

        assertThat(ids(options)).containsExactly("a-1", "a-2");
    }

    @Test
    @DisplayName("空值如实透出：description/avatar 为 null 不编造为空串")
    void projectionKeepsNullsInsteadOfFabricating() {
        when(agentProfileMapper.selectList(any())).thenReturn(List.of(
                profileWithNulls("a-1", "只有名字", 0, 1, date(1))));

        List<AgentOptionVO> options = service().listOptions();

        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option.description()).isNull();
            assertThat(option.avatar()).isNull();
            assertThat(option.name()).isEqualTo("只有名字");
        });
    }

    private static List<String> ids(List<AgentOptionVO> options) {
        return options.stream().map(AgentOptionVO::id).toList();
    }

    private static List<Boolean> executable(List<AgentOptionVO> options) {
        return options.stream().map(AgentOptionVO::executable).toList();
    }

    private static Date date(long millis) {
        return new Date(millis);
    }

    private static AgentProfileDO profile(String id, String name, int builtin, int active, Date createTime) {
        AgentProfileDO profile = new AgentProfileDO();
        profile.setId(id);
        profile.setName(name);
        profile.setDescription("描述-" + id);
        profile.setAvatar("avatar-" + id);
        profile.setBuiltin(builtin);
        profile.setActive(active);
        profile.setCreateTime(createTime);
        return profile;
    }

    private static AgentProfileDO profileWithNulls(String id, String name, int builtin, int active, Date createTime) {
        AgentProfileDO profile = new AgentProfileDO();
        profile.setId(id);
        profile.setName(name);
        profile.setBuiltin(builtin);
        profile.setActive(active);
        profile.setCreateTime(createTime);
        return profile;
    }
}
