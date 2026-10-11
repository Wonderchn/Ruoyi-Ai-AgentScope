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

package com.nageoffer.ai.ragent.rag.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S2-F11-A1：技能缓存**租户命名空间**判据（跨租户串读负例红先行）。
 *
 * <p><b>缺陷（Spec §2.3 O-2，引擎审计 R4 / F09-D3 同型键）</b>：{@code AgentSkillCacheManager}
 * 用全局固定 key {@code ragent:agent:enabled-skills:v1}，而技能行 {@code ai_agent_skill}
 * 带 {@code tenant_id}——缓存却全租户共享，管理面接线后租户 A 的已启用技能清单会被
 * 租户 B 读到（跨租户串读）。修法与 F09-A1 的 {@code AgentPromptCacheManager} 同构：
 * key 含租户段（{@code ragent:agent:enabled-skills:v1:{tenantId}}），缺执行主体一律
 * fail-closed（{@link PrincipalContext#require()}，不得回落全局 key），清缓存只清本租户。
 *
 * <p><b>与 S1 批的关系（共享面独占声明）</b>：同一文件/同一键由并行分支 S1
 * （{@code feat/s2-engine-s1-assembly}）同步改造，实现形状刻意逐字对齐（key 前缀、
 * fail-closed 位置、错误语义），合并时以 T0 裁决为准；本判据是 F11-A1 树内的
 * 红→绿证据，合并后与 S1 的 {@code AgentSkillCacheTenantContractTest} 同向共存。
 *
 * <p>Redis 用 Mockito 替身 + 内存 Map 模拟（本模块测试不依赖真实 Redis），
 * 被打桩的只有 Redis 读写原语，租户命名空间与回填逻辑走生产代码。
 */
@Tag("dev")
class AgentSkillCacheTenantScopeTest {

    private static final String TENANT_A = "t-a";
    private static final String TENANT_B = "t-b";
    private static final String KEY_PREFIX = "ragent:agent:enabled-skills:v1:";
    private static final String KEY_A = KEY_PREFIX + TENANT_A;
    private static final String KEY_B = KEY_PREFIX + TENANT_B;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> redisStore = new LinkedHashMap<>();
    private final List<String> setKeys = new ArrayList<>();
    private final List<String> deletedKeys = new ArrayList<>();

    private AgentSkillCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        redisStore.clear();
        setKeys.clear();
        deletedKeys.clear();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString()))
                .thenAnswer(invocation -> redisStore.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            setKeys.add(invocation.getArgument(0, String.class));
            redisStore.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
            invocation.getArgument(2, Long.class);
            invocation.getArgument(3, TimeUnit.class);
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redis.delete(anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0, String.class);
                    deletedKeys.add(key);
                    return redisStore.remove(key) != null;
                });

        cacheManager = new AgentSkillCacheManager(redis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("跨租户负例：租户 A 写入的已启用技能不得被租户 B 读到（key 必须带租户段）")
    void tenantMustNotReadAnotherTenantsCache() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(List.of(skill("a-only")));
        assertThat(setKeys)
                .as("写入 key 必须是 ragent:agent:enabled-skills:v1:{tenantId}——"
                        + "全局固定 key（无租户段）即跨租户串读面，本判据红")
                .containsExactly(KEY_A);

        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getFromCache())
                .as("租户 B 必须 miss——命中了 A 的技能清单就是跨租户串读")
                .isNull();
    }

    @Test
    @DisplayName("同租户回填：写入后本租户读得到（租户段不吞正常命中）")
    void sameTenantStillHitsItsOwnCache() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(List.of(skill("a-only")));
        List<AgentSkill> cached = cacheManager.getFromCache();
        assertThat(cached).isNotNull();
        assertThat(cached).extracting(AgentSkill::skillCode).containsExactly("a-only");
        assertThat(redisStore).containsKey(KEY_A).doesNotContainKey(KEY_B);
    }

    @Test
    @DisplayName("clearCache 只清本租户：租户 A 清完后 B 的条目仍在")
    void clearCacheOnlyClearsTheCurrentTenantsKey() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(List.of(skill("a-only")));
        PrincipalContext.set(principal(TENANT_B));
        cacheManager.saveToCache(List.of(skill("b-only")));

        cacheManager.clearCache();

        assertThat(deletedKeys).as("只允许删本租户 key").containsExactly(KEY_B);
        assertThat(redisStore).containsKey(KEY_A).doesNotContainKey(KEY_B);
        PrincipalContext.set(principal(TENANT_A));
        assertThat(cacheManager.getFromCache())
                .as("A 的条目不得被 B 的 clearCache 连带清掉")
                .isNotNull();
    }

    @Test
    @DisplayName("缺执行主体 fail-closed：读/写/清一律拒绝，绝不回落全局 key")
    void missingPrincipalIsRejectedForReadWriteAndClear() {
        PrincipalContext.clear();
        assertThatThrownBy(cacheManager::getFromCache)
                .as("缺主体不得被 'Redis I/O 失败吞异常' 分支吞成 null")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.saveToCache(List.of()))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(cacheManager::clearCache)
                .isInstanceOf(ClientException.class);
        assertThat(setKeys).isEmpty();
        assertThat(deletedKeys).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static ExecutionPrincipal principal(String tenantId) {
        return new ExecutionPrincipal(tenantId, "7", "platform:" + tenantId + ":7", 7, 3,
                Set.of("ai:skill:list"), "jti-f11-a1", "platform", 1_700_000_000L, 1_700_000_600L);
    }

    private static AgentSkill skill(String code) {
        return new AgentSkill(code, code, "desc", "content", List.of());
    }
}
