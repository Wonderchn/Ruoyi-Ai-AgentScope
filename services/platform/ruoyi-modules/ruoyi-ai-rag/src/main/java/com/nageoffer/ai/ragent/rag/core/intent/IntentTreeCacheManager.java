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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 意图树缓存管理器
 * 负责意图树在Redis中的缓存管理。
 *
 * <p><b>契约（F07-A2 定稿）</b>：
 * <ul>
 *   <li><b>key 命名空间</b>：{@code ragent:intent:tree:{tenantId}}，一租户一 key；
 *       租户取自当前执行主体（{@link ExecutionPrincipal#tenantId()}，下同）。
 *       节点表 {@code ai_intent_node} 是租户业务表（V7 模板/租户覆盖域补
 *       {@code tenant_id} 列并 SET NOT NULL、加 {@code ck_t_intent_node_tenant}
 *       非空校验，读写按租户限域），缓存必须与之同口径——此前全租户共享一个固定 key，
 *       装配后租户 A 的意图树会被租户 B 读到（跨租户串树），故禁止任何跨租户共享 key；</li>
 *   <li><b>缺主体即拒绝（fail-closed）</b>：无执行主体时读/写/清/探针一律抛
 *       {@link com.nageoffer.ai.ragent.framework.exception.ClientException}
 *       （{@link PrincipalContext#require()} 统一入口），绝不回落到全局 key
 *       或默认租户；该拒绝不吞（见"错误语义"）；</li>
 *   <li><b>TTL 7 天</b>：仅作兜底。节点的增删改会主动清本租户缓存，不依赖过期生效；</li>
 *   <li><b>失效触发</b>：{@code IntentTreeServiceImpl} 的 create/update/delete
 *       及批量启停等在落库后调用 {@link #clearIntentTreeCache()}（只清当前主体的租户）；</li>
 *   <li><b>读侧回填</b>：{@code DefaultIntentClassifier#loadIntentTreeData} 缓存优先，
 *       miss 才查库组装，组装非空即回填；</li>
 *   <li><b>错误语义</b>：Redis I/O 失败吞异常——读返回 {@code null}（退化为查库）、
 *       写仅记日志（缓存故障不阻断查询链）；{@code clearIntentTreeCache} 保持既有
 *       异常面不吞；但"缺主体"不是 I/O 故障，任何方法都不吞。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentTreeCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * key 前缀，完整 key 为 {@code ragent:intent:tree:{tenantId}}；
     * key 形状变更必须同步本契约注释与用例判据。
     */
    private static final String INTENT_TREE_CACHE_KEY_PREFIX = "ragent:intent:tree:";

    /**
     * 缓存过期时间：7天
     */
    private static final long CACHE_EXPIRE_DAYS = 7;

    /**
     * 当前主体的租户缓存 key；缺主体直接拒绝（fail-closed），不落全局 key。
     */
    private String tenantCacheKey() {
        return INTENT_TREE_CACHE_KEY_PREFIX + PrincipalContext.require().tenantId();
    }

    /**
     * 从Redis获取意图树缓存
     *
     * @return 意图树根节点列表，缓存不存在（含跨租户）则返回null
     */
    public List<IntentNode> getIntentTreeFromCache() {
        // 缺主体的 ClientException 在此抛出，必须留在 try 之外——否则会被下面的
        // "Redis I/O 失败吞异常"分支吞成 null，fail-closed 就退化成静默放行
        String cacheKey = tenantCacheKey();
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cacheJson == null) {
                log.info("意图树缓存不存在，需要从数据库加载");
                return null;
            }

            return objectMapper.readValue(
                    cacheJson,
                    new TypeReference<>() {
                    }
            );
        } catch (Exception e) {
            log.error("从Redis读取意图树缓存失败", e);
            return null;
        }
    }

    /**
     * 将意图树保存到Redis缓存
     *
     * @param roots 意图树根节点列表
     */
    public void saveIntentTreeToCache(List<IntentNode> roots) {
        // 同 getIntentTreeFromCache：缺主体的拒绝不吞
        String cacheKey = tenantCacheKey();
        try {
            String cacheJson = objectMapper.writeValueAsString(roots);
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    cacheJson,
                    CACHE_EXPIRE_DAYS,
                    TimeUnit.DAYS
            );
            log.info("意图树已保存到Redis缓存，根节点数: {}", roots.size());
        } catch (Exception e) {
            log.error("保存意图树到Redis缓存失败", e);
        }
    }

    /**
     * 清除意图树缓存
     * 在意图节点发生增删改时调用；只清当前主体所属租户的 key
     */
    public void clearIntentTreeCache() {
        String cacheKey = tenantCacheKey();
        Boolean deleted = stringRedisTemplate.delete(cacheKey);
        if (deleted) {
            log.info("意图树缓存已清除");
        } else {
            log.info("意图树缓存不存在，无需清除");
        }
    }

    /**
     * 检查缓存是否存在
     *
     * @return true表示缓存存在，false表示不存在
     */
    public boolean isCacheExists() {
        // 探针同样不得跨租户、不得在缺主体时谎报"不存在"（那会误导调用方走回库/回填链）
        String cacheKey = tenantCacheKey();
        try {
            return stringRedisTemplate.hasKey(cacheKey);
        } catch (Exception e) {
            log.error("检查意图树缓存是否存在失败", e);
            return false;
        }
    }
}
