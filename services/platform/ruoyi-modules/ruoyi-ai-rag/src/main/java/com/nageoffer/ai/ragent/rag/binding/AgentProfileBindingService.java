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

package com.nageoffer.ai.ragent.rag.binding;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileBindingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileBindingMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P3-BIND：Agent 绑定的保存/读取（F09:3 权威；F11:3 复用）。
 *
 * <p>语义要点：
 * <ul>
 *   <li>只存**引用**（模型 id / skillCode / mcpToolId / 编排码），不复制资产；</li>
 *   <li>保存前**在线核引用**（存在+启用+同租户+可授权）——任一不合法 ⇒ 整笔拒绝，
 *       且校验发生在任何写之前 ⇒ **零部分保存**；</li>
 *   <li>核验端口缺席 ⇒ **fail-closed 拒绝**（不因"没接线"而放行）；</li>
 *   <li>空绑定读取是**空集**（不是全量）——F11:3「空绑定不得扩大为全部」的服务层半边；</li>
 *   <li>{@code bindingVersion} 是绑定集合的确定性哈希：同集合同版本、变化即变，
 *       供运行期冻结绑定视图（无需新列）。</li>
 * </ul>
 */
@Service
public class AgentProfileBindingService {

    /** 四类绑定引用（闭集）。 */
    public enum RefType {
        MODEL, SKILL, MCP, ORCHESTRATION
    }

    /** 一条绑定引用。 */
    public record BindingReference(RefType type, String refId) {
    }

    /**
     * 在线核引用端口：由适配既有资产面的实现提供
     * （技能表/意图树 MCP 节点/模型目录），实现方负责"存在 + 启用 + 同租户 + 可授权"。
     */
    public interface RefLookup {
        boolean existsEnabled(String tenantId, RefType type, String refId);
    }

    /**
     * P3-BIND 续段 2（M-1）：**模型引用**核验端口。
     *
     * <p>模型目录不在本模块（`ModelSelector` 属 infra-ai）⇒ 本卡只落端口与 fail-closed 默认：
     * 端口缺席时 MODEL 一律不可绑定；端口装配与 runtime 目录适配列入 T0 共享闸批量落行。
     */
    public interface ModelRefLookup {
        boolean existsEnabled(String tenantId, String modelRefId);
    }

    /** 编排模式闭集：只接受已知码，未知码拒绝（不回落默认模式）。 */
    private static final Set<String> ORCHESTRATION_MODES = Set.of("REACT", "PLAN_ACT");

    private final AgentProfileBindingMapper bindingMapper;
    private final ObjectProvider<RefLookup> refLookup;
    /**
     * P3-BIND 运行修复（F09:3-D1，2026-10-18）：租户作用域绑定端口。
     *
     * <p>agent 引擎线程（boundedElastic）没有 Sa-Token 登录主体/动态租户，平台侧 MyBatis
     * 租户行拦截器（{@code tenant.enable=true}）会在 SQL 下发前拒绝（真机原文
     * {@code tenant.context.missing}）。查询必须经本端口在 {@code TenantHelper.dynamic}
     * 等价作用域内执行。端口缺席（独立应用无拦截器）⇒ 按原样直跑。
     */
    private final ObjectProvider<TenantScopeBinder> tenantScopeBinder;

    /**
     * 生产构造（Spring 装配）：3 参；构造上标 {@link Autowired}，避免"双公开构造无注解"
     * 时 Spring 无法选择（同模块既有教训，见 embedded 装配注释）。
     */
    @Autowired
    public AgentProfileBindingService(AgentProfileBindingMapper bindingMapper,
                                      ObjectProvider<RefLookup> refLookup,
                                      ObjectProvider<TenantScopeBinder> tenantScopeBinder) {
        this.bindingMapper = bindingMapper;
        this.refLookup = refLookup;
        this.tenantScopeBinder = tenantScopeBinder;
    }

    /** 兼容构造：无租户作用域绑定端口（既有测试/老装配；按"无拦截器"直跑）。 */
    public AgentProfileBindingService(AgentProfileBindingMapper bindingMapper,
                                      ObjectProvider<RefLookup> refLookup) {
        this(bindingMapper, refLookup, null);
    }

