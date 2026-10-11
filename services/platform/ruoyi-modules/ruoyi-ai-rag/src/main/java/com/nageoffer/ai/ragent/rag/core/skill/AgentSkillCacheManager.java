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
 * 技能缓存管理器
 * 缓存已启用技能的全量清单，每轮对话都要读，不宜每次落库
 *
 * <p><b>契约（F11-A1 / S1 引擎闭包批同构定稿，与 F09-A1 的 {@code AgentPromptCacheManager} 同构）</b>：
 * <ul>
 *   <li><b>key 命名空间</b>：{@code ragent:agent:enabled-skills:v1:{tenantId}}，一租户一 key；
 *       租户取自当前执行主体（{@link ExecutionPrincipal#tenantId()}，下同）。技能行
 *       {@code ai_agent_skill} 带 {@code tenant_id}，缓存必须与之同口径——此前全租户共享
 *       一个固定 key {@code ragent:agent:enabled-skills:v1}，管理面接线/引擎闭包装配后
 *       租户 A 的已启用技能清单会被租户 B 读到（跨租户串读，Spec §2.3 O-2 / 引擎审计 R4），
 *       故禁止任何跨租户共享 key；</li>
 *   <li><b>缺主体即拒绝（fail-closed）</b>：无执行主体时读/写/清一律抛
 *       {@link com.nageoffer.ai.ragent.framework.exception.ClientException}
 *       （{@link PrincipalContext#require()} 统一入口），绝不回落到全局 key 或默认租户；
 *       该拒绝不吞（见"错误语义"）；</li>
 *   <li><b>TTL 1 小时</b>：仅作兜底；技能写操作会主动清本租户缓存，不依赖过期生效；</li>
 *   <li><b>读侧回填</b>：{@code AgentSkillRegistry#listEnabled} 缓存优先，miss 才查库并回填，
 *       空列表也是有效快照（"确实一个技能都没配"）；</li>
 *   <li><b>错误语义</b>：Redis I/O 失败吞异常——读返回 {@code null}（退化为查库）、写与清
 *       仅记日志，缓存故障不阻断管理面；但"缺主体"不是 I/O 故障，任何方法都不吞。</li>
 * </ul>
 *
 * <p><b>共享面合并记录</b>：本文件曾由并行分支 S1（{@code feat/s2-engine-s1-assembly}）
 * 与 F11-A1 同步改造，实现形状逐字对齐（key 前缀、fail-closed 位置、错误语义一致）；
 * 合并时按该一致形状收敛，注释取 F11-A1 详版（T0 裁决）。树内红→绿证据见
 * {@code AgentSkillCacheTenantScopeTest}（F11-A1）与 {@code AgentSkillCacheTenantContractTest}（S1）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSkillCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * key 前缀，完整 key 为 {@code ragent:agent:enabled-skills:v1:{tenantId}}；
     * 缓存结构随技能数据面变化时递增版本，避免旧缓存形状漂移；
     * key 形状变更必须同步本契约注释与用例判据（{@code AgentSkillCacheTenantScopeTest} /
     * {@code AgentSkillCacheTenantContractTest}）。
     */
    private static final String CACHE_KEY_PREFIX = "ragent:agent:enabled-skills:v1:";

    private static final long CACHE_EXPIRE_HOURS = 1;

    /**
     * 当前主体的租户缓存 key；缺主体直接拒绝（fail-closed），不落全局 key。
     */
    private String tenantCacheKey() {
        return CACHE_KEY_PREFIX + PrincipalContext.require().tenantId();
    }

    /**
     * @return 已启用技能列表，缓存不存在则返回 null；空列表是有效结果，代表确实一个技能都没配
     */
    public List<AgentSkill> getFromCache() {
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
            log.error("从 Redis 读取技能缓存失败", e);
            return null;
        }
    }

    public void saveToCache(List<AgentSkill> skills) {
        // 同 getFromCache：缺主体的拒绝不吞
        String cacheKey = tenantCacheKey();
        try {
            String cacheJson = objectMapper.writeValueAsString(skills);
            stringRedisTemplate.opsForValue().set(cacheKey, cacheJson, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("保存技能到 Redis 缓存失败", e);
        }
    }

    /**
     * 任何技能写操作后必须调用，否则改动直到过期才生效；只清当前主体所属租户的 key
     */
    public void clearCache() {
        // 同 getFromCache：缺主体的拒绝不吞
        String cacheKey = tenantCacheKey();
        try {
            stringRedisTemplate.delete(cacheKey);
            log.info("技能缓存已清除");
        } catch (Exception e) {
            log.error("清除技能缓存失败", e);
        }
    }
}

