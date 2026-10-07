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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryControlMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-20 · F16「长期记忆的来源与撤权」判据：{@code provenanceRequired} 打开时，
 * <b>来源已过期/不可核验的记忆条目一律不得注入模型或返回给调用方</b>。
 *
 * <p><b>为什么必须有这一条。</b>{@code AgentMemoryRepository.listActiveItems} 里有一道
 * fail-closed 过滤：
 * <pre>
 *   !provenanceRequired || (sourceAuthorization != null
 *       &amp;&amp; sourceAuthorization.sourcesCurrent(principal, row.sourceRefs, row.sourcePolicyVersion, row.sourceAclVersion))
 * </pre>
 * 它决定"撤权之后，早先抽取出来的记忆还会不会被继续喂给模型"。此前它<b>没有任何判据</b>——
 * 也就是说这条不变量可以被静默改成 {@code return true}（最典型的改法是"授权服务缺席时先放行"）
 * 而测试全绿。撤权屏障对 run/检索有覆盖，对长期记忆没有覆盖，本条补上。
 *
 * <p>三个必须成立的分支：
 * <ol>
 *   <li>来源当前有效 ⇒ 保留；来源已失效（撤权/引用被改写）⇒ 剔除；</li>
 *   <li>授权服务<b>缺席</b> ⇒ <b>全部剔除</b>（不是"全部放行"）。这条是最容易被改错的；</li>
 *   <li>判据用的是<b>该行自己的</b> source 版本，不是调用方或缓存里的版本；缺失版本按 0 参与比对
 *       （0 与任何真实版本都不等 ⇒ 一律剔除，fail-closed）。</li>
 * </ol>
 *
 * <p>另有两条与"缺主体必须拒绝"直接相关：没有执行主体时<b>不查库</b>（不出现"没有租户/成员限定"的
 * 无作用域读取），注入路径（{@link AgentMemoryRepository#loadSnapshot}）退化为空快照而非跨租户回退。
 */
@Tag("dev")
class AgentMemoryProvenanceFilterTest {

    private static final String TENANT = "t1";
    private static final String USER = "1001";
    private static final String MEMBER = "platform:" + TENANT + ":" + USER;

    private AgentMemoryMapper memoryMapper;
    private ResourceAuthorizationService sources;
    private AgentMemoryRepository repository;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ResourceAuthorizationService> provider(ResourceAuthorizationService value) {
        ObjectProvider<ResourceAuthorizationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @BeforeEach
    void setUp() {
        memoryMapper = mock(AgentMemoryMapper.class);
        sources = mock(ResourceAuthorizationService.class);
        repository = new AgentMemoryRepository(memoryMapper,
                mock(AgentMemoryExtractionMapper.class), mock(AgentMemoryControlMapper.class),
                mock(AgentMessageMapper.class), new AgentMemoryProperties());
        PrincipalContext.set(new ExecutionPrincipal(TENANT, USER, MEMBER, 7, 3, Set.of(),
                "jti", "platform", 1_700_000_000L, 1_700_000_060L));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    private static AgentMemoryDO row(String id, String content, String sourceRefs, Integer policy, Integer acl) {
        AgentMemoryDO row = new AgentMemoryDO();
        row.setId(id);
        row.setTenantId(TENANT);
        row.setMemberId(MEMBER);
        row.setUserId(USER);
        row.setContent(content);
        row.setSourceRefs(sourceRefs);
        row.setSourcePolicyVersion(policy);
        row.setSourceAclVersion(acl);
        return row;
    }

    private static final String REFS = "[{\"ref\":\"kb:kb1\",\"version\":4}]";

    @Test
    @DisplayName("来源有效保留、来源失效剔除（逐行判定，不是整批放行/整批否决）")
    void staleProvenanceIsDroppedPerRow() {
        repository.configureSources(true, provider(sources));
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                row("m-1", "用户住在南京", REFS, 7, 3),
                row("m-2", "用户住在杭州", REFS, 7, 3)));
        when(sources.sourcesCurrent(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(true).thenReturn(false);

        List<AgentMemoryItem> items = repository.listActiveItems(USER);

        assertThat(items).extracting(AgentMemoryItem::id).containsExactly("m-1");
    }

    @Test
    @DisplayName("授权服务缺席 ⇒ 全部剔除（不是全部放行）—— 本类最容易被改错的一条")
    void absentAuthorizationSourceDropsEverything() {
        repository.configureSources(true, provider(null));
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                row("m-1", "用户住在南京", REFS, 7, 3)));

        List<AgentMemoryItem> items = repository.listActiveItems(USER);

        assertThat(items).isEmpty();
        verify(sources, never()).sourcesCurrent(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("未开启来源要求（非集成形态）⇒ 不过滤，且不调用授权服务")
    void provenanceNotRequiredKeepsRowsUntouched() {
        repository.configureSources(false, provider(sources));
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                row("m-1", "用户住在南京", REFS, 7, 3)));

        List<AgentMemoryItem> items = repository.listActiveItems(USER);

        assertThat(items).extracting(AgentMemoryItem::id).containsExactly("m-1");
        verify(sources, never()).sourcesCurrent(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("比对用的是该行自己的 source 版本；版本列缺失按 0（与任何真实版本都不等 ⇒ 剔除）")
    void rowOwnSourceVersionsAreUsed() {
        repository.configureSources(true, provider(sources));
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                row("m-1", "有版本", REFS, 7, 3),
                row("m-2", "版本列缺失", REFS, null, null)));
        when(sources.sourcesCurrent(any(), anyString(), anyInt(), anyInt())).thenReturn(true);

        repository.listActiveItems(USER);

        ArgumentCaptor<Integer> policy = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> acl = ArgumentCaptor.forClass(Integer.class);
        verify(sources, org.mockito.Mockito.times(2))
                .sourcesCurrent(any(), anyString(), policy.capture(), acl.capture());
        assertThat(policy.getAllValues()).containsExactly(7, 0);
        assertThat(acl.getAllValues()).containsExactly(3, 0);
    }

    @Test
    @DisplayName("缺执行主体 ⇒ 拒绝且不查库（不得出现无租户/成员限定的读取）")
    void missingPrincipalNeverReadsTheStore() {
        repository.configureSources(true, provider(sources));
        PrincipalContext.clear();

        assertThatThrownBy(() -> repository.listActiveItems(USER)).isInstanceOf(ClientException.class);
        verify(memoryMapper, never()).selectList(any());
        verify(sources, never()).sourcesCurrent(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("注入路径缺主体 ⇒ 空快照（不抛给聊天链）但同样零读取、零跨租户回退")
    void snapshotDegradesToEmptyWithoutAnyRead() {
        repository.configureSources(true, provider(sources));
        PrincipalContext.clear();

        AgentMemorySnapshot snapshot = repository.loadSnapshot(USER);

        assertThat(snapshot.items()).isEmpty();
        verify(memoryMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("长期记忆总开关关闭 ⇒ 空快照且不查库")
    void disabledLongTermMemoryNeverReads() {
        AgentMemoryProperties properties = new AgentMemoryProperties() {
            @Override
            public boolean isLongTermEnabled() {
                return false;
            }
        };
        AgentMemoryRepository disabled = new AgentMemoryRepository(memoryMapper,
                mock(AgentMemoryExtractionMapper.class), mock(AgentMemoryControlMapper.class),
                mock(AgentMessageMapper.class), properties);

        assertThat(disabled.loadSnapshot(USER).items()).isEmpty();
        verify(memoryMapper, never()).selectList(any());
    }
}