    /**
     * 保存某 Agent 的绑定集合（替换语义）：全量校验通过后，先作废旧行再逐条落库。
     *
     * @return 新的绑定版本（见 {@link #bindingVersion(List)}）
     */
    @Transactional(rollbackFor = Exception.class)
    public String saveBindings(String agentProfileId, List<BindingReference> refs) {
        return replaceBindings(agentProfileId, validateReferences(refs));
    }

    /**
     * 只做"在线核引用"，**不写任何行**：供需要"先校验、后落库"的调用方使用
     * （例如保存 Agent 时先校验绑定，再插 Agent 行 ⇒ 校验失败时零持久化）。
     *
     * @return 归一化后的引用（去 null/trim）
     */
    public List<BindingReference> validateReferences(List<BindingReference> refs) {
        String tenantId = PrincipalContext.require().tenantId();
        List<BindingReference> normalized = normalise(refs);
        validate(normalized, tenantId);
        return normalized;
    }

    /**
     * 用给定引用整体替换该 Agent 的绑定（替换语义；**调用方保证引用已校验**）。
     *
     * @return 新的绑定版本
     */
    @Transactional(rollbackFor = Exception.class)
    public String replaceBindings(String agentProfileId, List<BindingReference> refs) {
        if (!StringUtils.hasText(agentProfileId)) {
            throw new com.nageoffer.ai.ragent.framework.exception.ClientException("Agent 标识不能为空");
        }
        String tenantId = PrincipalContext.require().tenantId();
        String operator = PrincipalContext.require().userId();
        List<BindingReference> normalized = normalise(refs);

        bindingMapper.delete(Wrappers.lambdaQuery(AgentProfileBindingDO.class)
                .eq(AgentProfileBindingDO::getAgentProfileId, agentProfileId)
                .apply("tenant_id = {0}", tenantId));

        for (int i = 0; i < normalized.size(); i++) {
            BindingReference ref = normalized.get(i);
            bindingMapper.insert(AgentProfileBindingDO.builder()
                    .tenantId(tenantId)
                    .agentProfileId(agentProfileId)
                    .refType(ref.type() == null ? null : ref.type().name())
                    .refId(ref.refId())
                    .enabled(1)
                    .sortOrder(i)
                    .createdBy(operator)
                    .updatedBy(operator)
                    .deleted(0)
                    .build());
        }
        return bindingVersion(normalized);
    }

    /**
     * 读取该 Agent 的生效绑定（租户收窄、enabled=1、按 sortOrder）。未绑定 ⇒ 空列表。
     *
     * <p>运行期（agent 引擎线程）没有 Sa-Token 登录主体/动态租户：租户行拦截器会在查询前
     * 拒绝。有 {@link TenantScopeBinder} 时把查询包进该主体的租户作用域；端口缺席
     * （独立应用无拦截器）按原样直跑。查询失败**不吞**——是否 fail-closed 由调用方决定。
     */
    public List<BindingReference> bindingsOf(String agentProfileId) {
        String tenantId = PrincipalContext.require().tenantId();
        TenantScopeBinder binder = tenantScopeBinder == null ? null : tenantScopeBinder.getIfAvailable();
        if (binder == null) {
            return readBindings(agentProfileId, tenantId);
        }
        AtomicReference<List<BindingReference>> holder = new AtomicReference<>();
        binder.runWithTenant(tenantId, () -> holder.set(readBindings(agentProfileId, tenantId)));
        List<BindingReference> bindings = holder.get();
        if (bindings == null) {
            // 绑定端口没有执行给定动作 ⇒ 不冒充"空绑定"（读不出要响亮失败）
            throw new IllegalStateException("租户作用域绑定端口未执行绑定查询");
        }
        return bindings;
    }

    private List<BindingReference> readBindings(String agentProfileId, String tenantId) {
        return bindingMapper.selectList(Wrappers.lambdaQuery(AgentProfileBindingDO.class)
                        .eq(AgentProfileBindingDO::getAgentProfileId, agentProfileId)
                        .eq(AgentProfileBindingDO::getEnabled, 1)
                        .apply("tenant_id = {0}", tenantId)
                        .orderByAsc(AgentProfileBindingDO::getSortOrder)
                        .orderByAsc(AgentProfileBindingDO::getId))
                .stream()
                // 逻辑删由 @TableLogic 兜底；enabled 在 Java 侧再过滤一次（SQL 条件不依赖 mock）
                .filter(row -> row.getEnabled() != null && row.getEnabled() == 1)
                .map(row -> new BindingReference(RefType.valueOf(row.getRefType()), row.getRefId()))
                .toList();
    }

