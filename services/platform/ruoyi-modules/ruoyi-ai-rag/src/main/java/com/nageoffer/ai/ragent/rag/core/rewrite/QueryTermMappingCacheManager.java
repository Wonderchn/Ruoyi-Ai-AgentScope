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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 术语映射缓存管理器
 * 负责术语映射规则在 Redis 中的缓存管理。
 *
 * <p><b>契约（F07-A1 定稿）</b>：
 * <ul>
 *   <li><b>key 命名空间</b>：{@code ragent:query-term:mappings:{tenantId}}，一租户一 key；
 *       租户取自当前执行主体（{@link ExecutionPrincipal#tenantId()}，下同）。
 *       映射表 {@code ai_query_term_mapping} 是租户业务表（V7:1862 补 {@code tenant_id}
 *       列，数据库读写按租户限域），缓存必须与之同口径——此前全租户共享一个固定 key，
 *       装配后租户 A 的词规则会被租户 B 读到（跨租户串词），故禁止任何跨租户共享 key；</li>
 *   <li><b>缺主体即拒绝（fail-closed）</b>：无执行主体时读/写/清一律抛
 *       {@link com.nageoffer.ai.ragent.framework.exception.ClientException}
 *       （{@link PrincipalContext#require()} 统一入口），绝不回落到全局 key
 *       或默认租户；该拒绝不吞（见"错误语义"）；</li>
 *   <li><b>TTL 7 天</b>：仅作兜底。规则增删改会主动清本租户缓存，不依赖过期生效；</li>
 *   <li><b>失效触发</b>：{@code QueryTermMappingAdminServiceImpl} 的
 *       create/update/delete 在落库后调用 {@link #clearCache()}（只清当前主体的租户）；</li>
 *   <li><b>读侧回填</b>：{@code QueryTermMappingService} 缓存优先，miss 才查库，
 *       按 priority/长度排序后回填；空列表是有效缓存值（"一条规则都没配"也认缓存，
 *       否则每次提问白读一次库）；</li>
 *   <li><b>硬删语义</b>：删除是物理删（{@code QueryTermMappingDO} 无 {@code @TableLogic}，
 *       deleteById 直删），硬删后 get 必须 miss，下一次读回库；</li>
 *   <li><b>错误语义</b>：Redis I/O 失败吞异常——读返回 {@code null}（退化为查库）、
 *       写仅记日志（缓存故障不阻断管理面）；{@code clearCache} 保持既有异常面不吞；
 *       但"缺主体"不是 I/O 故障，任何方法都不吞。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryTermMappingCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * key 前缀，完整 key 为 {@code ragent:query-term:mappings:{tenantId}}；
     * key 形状变更必须同步本契约注释与用例判据。
     */
    private static final String CACHE_KEY_PREFIX = "ragent:query-term:mappings:";

    /**
     * 缓存过期时间：7天
     */
    private static final long CACHE_EXPIRE_DAYS = 7;

    /**
     * 当前主体的租户缓存 key；缺主体直接拒绝（fail-closed），不落全局 key。
     */
    private String tenantCacheKey() {
        return CACHE_KEY_PREFIX + PrincipalContext.require().tenantId();
    }

    /**
     * 从 Redis 获取术语映射缓存
     *
     * @return 映射规则列表，缓存不存在（含跨租户）则返回 null
     */
    public List<QueryTermMappingDO> getMappingsFromCache() {
        // 缺主体的 ClientException 在此抛出，必须留在 try 之外——否则会被下面的
        // "Redis I/O 失败吞异常"分支吞成 null，fail-closed 就退化成静默放行
        String cacheKey = tenantCacheKey();
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cacheJson == null) {
                log.info("术语映射缓存不存在，需要从数据库加载");
                return null;
            }
            return objectMapper.readValue(cacheJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("从 Redis 读取术语映射缓存失败", e);
            return null;
        }
    }

    /**
     * 将术语映射保存到 Redis 缓存
     *
     * @param mappings 映射规则列表（已排序）
     */
    public void saveMappingsToCache(List<QueryTermMappingDO> mappings) {
        // 同 getMappingsFromCache：缺主体的拒绝不吞
        String cacheKey = tenantCacheKey();
        try {
            String cacheJson = objectMapper.writeValueAsString(mappings);
            stringRedisTemplate.opsForValue().set(cacheKey, cacheJson, CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
            log.info("术语映射已保存到 Redis 缓存，共 {} 条规则", mappings.size());
        } catch (Exception e) {
            log.error("保存术语映射到 Redis 缓存失败", e);
        }
    }

    /**
     * 清除术语映射缓存
     * 在映射规则发生增删改时调用；只清当前主体所属租户的 key
     */
    public void clearCache() {
        String cacheKey = tenantCacheKey();
        Boolean deleted = stringRedisTemplate.delete(cacheKey);
        if (deleted) {
            log.info("术语映射缓存已清除");
        } else {
            log.info("术语映射缓存不存在，无需清除");
        }
    }
}
