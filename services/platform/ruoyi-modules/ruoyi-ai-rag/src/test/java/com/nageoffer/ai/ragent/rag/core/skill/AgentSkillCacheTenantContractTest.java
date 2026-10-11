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
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
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
 * S1 引擎闭包 · 已启用技能缓存租户契约判据（跨租户负例红先行）。
 *
 * <p><b>缺陷（R4，F09-D3 登记的同型键）</b>：{@code AgentSkillCacheManager} 曾用全局固定 key
 * {@code ragent:agent:enabled-skills:v1}，而技能行 {@code ai_agent_skill} 带 {@code tenant_id}
 * ——缓存却全租户共享，引擎闭包装配后租户 A 的已启用技能清单会被租户 B 读到（跨租户串读）。
 * 该件是 S1 引擎最小闭包的必要前置（{@code AgentSkillRegistry} 的唯一缓存件），修法与
 * F09-A1 的 {@code AgentPromptCacheManager} 逐项同构（该判据族即其镜像）。
 *
 * <p><b>判据</b>：key 必须为 {@code ragent:agent:enabled-skills:v1:{tenantId}}；
 * 缺执行主体一律 fail-closed（不得回落全局 key）；清缓存只清本租户；TTL 1 小时仅作兜底；
 * registry 级两上下文不得互相读到对方的技能清单。
 *
 * <p>Redis 用 Mockito 替身 + 内存 Map 模拟（本模块测试不依赖真实 Redis），
 * 被打桩的只有 Redis 的读写原语，租户命名空间与回填逻辑走生产代码。
 */
@Tag("dev")
class AgentSkillCacheTenantContractTest {

    private static final String TENANT_A = "t-a";
    private static final String TENANT_B = "t-b";
    private static final String KEY_A = "ragent:agent:enabled-skills:v1:" + TENANT_A;
    private static final String KEY_B = "ragent:agent:enabled-skills:v1:" + TENANT_B;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> redisStore = new LinkedHashMap<>();
    private final List<String> setKeys = new ArrayList<>();
    private long lastTtlSeconds = -1;
    private TimeUnit lastTimeUnit;

