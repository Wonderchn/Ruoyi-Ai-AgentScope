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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Date;
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
 * F09-A1 · 已解析提示词缓存租户契约判据（跨租户负例红先行）。
 *
 * <p><b>缺陷</b>：{@code AgentPromptCacheManager} 曾用全局固定 key
 * {@code ragent:agent:resolved-prompts:v2}，而提示词槽位按租户限域
 * （{@code ai_agent_profile}/{@code ai_agent_prompt} 带 {@code tenant_id}，V7）——
 * 缓存却全租户共享，装配后租户 A 的已解析提示词会被租户 B 读到（跨租户串读）。
 *
 * <p><b>判据</b>：key 必须为 {@code ragent:agent:resolved-prompts:v2:{tenantId}}；
 * 缺执行主体一律 fail-closed（不得回落全局 key）；清缓存只清本租户；TTL 1 小时仅作兜底；
 * resolver 级两上下文不得互相读到对方的解析结果。
 *
 * <p>Redis 用 Mockito 替身 + 内存 Map 模拟（本模块测试不依赖真实 Redis），
 * 被打桩的只有 Redis 的读写原语，租户命名空间与回填逻辑走生产代码。
 */
@Tag("dev")
class AgentPromptCacheTenantContractTest {

    private static final String TENANT_A = "t-a";
    private static final String TENANT_B = "t-b";
    private static final String KEY_A = "ragent:agent:resolved-prompts:v2:" + TENANT_A;
    private static final String KEY_B = "ragent:agent:resolved-prompts:v2:" + TENANT_B;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> redisStore = new LinkedHashMap<>();
    private final List<String> setKeys = new ArrayList<>();
    private long lastTtlSeconds = -1;
    private TimeUnit lastTimeUnit;

    private AgentPromptCacheManager cacheManager;

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

        cacheManager = new AgentPromptCacheManager(redis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    // ---------------------------------------------------------------- 跨租户隔离（核心负例）

    @Test
    @DisplayName("跨租户负例：租户 A 写入的已解析提示词不得被租户 B 读到")
    void tenantMustNotReadAnotherTenantsCache() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(Map.of(AgentPromptSlot.SYSTEM_CHAT.name(), "A 租户的闲聊提示词"));

        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getFromCache())
                .as("租户 B 读取必须 miss——命中了 A 的提示词就是跨租户串读")
                .isNull();

