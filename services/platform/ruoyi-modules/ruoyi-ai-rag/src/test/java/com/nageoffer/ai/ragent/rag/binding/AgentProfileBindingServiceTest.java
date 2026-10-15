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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.BindingReference;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefLookup;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefType;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileBindingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileBindingMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P3-BIND Stage B：绑定保存/读取的服务层行为（离线，Mockito 驱动，不连库）。
 *
 * <p>判据（红先行）：
 * <ul>
 *   <li>在线核引用：跨租户/悬空/未启用/未知编排码/重复引用 ⇒ 全拒绝；</li>
 *   <li><b>零部分保存</b>：任一引用不合法 ⇒ 零写入（delete/insert 都不得发生）；</li>
 *   <li>核验端口缺席 ⇒ fail-closed 拒绝（不得"没接线就放行"）；</li>
 *   <li>空绑定读取 ⇒ 空集，**绝不等于全量**；</li>
 *   <li>绑定版本对同一集合确定、集合变化即变化（运行期冻结用）。</li>
 * </ul>
 */
@Tag("dev")
class AgentProfileBindingServiceTest {

    private static final String TENANT = "T1";
    private static final String AGENT = "agent-1";

    private final AgentProfileBindingMapper bindingMapper = mock(AgentProfileBindingMapper.class);
    private final RefLookup refLookup = mock(RefLookup.class);

