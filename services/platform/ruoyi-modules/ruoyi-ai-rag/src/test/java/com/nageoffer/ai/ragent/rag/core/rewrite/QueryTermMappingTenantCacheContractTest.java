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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import com.nageoffer.ai.ragent.rag.service.impl.QueryTermMappingAdminServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F07-A1 · 词映射缓存租户契约判据（跨租户负例红先行）。
 *
 * <p><b>缺陷</b>：{@code QueryTermMappingCacheManager} 曾用全局固定 key
 * {@code ragent:query-term:mappings}，而映射表 {@code ai_query_term_mapping}
 * 是租户业务表（V7:1862 补 {@code tenant_id} 列，读侧按租户限域）——
 * 缓存却全租户共享，装配后租户 A 的词规则会被租户 B 读到（跨租户串词）。
 *
 * <p><b>判据</b>：key 必须为 {@code ragent:query-term:mappings:{tenantId}}；
 * 缺执行主体一律 fail-closed（不得回落全局 key）；CRUD 只清本租户缓存；
 * 硬删（物理删）后必须 miss 回库；miss→回填→命中链路成立（空列表也是有效缓存）；
 * TTL 7 天仅作兜底。
 *
 * <p>Redis 用 Mockito 替身 + 内存 Map 模拟（本模块测试不依赖真实 Redis），
 * 被打桩的只有 Redis 的读写原语，租户命名空间与回填逻辑走生产代码。
 */
@Tag("dev")
class QueryTermMappingTenantCacheContractTest {

    private static final String TENANT_A = "t-a";
    private static final String TENANT_B = "t-b";
    private static final String KEY_A = "ragent:query-term:mappings:" + TENANT_A;
    private static final String KEY_B = "ragent:query-term:mappings:" + TENANT_B;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> redisStore = new LinkedHashMap<>();
    private final List<String> setKeys = new ArrayList<>();
    private long lastTtlSeconds = -1;
    private TimeUnit lastTimeUnit;

