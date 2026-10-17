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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 技能注册表
 * 运行期只读，Agent 侧从这里取已启用技能
 */
@Component
public class AgentSkillRegistry {

    private final AgentSkillMapper agentSkillMapper;
    private final AgentSkillCacheManager cacheManager;

    /**
     * P3-BIND 绑定权威（F11:3 运行消费）：可为 null（老装配/单测），此时按 **fail-closed** 处理
     * ——空子集，绝不放行全量。
     */
    private final com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService bindingService;

    /**
     * F09:3-D2 租户作用域端口：冷缓存查 {@code ai_agent_skill} 时，agent 引擎线程没有
     * Sa-Token 登录主体/动态租户，平台租户行拦截器会在 SQL 下发前拒绝（真机原文
     * {@code tenant.context.missing}）；查询必须在 {@code TenantHelper.dynamic} 等价作用域内执行。
     *
     * <p><b>两种装配都要生效</b>：4 参构造用于直接装配与测试；embedded 装配
     * （{@code AiEmbeddedAgentEngineClosureConfiguration#agentSkillRegistry}，本卡不允许改）
     * 仍走 3 参构造，本字段由 Spring 在 @Bean 实例化后注入
     * （{@code @Autowired} 后处理器同样作用于 @Bean 返回的对象）。端口缺席（独立应用无拦截器）⇒ 直跑。
     */
    @Autowired(required = false)
    private ObjectProvider<TenantScopeBinder> tenantScopeBinder;

    @Autowired
    public AgentSkillRegistry(AgentSkillMapper agentSkillMapper, AgentSkillCacheManager cacheManager,
                              com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService bindingService,
                              ObjectProvider<TenantScopeBinder> tenantScopeBinder) {
        this.agentSkillMapper = agentSkillMapper;
        this.cacheManager = cacheManager;
        this.bindingService = bindingService;
        this.tenantScopeBinder = tenantScopeBinder;
    }

    /** 生产/兼容构造（embedded @Bean 既有调用）：租户作用域端口由 Spring 字段注入回填。 */
    public AgentSkillRegistry(AgentSkillMapper agentSkillMapper, AgentSkillCacheManager cacheManager,
                              com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService bindingService) {
        this(agentSkillMapper, cacheManager, bindingService, null);
    }

    /** 兼容构造：无绑定权威（仅老调用方；{@code listEnabledFor} 将 fail-closed 为空集）。 */
    public AgentSkillRegistry(AgentSkillMapper agentSkillMapper, AgentSkillCacheManager cacheManager) {
        this(agentSkillMapper, cacheManager, null);
    }

    /**
     * @return 已启用技能，按 sortOrder 升序；正文一并带出，加载时不必再查库
     */
    public List<AgentSkill> listEnabled() {
        List<AgentSkill> cached = cacheManager.getFromCache();
        if (cached != null) {
            return cached;
        }
        List<AgentSkill> skills = queryEnabledInTenantScope();
        cacheManager.saveToCache(skills);
        return skills;
    }

    /**
     * 冷缓存查库（F09:3-D2）：有租户作用域端口时以当前主体租户包住查询；端口缺席直跑。
     * 查询异常不捕获、不转换——是否 fail-closed 由调用方决定。
     */
    private List<AgentSkill> queryEnabledInTenantScope() {
        TenantScopeBinder binder = tenantScopeBinder == null ? null : tenantScopeBinder.getIfAvailable();
        if (binder == null) {
            return queryEnabled();
        }
        String tenantId = PrincipalContext.require().tenantId();
        AtomicReference<List<AgentSkill>> holder = new AtomicReference<>();
        binder.runWithTenant(tenantId, () -> holder.set(queryEnabled()));
        List<AgentSkill> skills = holder.get();
        if (skills == null) {
            // 绑定端口没有执行给定动作 ⇒ 不冒充"无技能"（读不出要响亮失败）
            throw new IllegalStateException("租户作用域绑定端口未执行技能查询");
        }
        return skills;
    }

    private List<AgentSkill> queryEnabled() {
        return agentSkillMapper.selectList(
                        Wrappers.lambdaQuery(AgentSkillDO.class)
                                .eq(AgentSkillDO::getEnabled, 1)
                                .orderByAsc(AgentSkillDO::getSortOrder)
                                .orderByAsc(AgentSkillDO::getId))
                .stream()
                .map(AgentSkillRegistry::toSkill)
                .toList();
    }

    /**
     * @return 指定技能，停用或不存在都返回 null
     */
    public AgentSkill findByCode(String skillCode) {
        if (StrUtil.isBlank(skillCode)) {
            return null;
        }
        String target = skillCode.trim();
        return listEnabled().stream()
                .filter(skill -> target.equals(skill.skillCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 运行期技能子集（F11:3）：**该 Agent 的绑定集合** ∩ 已启用技能。
     *
     * <p>RED(Stage C)：当前实现回落为全量——正是要消除的缺陷形状（空绑定不得扩大为全部）。
     */
    public List<AgentSkill> listEnabledFor(String agentProfileId) {
        if (bindingService == null) {
            // fail-closed：绑定权威缺席时运行期看不到任何技能（绝不放行全量）
            return List.of();
        }
        java.util.Set<String> bound = bindingService.boundSkillCodes(agentProfileId);
        if (bound.isEmpty()) {
            // 空绑定 ⇒ 空子集（F11:3 核心：不得扩大为全部）
            return List.of();
        }
        return listEnabled().stream()
                .filter(skill -> bound.contains(skill.skillCode()))
                .toList();
    }

    /**
     * 运行期按绑定子集取单个技能：不在子集内一律视为**不存在**（掩码三段：绑定前/解绑后不可见）。
     */
    public AgentSkill findByCodeFor(String agentProfileId, String skillCode) {
        if (StrUtil.isBlank(skillCode)) {
            return null;
        }
        String target = skillCode.trim();
        return listEnabledFor(agentProfileId).stream()
                .filter(skill -> target.equals(skill.skillCode()))
                .findFirst()
                .orElse(null);
    }

    private static AgentSkill toSkill(AgentSkillDO record) {
        return new AgentSkill(record.getSkillCode(), record.getName(), record.getDescription(),
                record.getContent(), record.getToolIds());
    }
}