    private ObjectProvider<RefLookup> provider(RefLookup lookup) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RefLookup> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(lookup);
        return provider;
    }

    private AgentProfileBindingService service() {
        return new AgentProfileBindingService(bindingMapper, provider(refLookup));
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

    // ------------------------------------------------------------------ 在线核引用与零部分保存

    @Test
    @DisplayName("跨租户/悬空/未启用引用：逐类型拒绝且零写入")
    void rejectsInvalidReferencesForEveryTypeWithoutWrites() {
        when(refLookup.existsEnabled(anyString(), any(), anyString())).thenReturn(false);

        for (RefType type : List.of(RefType.MODEL, RefType.SKILL, RefType.MCP)) {
            assertThatThrownBy(() -> service().saveBindings(AGENT, List.of(new BindingReference(type, "ref-x"))))
                    .as("引用核验不通过必须拒绝: " + type)
                    .isInstanceOf(ClientException.class);
        }
        verify(bindingMapper, never()).insert(any(AgentProfileBindingDO.class));
        verify(bindingMapper, never()).delete(any());
    }

    @Test
    @DisplayName("任一引用非法 ⇒ 零部分保存（前一条合法也不得落库）")
    void rejectsWholeSetWhenAnySingleReferenceInvalid() {
        when(refLookup.existsEnabled(anyString(), any(), anyString())).thenReturn(true);
        when(refLookup.existsEnabled(TENANT, RefType.MCP, "mcp-bad")).thenReturn(false);

        assertThatThrownBy(() -> service().saveBindings(AGENT, List.of(
                new BindingReference(RefType.SKILL, "skill-ok"),
                new BindingReference(RefType.MCP, "mcp-bad"))))
                .isInstanceOf(ClientException.class);

        verify(bindingMapper, never()).delete(any());
        verify(bindingMapper, never()).insert(any(AgentProfileBindingDO.class));
    }

    @Test
    @DisplayName("未知编排码/空 refId/重复引用：拒绝且零写入")
    void rejectsUnknownOrchestrationBlankRefAndDuplicates() {
        when(refLookup.existsEnabled(anyString(), any(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service().saveBindings(AGENT, List.of(
                new BindingReference(RefType.ORCHESTRATION, "SOME_SCRIPT"))))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service().saveBindings(AGENT, List.of(
                new BindingReference(RefType.SKILL, "  "))))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> service().saveBindings(AGENT, List.of(
                new BindingReference(RefType.SKILL, "skill-a"),
                new BindingReference(RefType.SKILL, "skill-a"))))
                .isInstanceOf(ClientException.class);

        verify(bindingMapper, never()).insert(any(AgentProfileBindingDO.class));
    }

    @Test
    @DisplayName("核验端口缺席：fail-closed 拒绝（不得没接线就放行）")
    void refusesWhenLookupPortMissing() {
        AgentProfileBindingService service = new AgentProfileBindingService(bindingMapper, provider(null));

        assertThatThrownBy(() -> service.saveBindings(AGENT, List.of(new BindingReference(RefType.SKILL, "skill-a"))))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(bindingMapper);
    }

    @Test
    @DisplayName("缺主体：不降级、零写入")
    void refusesWithoutPrincipal() {
        PrincipalContext.clear();
        when(refLookup.existsEnabled(anyString(), any(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service().saveBindings(AGENT, List.of(new BindingReference(RefType.SKILL, "s"))))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(bindingMapper);
    }

    // ------------------------------------------------------------------ 正例与替换语义

    @Test
    @DisplayName("全量合法：先替换旧行再逐条落库（租户/Agent/类型/引用/顺序）")
    void writesAllReferencesAfterFullValidation() {
        when(refLookup.existsEnabled(anyString(), any(), anyString())).thenReturn(true);

        String version = service().saveBindings(AGENT, List.of(
                new BindingReference(RefType.MODEL, "model-1"),
                new BindingReference(RefType.SKILL, "skill-a"),
                new BindingReference(RefType.MCP, "mcp-a"),
                new BindingReference(RefType.ORCHESTRATION, "REACT")));

        verify(bindingMapper).delete(any());
        ArgumentCaptor<AgentProfileBindingDO> rows = ArgumentCaptor.forClass(AgentProfileBindingDO.class);
        verify(bindingMapper, org.mockito.Mockito.times(4)).insert(rows.capture());
        List<AgentProfileBindingDO> written = rows.getAllValues();
        assertThat(written.get(0).getTenantId()).isEqualTo(TENANT);
        assertThat(written.get(0).getAgentProfileId()).isEqualTo(AGENT);
        assertThat(written.get(0).getRefType()).isEqualTo("MODEL");
        assertThat(written.get(0).getRefId()).isEqualTo("model-1");
        assertThat(written.get(0).getEnabled()).isEqualTo(1);
        assertThat(written.get(0).getCreatedBy()).isEqualTo("2101");
        assertThat(written.stream().map(AgentProfileBindingDO::getSortOrder)).containsExactly(0, 1, 2, 3);
        assertThat(version).isNotBlank();
    }

    @Test
    @DisplayName("空列表 = 解除全部绑定：允许，且是显式的空集合（不是\"无操作\"）")
    void emptyListUnbindsEverythingExplicitly() {
        String version = service().saveBindings(AGENT, List.of());

        verify(bindingMapper).delete(any());
        verify(bindingMapper, never()).insert(any(AgentProfileBindingDO.class));
        assertThat(version).isEqualTo(service().bindingVersion(List.of()));
    }

    // ------------------------------------------------------------------ 读取：空绑定=空集

    @Test
    @DisplayName("未绑定 ⇒ 空集（绝不等于全量）：bindingsOf/boundSkillCodes 都是空")
    void unboundAgentReadsEmptyNotWholeCatalog() {
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        assertThat(service().bindingsOf(AGENT)).isEmpty();
        assertThat(service().boundSkillCodes(AGENT)).isEmpty();
    }

    @Test
    @DisplayName("绑定读取只取 enabled=1：停用行不进运行集合")
    void disabledBindingRowsAreNotReturned() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(
                row(RefType.SKILL, "skill-on", 1),
                row(RefType.SKILL, "skill-off", 0),
                row(RefType.MCP, "mcp-on", 1)));

        assertThat(service().boundSkillCodes(AGENT)).containsExactly("skill-on");
        assertThat(service().bindingsOf(AGENT)).extracting(BindingReference::refId)
                .containsExactly("skill-on", "mcp-on");
    }

    // ------------------------------------------------------------------ 版本：确定性 + 变化

    @Test
    @DisplayName("绑定版本：同集合同值、集合变化即变、空与空相等")
    void bindingVersionIsDeterministicAndSensitive() {
        AgentProfileBindingService service = service();

        String a = service.bindingVersion(List.of(new BindingReference(RefType.SKILL, "s-a")));
        String sameAsA = service.bindingVersion(List.of(new BindingReference(RefType.SKILL, "s-a")));
        String both = service.bindingVersion(List.of(
                new BindingReference(RefType.SKILL, "s-a"), new BindingReference(RefType.MCP, "m-b")));
        String empty = service.bindingVersion(List.of());

        assertThat(a).isEqualTo(sameAsA);
        assertThat(both).isNotEqualTo(a);
        assertThat(empty).isNotEqualTo(a);
        assertThat(service.bindingVersion(List.of())).isEqualTo(empty);
    }

    private static AgentProfileBindingDO row(RefType type, String refId, int enabled) {
        return AgentProfileBindingDO.builder()
                .id(type.name() + ":" + refId)
                .tenantId(TENANT)
                .agentProfileId(AGENT)
                .refType(type.name())
                .refId(refId)
                .enabled(enabled)
                .deleted(0)
                .build();
    }
}
