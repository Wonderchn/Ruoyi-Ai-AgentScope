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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 智能体提示词缓存管理器
 * 缓存的是激活智能体叠加自定义提示词之后的结果，命中即可直接取用
 *
 * <p><b>契约（F09-A1 定稿）</b>：
 * <ul>
 *   <li><b>key 命名空间</b>：{@code ragent:agent:resolved-prompts:v2:{tenantId}}，一租户一 key；
 *       租户取自当前执行主体（{@link ExecutionPrincipal#tenantId()}，下同）。提示词槽位按租户
 *       限域（{@code ai_agent_profile}/{@code ai_agent_prompt} 带 {@code tenant_id}，V7 现势），
 *       缓存必须与之同口径——此前全租户共享一个固定 key，装配后租户 A 的已解析提示词会被
 *       租户 B 读到（跨租户串读），故禁止任何跨租户共享 key；</li>
 *   <li><b>缺主体即拒绝（fail-closed）</b>：无执行主体时读/写/清一律抛
 *       {@link com.nageoffer.ai.ragent.framework.exception.ClientException}
 *       （{@link PrincipalContext#require()} 统一入口），绝不回落到全局 key 或默认租户；
 *       该拒绝不吞（见"错误语义"）；</li>
 *   <li><b>TTL 1 小时</b>：仅作兜底；提示词写操作会主动清本租户缓存，不依赖过期生效；</li>
 *   <li><b>失效触发</b>：{@code AgentProfileAdminServiceImpl} 的 savePrompt/activate/delete
 *       在落库后调用 {@link #clearCache()}（只清当前主体的租户）；</li>
 *   <li><b>读侧回填</b>：{@code AgentPromptResolver#resolveAll} 缓存优先，miss 才查库并回填；
 *       空 map 会被写入（"内置基线为空"也是快照），但读侧把空 map 当未命中（resolveAll
 *       据此回源），本类不做二次判定；</li>
 *   <li><b>错误语义</b>：Redis I/O 失败吞异常——读返回 {@code null}（退化为查库）、写与清
 *       仅记日志，缓存故障不阻断管理面；但"缺主体"不是 I/O 故障，任何方法都不吞。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPromptCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * key 前缀，完整 key 为 {@code ragent:agent:resolved-prompts:v2:{tenantId}}；
     * 缓存结构随提示词槽位集合变化时递增版本，避免旧缓存缺少新增槽位；
     * key 形状变更必须同步本契约注释与用例判据。
     */
    private static final String CACHE_KEY_PREFIX = "ragent:agent:resolved-prompts:v2:";

    private static final long CACHE_EXPIRE_HOURS = 1;

    /**
     * 当前主体的租户缓存 key；缺主体直接拒绝（fail-closed），不落全局 key。
     */
    private String tenantCacheKey() {
        return CACHE_KEY_PREFIX + PrincipalContext.require().tenantId();
    }

    /**
     * @return 槽位到提示词的映射，缓存不存在（含跨租户/未过期但被清）则返回 null
     */
    public Map<String, String> getFromCache() {
        // 缺主体的 ClientException 在此抛出，必须留在 try 之外——否则会被下面的
        // "Redis I/O 失败吞异常"分支吞成 null，fail-closed 就退化成静默放行
        String cacheKey = tenantCacheKey();
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cacheJson == null) {
                return null;
            }
            return objectMapper.readValue(cacheJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("从 Redis 读取智能体提示词缓存失败", e);
            return null;
        }
    }

    public void saveToCache(Map<String, String> prompts) {
        // 同 getFromCache：缺主体的拒绝不吞
        String cacheKey = tenantCacheKey();
        try {
            String cacheJson = objectMapper.writeValueAsString(prompts);
            stringRedisTemplate.opsForValue().set(cacheKey, cacheJson, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("保存智能体提示词到 Redis 缓存失败", e);
        }
    }

    /**
     * 任何智能体或槽位写操作后必须调用，否则改动直到过期才生效；只清当前主体所属租户的 key
     */
    public void clearCache() {
        // 同 getFromCache：缺主体的拒绝不吞
        String cacheKey = tenantCacheKey();
        try {
            stringRedisTemplate.delete(cacheKey);
            log.info("智能体提示词缓存已清除");
        } catch (Exception e) {
            log.error("清除智能体提示词缓存失败", e);
        }
    }
}