    /** 运行期技能子集（F11:3 消费点）：空绑定 ⇒ 空集合。 */
    public Set<String> boundSkillCodes(String agentProfileId) {
        Set<String> codes = new LinkedHashSet<>();
        for (BindingReference ref : bindingsOf(agentProfileId)) {
            if (ref.type() == RefType.SKILL) {
                codes.add(ref.refId());
            }
        }
        return codes;
    }

    /** 运行期 MCP 工具子集（O-2 消费点）：空绑定 ⇒ 空集合（无 MCP 工具可用）。 */
    public Set<String> boundMcpToolIds(String agentProfileId) {
        Set<String> toolIds = new LinkedHashSet<>();
        for (BindingReference ref : bindingsOf(agentProfileId)) {
            if (ref.type() == RefType.MCP) {
                toolIds.add(ref.refId());
            }
        }
        return toolIds;
    }

    /** 绑定版本：集合的确定性哈希（排序后拼接再 sha256）。 */
    public String bindingVersion(List<BindingReference> refs) {
        List<String> keys = normalise(refs).stream()
                .map(ref -> (ref.type() == null ? "?" : ref.type().name()) + ":" + ref.refId())
                .sorted()
                .toList();
        return SecureUtil.sha256(String.join("|", keys));
    }

    /**
     * 在线核引用（**在任何写之前**执行）：
     * <ul>
     *   <li>引用完整性：类型非空、refId 非空；</li>
     *   <li>编排模式：必须落在闭集内（未知码拒绝，不回落默认模式）；</li>
     *   <li>其余三类：必须由 {@link RefLookup} 认定"存在 + 启用 + 同租户 + 可授权"；
     *       端口缺席 ⇒ fail-closed 拒绝（不因未接线而放行）；</li>
     *   <li>同一 (类型, 引用) 不得重复。</li>
     * </ul>
     * 任何一条不满足即抛 {@link com.nageoffer.ai.ragent.framework.exception.ClientException}，
     * 调用方（{@link #saveBindings}）因此不会执行任何 delete/insert。
     */
    private void validate(List<BindingReference> refs, String tenantId) {
        Set<String> seen = new LinkedHashSet<>();
        for (BindingReference ref : refs) {
            if (ref.type() == null || !StringUtils.hasText(ref.refId())) {
                throw new com.nageoffer.ai.ragent.framework.exception.ClientException(
                        "绑定引用不完整（类型与引用标识都必须提供）");
            }
            if (!seen.add(ref.type().name() + ":" + ref.refId())) {
                throw new com.nageoffer.ai.ragent.framework.exception.ClientException(
                        "重复绑定：" + ref.type().name() + ":" + ref.refId());
            }
            if (ref.type() == RefType.ORCHESTRATION) {
                if (!ORCHESTRATION_MODES.contains(ref.refId())) {
                    throw new com.nageoffer.ai.ragent.framework.exception.ClientException(
                            "未知编排模式：" + ref.refId());
                }
                continue;
            }
            RefLookup lookup = refLookup == null ? null : refLookup.getIfAvailable();
            if (lookup == null) {
                throw new com.nageoffer.ai.ragent.framework.exception.ClientException(
                        "绑定引用核验未接线，拒绝保存：" + ref.type().name());
            }
            if (!lookup.existsEnabled(tenantId, ref.type(), ref.refId())) {
                throw new com.nageoffer.ai.ragent.framework.exception.ClientException(
                        "绑定引用无效、未启用或不可授权：" + ref.type().name() + ":" + ref.refId());
            }
        }
    }

    /** 归一化：只做去 null 与 trim，**不丢弃非法项**（非法项留给校验去拒绝）。 */
    private static List<BindingReference> normalise(List<BindingReference> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<BindingReference> out = new ArrayList<>(refs.size());
        for (BindingReference ref : refs) {
            if (ref == null) {
                continue;
            }
            out.add(new BindingReference(ref.type(), ref.refId() == null ? null : ref.refId().trim()));
        }
        return List.copyOf(out);
    }
}