        assertThat(setKeys)
                .as("写入 key 必须带租户命名空间（现为全局 key 即红）")
                .containsExactly(KEY_A);
    }

    @Test
    @DisplayName("两个租户各读各的：同一次运行里互不串读")
    void eachTenantReadsBackItsOwnPrompts() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(Map.of(AgentPromptSlot.SYSTEM_CHAT.name(), "A 的闲聊"));
        PrincipalContext.set(principal(TENANT_B));
        cacheManager.saveToCache(Map.of(AgentPromptSlot.KB_ANSWER.name(), "B 的知识库应答"));

        PrincipalContext.set(principal(TENANT_A));
        assertThat(cacheManager.getFromCache())
                .as("租户 A 读回自己的槽位")
                .containsEntry(AgentPromptSlot.SYSTEM_CHAT.name(), "A 的闲聊")
                .doesNotContainKey(AgentPromptSlot.KB_ANSWER.name());
        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getFromCache())
                .as("租户 B 读回自己的槽位")
                .containsEntry(AgentPromptSlot.KB_ANSWER.name(), "B 的知识库应答")
                .doesNotContainKey(AgentPromptSlot.SYSTEM_CHAT.name());
    }

    @Test
    @DisplayName("缺执行主体：读/写/清一律拒绝，绝不回落到全局 key（fail-closed）")
    void missingPrincipalIsRefusedInsteadOfFallingBackToGlobalKey() {
        PrincipalContext.clear();

        assertThatThrownBy(() -> cacheManager.getFromCache())
                .as("缺主体读取必须拒绝，不得去读任何跨租户共享的 key")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.saveToCache(Map.of(AgentPromptSlot.SYSTEM_CHAT.name(), "x")))
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
    @DisplayName("TTL 契约：1 小时，仅作兜底（失效主通道是管理面写操作清缓存）")
    void ttlIsOneHour() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(Map.of(AgentPromptSlot.SYSTEM_CHAT.name(), "A 的闲聊"));
        assertThat(lastTimeUnit).isEqualTo(TimeUnit.HOURS);
        assertThat(lastTtlSeconds).isEqualTo(1L);
    }

    @Test
    @DisplayName("清缓存契约：只清当前主体的租户，不误清别的租户")
    void clearOnlyActingTenant() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveToCache(Map.of(AgentPromptSlot.SYSTEM_CHAT.name(), "A 的闲聊"));
        PrincipalContext.set(principal(TENANT_B));
        cacheManager.saveToCache(Map.of(AgentPromptSlot.SYSTEM_CHAT.name(), "B 的闲聊"));

        cacheManager.clearCache();

        PrincipalContext.set(principal(TENANT_A));
        assertThat(cacheManager.getFromCache())
                .as("B 清缓存不得误清 A 的 key")
                .containsEntry(AgentPromptSlot.SYSTEM_CHAT.name(), "A 的闲聊");
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

    // ---------------------------------------------------------------- resolver 级（端到端版负例）

    @Test
    @DisplayName("resolver 级跨租户负例：两上下文各自解析，B 不得读到 A 的解析结果")
    void resolverMustNotServeAnotherTenantsResolvedPrompts() {
        AgentProfileMapper profileMapper = mock(AgentProfileMapper.class);
        AgentPromptMapper promptMapper = mock(AgentPromptMapper.class);
        PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);
        when(profileMapper.selectList(any())).thenAnswer(invocation -> {
            ExecutionPrincipal principal = PrincipalContext.get();
            return List.of(profile("builtin-" + principal.tenantId(), 1, 1));
        });
        when(promptMapper.selectList(any())).thenAnswer(invocation -> {
            ExecutionPrincipal principal = PrincipalContext.get();
            return List.of(prompt("builtin-" + principal.tenantId(), AgentPromptSlot.KB_ANSWER,
                    "租户 " + principal.tenantId() + " 的知识库应答"));
        });
        AgentPromptResolver resolver = new AgentPromptResolver(
                profileMapper, promptMapper, cacheManager, templateRepository);

        PrincipalContext.set(principal(TENANT_A));
        assertThat(resolver.resolve(AgentPromptSlot.KB_ANSWER)).isEqualTo("租户 t-a 的知识库应答");

        PrincipalContext.set(principal(TENANT_B));
        assertThat(resolver.resolve(AgentPromptSlot.KB_ANSWER))
                .as("租户 B 必须解析出自己的提示词——读到 A 的内容即跨租户串读")
                .isEqualTo("租户 t-b 的知识库应答");
        assertThat(setKeys)
                .as("每个租户各写各的 key")
                .containsExactly(KEY_A, KEY_B);
    }

    @Test
    @DisplayName("resolver 级 fail-closed：无执行主体时 resolve 必须拒绝而不是走全局缓存")
    void resolverRefusesWhenPrincipalMissing() {
        AgentProfileMapper profileMapper = mock(AgentProfileMapper.class);
        AgentPromptMapper promptMapper = mock(AgentPromptMapper.class);
        PublicTemplateRepository templateRepository = mock(PublicTemplateRepository.class);
        AgentPromptResolver resolver = new AgentPromptResolver(
                profileMapper, promptMapper, cacheManager, templateRepository);

        PrincipalContext.clear();

        assertThatThrownBy(() -> resolver.resolve(AgentPromptSlot.KB_ANSWER))
                .isInstanceOf(ClientException.class);
    }

    // ---------------------------------------------------------------- 夹具

    private static ExecutionPrincipal principal(String tenant) {
        return new ExecutionPrincipal(tenant, "7", "platform:" + tenant + ":7", 1, 1, Set.of(),
                "jti-f09-a1", "platform", 1_700_000_000L, 1_700_000_600L);
    }

    private static AgentProfileDO profile(String id, int builtin, int active) {
        return AgentProfileDO.builder()
                .id(id)
                .name("agent-" + id)
                .builtin(builtin)
                .active(active)
                .createTime(new Date())
                .build();
    }

    private static AgentPromptDO prompt(String agentId, AgentPromptSlot slot, String content) {
        return AgentPromptDO.builder()
                .id(agentId + "-" + slot.name())
                .agentId(agentId)
                .slotKey(slot.name())
                .content(content)
                .build();
    }
}
