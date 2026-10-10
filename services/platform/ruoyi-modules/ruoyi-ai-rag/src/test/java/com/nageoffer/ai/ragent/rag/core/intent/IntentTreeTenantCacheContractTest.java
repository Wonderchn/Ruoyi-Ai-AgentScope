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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.service.impl.IntentTreeServiceImpl;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F07-A2 · 意图树缓存租户契约判据（跨租户负例红先行；与 A1 词映射同文件族同缺陷类）。
 *
 * <p><b>缺陷</b>：{@code IntentTreeCacheManager} 曾用全局固定 key
 * {@code ragent:intent:tree}，而意图节点表 {@code ai_intent_node} 是租户业务表
 * （V7 模板/租户覆盖域补 {@code tenant_id} 列并 SET NOT NULL +
 * {@code ck_t_intent_node_tenant} 非空校验，读写按租户限域）——
 * 缓存却全租户共享，装配后租户 A 的意图树会被租户 B 读到（跨租户串树）。
 *
 * <p><b>判据</b>：key 必须为 {@code ragent:intent:tree:{tenantId}}；
 * 缺执行主体一律 fail-closed（不得回落全局 key，探针也不得谎报"不存在"）；
 * 节点删除只清本租户缓存；TTL 7 天仅作兜底。
 *
 * <p>Redis 用 Mockito 替身 + 内存 Map 模拟（本模块测试不依赖真实 Redis），
 * 被打桩的只有 Redis 的读写原语与节点 DAO，租户命名空间与清缓存走生产代码。
 *
 * <p>ObjectMapper 用 {@link Jackson2ObjectMapperBuilder}（Spring 自动配置同一构建器）：
 * {@code IntentNode} 只有 {@code @Builder} 的全参构造、没有无参构造，反序列化依赖
 * ParameterNamesModule（生产注入的就是 Spring 的 mapper，行为一致）；用裸
 * {@code new ObjectMapper()} 会因"无可用构造器"读不回，掩盖本组要判的租户命名空间。
 */
@Tag("dev")
class IntentTreeTenantCacheContractTest {

    private static final String TENANT_A = "t-a";
    private static final String TENANT_B = "t-b";
    private static final String KEY_A = "ragent:intent:tree:" + TENANT_A;
    private static final String KEY_B = "ragent:intent:tree:" + TENANT_B;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final Map<String, String> redisStore = new LinkedHashMap<>();
    private final List<String> setKeys = new ArrayList<>();
    private long lastTtlSeconds = -1;
    private TimeUnit lastTimeUnit;

    private IntentTreeCacheManager cacheManager;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IntentNodeDO.class);
    }

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
        when(redis.hasKey(anyString()))
                .thenAnswer(invocation -> redisStore.containsKey(invocation.getArgument(0, String.class)));

        cacheManager = new IntentTreeCacheManager(redis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    // ---------------------------------------------------------------- 跨租户隔离（核心负例）

    @Test
    @DisplayName("跨租户负例：租户 A 写入的意图树缓存不得被租户 B 读到")
    void tenantMustNotReadAnotherTenantsIntentTree() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveIntentTreeToCache(List.of(node("group", "集团信息化")));

        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getIntentTreeFromCache())
                .as("租户 B 读取必须 miss——命中了 A 的意图树就是跨租户串树")
                .isNull();

        assertThat(setKeys)
                .as("写入 key 必须带租户命名空间（现为全局 key 即红）")
                .containsExactly(KEY_A);
    }

    @Test
    @DisplayName("两个租户各读各的：同一次运行里互不串树")
    void eachTenantReadsBackItsOwnTree() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveIntentTreeToCache(List.of(node("group", "集团信息化")));
        PrincipalContext.set(principal(TENANT_B));
        cacheManager.saveIntentTreeToCache(List.of(node("biz-oa", "OA系统")));

        PrincipalContext.set(principal(TENANT_A));
        assertThat(cacheManager.getIntentTreeFromCache())
                .as("租户 A 读回自己的树")
                .extracting(IntentNode::getId)
                .containsExactly("group");
        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getIntentTreeFromCache())
                .as("租户 B 读回自己的树")
                .extracting(IntentNode::getId)
                .containsExactly("biz-oa");
    }

    @Test
    @DisplayName("缺执行主体：读/写/清/探针一律拒绝，绝不回落到全局 key（fail-closed）")
    void missingPrincipalIsRefusedInsteadOfFallingBackToGlobalKey() {
        PrincipalContext.clear();

        assertThatThrownBy(() -> cacheManager.getIntentTreeFromCache())
                .as("缺主体读取必须拒绝，不得去读任何跨租户共享的 key")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.saveIntentTreeToCache(List.of(node("group", "集团信息化"))))
                .as("缺主体写入必须拒绝，不得落到全局 key")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.clearIntentTreeCache())
                .as("缺主体清缓存必须拒绝（清全局 key 会误伤所有租户）")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.isCacheExists())
                .as("缺主体探针不得谎报「不存在」，与读/写/清同口径拒绝")
                .isInstanceOf(ClientException.class);
        assertThat(redisStore)
                .as("缺主体时不允许产生任何 Redis 写入")
                .isEmpty();
        assertThat(setKeys).isEmpty();
    }

    // ---------------------------------------------------------------- 缓存契约

    @Test
    @DisplayName("TTL 契约：7 天，仅作兜底（失效主通道是节点增删改清缓存）")
    void ttlIsSevenDays() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveIntentTreeToCache(List.of(node("group", "集团信息化")));
        assertThat(lastTimeUnit).isEqualTo(TimeUnit.DAYS);
        assertThat(lastTtlSeconds).isEqualTo(7L);
    }

    @Test
    @DisplayName("管理面契约：节点删除只清本租户缓存，不误清他租户")
    void deleteNodeClearsOnlyActingTenant() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveIntentTreeToCache(List.of(node("group", "集团信息化")));
        assertThat(redisStore).containsKey(KEY_A);

        IntentNodeMapper mapper = mock(IntentNodeMapper.class);
        IntentNodeDO existing = IntentNodeDO.builder()
                .id("n-1")
                .intentCode("biz-oa")
                .name("OA系统")
                .deleted(0)
                .build();
        when(mapper.selectById("n-1")).thenReturn(existing);
        IntentTreeServiceImpl service = new IntentTreeServiceImpl(mapper,
                mock(KnowledgeBaseMapper.class), cacheManager, mock(BizChangeLogContext.class));

        PrincipalContext.set(principal(TENANT_B));
        cacheManager.saveIntentTreeToCache(List.of(node("biz-oa", "OA系统")));
        assertThat(redisStore).containsKey(KEY_B);

        service.deleteNode("n-1");
        verify(mapper, times(1)).deleteById("n-1");
        assertThat(cacheManager.getIntentTreeFromCache())
                .as("删除后本租户必须 miss（clearIntentTreeCache 只清当前主体的租户）")
                .isNull();
        assertThat(redisStore)
                .as("B 的删除不得误清 A 的缓存")
                .containsKey(KEY_A);
    }

    // ---------------------------------------------------------------- 夹具

    private static ExecutionPrincipal principal(String tenant) {
        return new ExecutionPrincipal(tenant, "7", "platform:" + tenant + ":7", 1, 1, Set.of(),
                "jti-f07-a2", "platform", 1_700_000_000L, 1_700_000_600L);
    }

    private static IntentNode node(String id, String name) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .build();
    }
}