    private AgentSkillCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        redisStore.clear();
        setKeys.clear();
        lastTtlSeconds = -1;
        lastTimeUnit = null;

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString()))
                .thenAnswer(invocation -> redisStore.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            setKeys.add(invocation.getArgument(0, String.class));
            redisStore.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
            lastTtlSeconds = invocation.getArgument(2, Long.class);
            lastTimeUnit = invocation.getArgument(3, TimeUnit.class);
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redis.delete(anyString()))
                .thenAnswer(invocation -> redisStore.remove(invocation.getArgument(0, String.class)) != null);

        cacheManager = new AgentSkillCacheManager(redis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    // ---------------------------------------------------------------- 跨租户隔离（核心负例）

    @Test
    @DisplayName("跨租户负例：租户 A 写入的已启用技能不得被租户 B 读到")
    void tenantMustNotReadAnotherTenantsCache() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(List.of(skill("a-only", "A 租户技能")));

        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getFromCache())
                .as("租户 B 读取必须 miss——命中了 A 的技能清单就是跨租户串读")
                .isNull();

        assertThat(setKeys)
                .as("写入 key 必须带租户命名空间（现为全局 key 即红）")
                .containsExactly(KEY_A);
    }

    @Test
    @DisplayName("两个租户各读各的：同一次运行里互不串读")
    void eachTenantReadsBackItsOwnSkills() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(List.of(skill("a-skill", "A 的技能")));
        PrincipalContext.set(principal(TENANT_B));
        cacheManager.saveToCache(List.of(skill("b-skill", "B 的技能"), skill("b-skill-2", "B 的技能2")));

        PrincipalContext.set(principal(TENANT_A));
        assertThat(cacheManager.getFromCache())
                .as("租户 A 读回自己的清单")
                .extracting(AgentSkill::skillCode)
                .containsExactly("a-skill");
        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getFromCache())
                .as("租户 B 读回自己的清单")
                .extracting(AgentSkill::skillCode)
                .containsExactly("b-skill", "b-skill-2");
    }

    @Test
    @DisplayName("缺执行主体：读/写/清一律拒绝，绝不回落到全局 key（fail-closed）")
    void missingPrincipalIsRefusedInsteadOfFallingBackToGlobalKey() {
        PrincipalContext.clear();

        assertThatThrownBy(() -> cacheManager.getFromCache())
                .as("缺主体读取必须拒绝，不得去读任何跨租户共享的 key")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.saveToCache(List.of(skill("x", "x"))))
                .as("缺主体写入必须拒绝，不得落到全局 key")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.clearCache())
                .as("缺主体清缓存必须拒绝（清全局 key 会误伤所有租户）")
                .isInstanceOf(ClientException.class);
        assertThat(redisStore)
                .as("缺主体时不允许产生任何 Redis 写入")
                .isEmpty();
        assertThat(setKeys)
                .as("缺主体时不允许产生任何 set 调用")
                .isEmpty();
    }

    // ---------------------------------------------------------------- 缓存契约

    @Test
    @DisplayName("TTL 契约：1 小时，仅作兜底（失效主通道是技能写操作清缓存）")
    void ttlIsOneHour() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(List.of(skill("a-skill", "A 的技能")));
        assertThat(lastTimeUnit).isEqualTo(TimeUnit.HOURS);
        assertThat(lastTtlSeconds).isEqualTo(1L);
    }

    @Test
    @DisplayName("清缓存契约：只清当前主体的租户，不误清别的租户")
    void clearOnlyActingTenant() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(List.of(skill("a-skill", "A 的技能")));
        PrincipalContext.set(principal(TENANT_B));
        cacheManager.saveToCache(List.of(skill("b-skill", "B 的技能")));

        cacheManager.clearCache();

        PrincipalContext.set(principal(TENANT_A));
        assertThat(cacheManager.getFromCache())
                .as("B 清缓存不得误清 A 的 key")
                .extracting(AgentSkill::skillCode)
                .containsExactly("a-skill");
        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getFromCache())
                .as("B 的 key 已被清，读取必须 miss")
                .isNull();
    }

    @Test
    @DisplayName("错误语义：坏 JSON 缓存退化为 miss（Redis I/O 类异常面保持吞异常）")
    void malformedCacheDegradesToMiss() {
        PrincipalContext.set(principal(TENANT_A));
        redisStore.put(KEY_A, "{not-json");
        assertThat(cacheManager.getFromCache()).isNull();
    }

    // ---------------------------------------------------------------- registry 级（端到端版负例）

    @Test
    @DisplayName("registry 级跨租户负例：两上下文各自查库，B 不得读到 A 的技能清单")
    void registryMustNotServeAnotherTenantsSkills() {
        AgentSkillMapper skillMapper = mock(AgentSkillMapper.class);
        when(skillMapper.selectList(any())).thenAnswer(invocation -> {
            ExecutionPrincipal principal = PrincipalContext.get();
            return List.of(skillDo(principal.tenantId() + "-skill", "租户 " + principal.tenantId() + " 的技能"));
        });
        AgentSkillRegistry registry = new AgentSkillRegistry(skillMapper, cacheManager);

        PrincipalContext.set(principal(TENANT_A));
        assertThat(registry.listEnabled())
                .extracting(AgentSkill::skillCode)
                .containsExactly("t-a-skill");

        PrincipalContext.set(principal(TENANT_B));
        assertThat(registry.listEnabled())
                .as("租户 B 必须查回/读回自己的技能——读到 A 的清单即跨租户串读")
                .extracting(AgentSkill::skillCode)
                .containsExactly("t-b-skill");
        assertThat(setKeys)
                .as("每个租户各写各的 key")
                .containsExactly(KEY_A, KEY_B);
    }

    // ---------------------------------------------------------------- 夹具

    private static ExecutionPrincipal principal(String tenant) {
        return new ExecutionPrincipal(tenant, "7", "platform:" + tenant + ":7", 1, 1, Set.of(),
                "jti-s1-engine", "platform", 1_700_000_000L, 1_700_000_600L);
    }

    private static AgentSkill skill(String code, String name) {
        return new AgentSkill(code, name, "desc-" + code, "content-" + code, List.of());
    }

    private static AgentSkillDO skillDo(String code, String name) {
        return AgentSkillDO.builder()
                .id(code)
                .skillCode(code)
                .name(name)
                .description("desc-" + code)
                .content("content-" + code)
                .toolIds(List.of())
                .enabled(1)
                .sortOrder(0)
                .build();
    }
}