    private QueryTermMappingCacheManager cacheManager;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                QueryTermMappingDO.class);
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

        cacheManager = new QueryTermMappingCacheManager(redis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    // ---------------------------------------------------------------- 跨租户隔离（核心负例）

    @Test
    @DisplayName("跨租户负例：租户 A 写入的映射缓存不得被租户 B 读到")
    void tenantMustNotReadAnotherTenantsCache() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveMappingsToCache(List.of(mapping("阿里", "阿里巴巴")));

        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getMappingsFromCache())
                .as("租户 B 读取必须 miss——命中了 A 的词就是跨租户串词")
                .isNull();

        assertThat(setKeys)
                .as("写入 key 必须带租户命名空间（现为全局 key 即红）")
                .containsExactly(KEY_A);
    }

    @Test
    @DisplayName("两个租户各读各的：同一次运行里互不串词")
    void eachTenantReadsBackItsOwnRules() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveMappingsToCache(List.of(mapping("阿里", "阿里巴巴")));
        PrincipalContext.set(principal(TENANT_B));
        cacheManager.saveMappingsToCache(List.of(mapping("钉钉", "DingTalk")));

        PrincipalContext.set(principal(TENANT_A));
        assertThat(cacheManager.getMappingsFromCache())
                .as("租户 A 读回自己的规则")
                .extracting(QueryTermMappingDO::getTargetTerm)
                .containsExactly("阿里巴巴");
        PrincipalContext.set(principal(TENANT_B));
        assertThat(cacheManager.getMappingsFromCache())
                .as("租户 B 读回自己的规则")
                .extracting(QueryTermMappingDO::getTargetTerm)
                .containsExactly("DingTalk");
    }

    @Test
    @DisplayName("缺执行主体：读/写/清一律拒绝，绝不回落到全局 key（fail-closed）")
    void missingPrincipalIsRefusedInsteadOfFallingBackToGlobalKey() {
        PrincipalContext.clear();

        assertThatThrownBy(() -> cacheManager.getMappingsFromCache())
                .as("缺主体读取必须拒绝，不得去读任何跨租户共享的 key")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.saveMappingsToCache(List.of(mapping("阿里", "阿里巴巴"))))
                .as("缺主体写入必须拒绝，不得落到全局 key")
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> cacheManager.clearCache())
                .as("缺主体清缓存必须拒绝（清全局 key 会误伤所有租户）")
                .isInstanceOf(ClientException.class);
        assertThat(redisStore)
                .as("缺主体时不允许产生任何 Redis 写入")
                .isEmpty();
    }

    // ---------------------------------------------------------------- 缓存契约

    @Test
    @DisplayName("TTL 契约：7 天，仅作兜底（失效主通道是 CRUD 清缓存）")
    void ttlIsSevenDays() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveMappingsToCache(List.of(mapping("阿里", "阿里巴巴")));
        assertThat(lastTimeUnit).isEqualTo(TimeUnit.DAYS);
        assertThat(lastTtlSeconds).isEqualTo(7L);
    }

    @Test
    @DisplayName("读侧契约：miss→查库→回填→命中（第二次不再查库）；空列表也是有效缓存")
    void missBackfillsThenHitsAndEmptyListIsCached() {
        PrincipalContext.set(principal(TENANT_A));
        QueryTermMappingMapper mapper = mock(QueryTermMappingMapper.class);
        QueryTermMappingService service = new QueryTermMappingService(mapper, cacheManager);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(mapping("阿里", "阿里巴巴"))));

        assertThat(service.normalize("阿里怎么样")).isEqualTo("阿里巴巴怎么样");
        verify(mapper, times(1)).selectList(any());
        assertThat(redisStore)
                .as("miss 后必须回填本租户缓存 key")
                .containsKey(KEY_A);

        assertThat(service.normalize("阿里怎么样")).isEqualTo("阿里巴巴怎么样");
        verify(mapper, times(1)).selectList(any());

        PrincipalContext.set(principal(TENANT_B));
        QueryTermMappingMapper emptyMapper = mock(QueryTermMappingMapper.class);
        QueryTermMappingService emptyService = new QueryTermMappingService(emptyMapper, cacheManager);
        when(emptyMapper.selectList(any())).thenReturn(new ArrayList<>());
        assertThat(emptyService.normalize("随便什么话")).isEqualTo("随便什么话");
        verify(emptyMapper, times(1)).selectList(any());
        assertThat(redisStore)
                .as("空列表回填同样是有效缓存（一条规则都没配也认缓存）")
                .containsKey(KEY_B);
        assertThat(emptyService.normalize("随便什么话")).isEqualTo("随便什么话");
        verify(emptyMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("管理面契约：CRUD→只清本租户缓存；硬删后 miss；不误清别的租户")
    void crudClearsOnlyActingTenantAndHardDeleteMisses() {
        PrincipalContext.set(principal(TENANT_A));
        cacheManager.saveMappingsToCache(List.of(mapping("阿里", "阿里巴巴")));
        assertThat(redisStore).containsKey(KEY_A);

        QueryTermMappingMapper mapper = mock(QueryTermMappingMapper.class);
        QueryTermMappingAdminServiceImpl admin = new QueryTermMappingAdminServiceImpl(
                mapper, cacheManager, mock(BizChangeLogContext.class));

        PrincipalContext.set(principal(TENANT_B));
        QueryTermMappingCreateRequest create = new QueryTermMappingCreateRequest();
        create.setSourceTerm("钉钉");
        create.setTargetTerm("DingTalk");
        admin.create(create);
        verify(mapper, times(1)).insert(any(QueryTermMappingDO.class));
        assertThat(redisStore)
                .as("B 的管理写不得误清 A 的缓存")
                .containsKey(KEY_A);

        QueryTermMappingService service = new QueryTermMappingService(mapper, cacheManager);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(mapping("钉钉", "DingTalk"))));
        assertThat(service.normalize("钉钉")).isEqualTo("DingTalk");
        assertThat(redisStore).containsKey(KEY_B);

        QueryTermMappingDO existing = mapping("钉钉", "DingTalk");
        existing.setId("m-1");
        when(mapper.selectById("m-1")).thenReturn(existing);
        QueryTermMappingUpdateRequest update = new QueryTermMappingUpdateRequest();
        update.setTargetTerm("DingTalk Pro");
        admin.update("m-1", update);
        verify(mapper, times(1)).updateById(any(QueryTermMappingDO.class));
        assertThat(redisStore)
                .as("update 后本租户缓存被清")
                .doesNotContainKey(KEY_B);
        assertThat(redisStore)
                .as("update 不得误清 A 的缓存")
                .containsKey(KEY_A);

        assertThat(service.normalize("钉钉")).isEqualTo("DingTalk");
        assertThat(redisStore).containsKey(KEY_B);
        admin.delete("m-1");
        verify(mapper, times(1)).deleteById("m-1");
        assertThat(cacheManager.getMappingsFromCache())
                .as("硬删后必须 miss（物理删除 + 清本租户缓存），下一次读回库")
                .isNull();
        assertThat(redisStore).containsKey(KEY_A);
    }

    // ---------------------------------------------------------------- 边界

    @Test
    @DisplayName("边界：空词/空白词在管理面被拒绝，且不产生任何写入")
    void blankTermsAreRejectedBeforeAnyWrite() {
        QueryTermMappingMapper mapper = mock(QueryTermMappingMapper.class);
        QueryTermMappingCacheManager cache = mock(QueryTermMappingCacheManager.class);
        QueryTermMappingAdminServiceImpl admin = new QueryTermMappingAdminServiceImpl(
                mapper, cache, mock(BizChangeLogContext.class));
        PrincipalContext.set(principal(TENANT_A));

        QueryTermMappingCreateRequest blankSource = new QueryTermMappingCreateRequest();
        blankSource.setSourceTerm("   ");
        blankSource.setTargetTerm("阿里巴巴");
        assertThatThrownBy(() -> admin.create(blankSource))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("原始词不能为空");

        QueryTermMappingCreateRequest blankTarget = new QueryTermMappingCreateRequest();
        blankTarget.setSourceTerm("阿里");
        blankTarget.setTargetTerm("  ");
        assertThatThrownBy(() -> admin.create(blankTarget))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("目标词不能为空");

        verifyNoInteractions(mapper, cache);
    }

    @Test
    @DisplayName("边界：重叠词不重复替换（已是目标词形的片段保持原样）")
    void overlappingTermsAreNotDoubleReplaced() {
        PrincipalContext.set(principal(TENANT_A));
        QueryTermMappingMapper mapper = mock(QueryTermMappingMapper.class);
        QueryTermMappingService service = new QueryTermMappingService(mapper, cacheManager);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(mapping("阿里", "阿里巴巴"))));

        assertThat(service.normalize("别把阿里叫阿里巴巴")).isEqualTo("别把阿里巴巴叫阿里巴巴");
    }

    // ---------------------------------------------------------------- 夹具

    private static ExecutionPrincipal principal(String tenant) {
        return new ExecutionPrincipal(tenant, "7", "platform:" + tenant + ":7", 1, 1, Set.of(),
                "jti-f07-a1", "platform", 1_700_000_000L, 1_700_000_600L);
    }

    private static QueryTermMappingDO mapping(String source, String target) {
        QueryTermMappingDO mapping = new QueryTermMappingDO();
        mapping.setId("m-" + source);
        mapping.setSourceTerm(source);
        mapping.setTargetTerm(target);
        mapping.setMatchType(1);
        mapping.setPriority(0);
        mapping.setEnabled(1);
        return mapping;
    }
}
