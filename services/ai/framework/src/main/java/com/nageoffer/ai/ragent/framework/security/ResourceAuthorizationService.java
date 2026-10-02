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

package com.nageoffer.ai.ragent.framework.security;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI 本地资源授权服务（05 §4.1 的"AI 唯一资源 ACL 权威"）。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>platform 负责</b>：tenant 启用/期限、成员是否当前有效、功能 permission、
 *       数据范围候选（组织事实）、policyVersion；</li>
 *   <li><b>本服务负责</b>：AI 资源 registry 事实、持久 ACL 交集、父继承收窄、
 *       tombstone、aclVersion 事务与生效判定。</li>
 * </ul>
 * 两侧求交：platform 允许 <b>且</b> AI ACL 允许才放行；任一侧缺版本/不可用即拒绝。
 *
 * <p>关键拒绝语义（不得放宽）：
 * <ul>
 *   <li>未知/缺失版本、读错版本：拒绝（<b>不</b>默认初始化到 1）；</li>
 *   <li>空 ACL：<b>不</b>等于公开；只有 owner 与显式 grant 可见；</li>
 *   <li>管理员身份不构成内容授权豁免；</li>
 *   <li>父资源授权不使 tombstone 子资源可见；子资源可收窄但不可扩张；</li>
 *   <li>空授权集合不得回落到"全部资源"。</li>
 * </ul>
 */
public interface ResourceAuthorizationService {

    /** 单个资源的判定结果。 */
    enum Verdict {
        /** 允许：已授权且资源当前有效。 */
        GRANT,
        /** 拒绝：无授权、跨租户或资源不存在——对外统一 404。 */
        DENY,
        /** 拒绝且需要重新取版本：本服务快照版本与请求不一致。 */
        STALE,
        /** 无法判定：registry/epoch 不可用或数据不一致——对外 503，<b>不放行</b>。 */
        UNKNOWN
    }

    /**
     * 一次判定动作的授权集合。
     *
     * @param principal 已验签/已在线核实的执行主体
     * @param action    canonical 动作（如 {@code kb.read}）
     * @param requested 请求侧选择条件（可为空 = 不额外收窄）；实现<b>不得</b>把空当作全库
     * @throws StaleVersionException 主体 pv/av 与本地事实不一致
     * @throws com.nageoffer.ai.ragent.framework.exception.ServiceException 事实源不可用（不放行）
     */
    AuthorizedResourceScope resolveScope(ExecutionPrincipal principal, String action,
                                         Collection<String> requested);

    /**
     * 单个资源是否可访问（含父继承与 tombstone 判定）。
     *
     * <p>资源不存在、跨租户、无授权三种情况一律返回 {@link Verdict#DENY}，
     * 调用方据此返回同一个 404，不泄露存在性。
     */
    Verdict check(ExecutionPrincipal principal, String action, String resourceRef);

    /**
     * 批量判定，用于 list 与分页（减少往返且保证同一版本快照）。
     *
     * @return ref → verdict 映射；未出现的 ref 视为 {@link Verdict#DENY}
     */
    Map<String, Verdict> checkBatch(ExecutionPrincipal principal, String action, Collection<String> resourceRefs);

    /**
     * 取当前租户 ACL 版本；不存在或读失败即拒绝（不返回默认值 1）。
     *
     * @throws com.nageoffer.ai.ragent.framework.exception.ServiceException 无 epoch 行或读取失败
     */
    int currentAclVersion(String tenantId);

    /**
     * 资源 registry 事实读取端口（由 rag 模块用正式 Mapper/原生 SQL 实现）。
     *
     * <p>刻意把端口定义在本接口内：这样"AI 资源事实"的实现只有一处可接线，
     * 不会在别处又冒出一个"绕过授权的注册表查询"。
     */
    interface FactPort {

        /**
         * 当前租户 ACL 版本。
         *
         * @return {@code empty} 表示租户没有 epoch 行（调用方必须拒绝，不得默认 1）
         */
        Optional<Integer> aclVersion(String tenantId);

        /**
         * 读取一批资源事实。
         *
         * <p>返回的 map 以 {@code resourceRef} 为键；缺失的 ref 表示资源不存在或不属于该租户，
         * 调用方按 DENY 处理。
         */
        Map<String, ResourceFact> facts(String tenantId, Collection<String> resourceRefs);
    }

    /**
     * platform 组织候选匹配端口（05 §4.1 的 {@code POST /internal/platform/v1/authorization/subjects/match}）。
     *
     * <p>返回<b>逐候选布尔匹配</b>与同一个 pv：AI 拿它与本地持久 ACL 求交，
     * 而不是把 platform 的判定当成完整授权。平台故障/坏响应必须抛错（调用方按 UNKNOWN 处理），
     * <b>不得</b>返回"全部未匹配"来伪装成正常的空集合。
     */
    interface SubjectMatchPort {

        /**
         * @param principal    当前主体
         * @param subjectRefs  候选主体引用（最多 200；实现需分页）
         * @param policyVersion 本次请求使用的 pv，必须原样带回比对
         * @return 逐候选匹配结果；返回的 pv 必须等于请求 pv
         * @throws com.nageoffer.ai.ragent.framework.exception.ServiceException 平台不可用或响应不可信
         */
        MatchResult match(ExecutionPrincipal principal, Collection<String> subjectRefs, int policyVersion);
        default MatchResult match(ExecutionPrincipal principal, Collection<String> subjectRefs, int policyVersion, String action) {
            return match(principal, subjectRefs, policyVersion);
        }
    }

    /**
     * 组织候选匹配结果。
     *
     * @param matched       命中的 subjectRef 集合
     * @param policyVersion 平台返回的当前 pv
     */
    record MatchResult(Set<String> matched, int policyVersion) {

        public MatchResult {
            matched = matched == null ? Set.of() : Set.copyOf(matched);
        }

        /** 与请求 pv 是否一致；不一致表示本次匹配结果不可用。 */
        public boolean agreesWith(int requestedPolicyVersion) {
            return policyVersion == requestedPolicyVersion;
        }
    }

    /**
     * 一条持久 ACL 规则。
     *
     * <p>{@code subjectRef} 的编码是 {@code subjectType:subjectId}（例如
     * {@code member:platform:T1:2101}、{@code department:1102}、{@code role:9101}、
     * {@code tenant_all:T1}）。编码由 AI 侧 registry 生成，framework 只做交集与匹配。
     */
    record AclRule(String resourceRef,
                   String subjectType,
                   String subjectId,
                   String action,
                   Long expiresAtEpochSecond) {

        /** 支持的主体类型（跨租户分享不在其中）。 */
        public static final Set<String> SUPPORTED_SUBJECT_TYPES =
                Set.of("member", "department", "role", "tenant_all");

        public AclRule {
            if (subjectType == null || !SUPPORTED_SUBJECT_TYPES.contains(subjectType)) {
                throw new IllegalArgumentException("unsupported acl subjectType: " + subjectType);
            }
        }

        /** 稳定编码，用于批量提交给 platform 的组织候选匹配。 */
        public String subjectRef() {
            return subjectType + ":" + subjectId;
        }

        /** 是否适用于该动作。 */
        public boolean appliesTo(String requestedAction) {
            return requestedAction != null && requestedAction.equals(action);
        }
    }

    /**
     * 一个资源的归属与状态事实。
     *
     * @param status          {@code ACTIVE} / {@code DELETED}（tombstone）等
     * @param resourceVersion 资源版本，随归属/ACL/删除变化递增
     * @param acl            该资源当前有效（未过期）的 ACL 规则
     */
    record ResourceFact(String resourceRef,
                        String resourceType,
                        String parentRef,
                        String ownerMemberId,
                        String ownerDeptId,
                        String status,
                        int resourceVersion,
                        List<AclRule> acl) {

        /** 有效状态字面量。 */
        public static final String STATUS_ACTIVE = "ACTIVE";

        public ResourceFact {
            acl = acl == null ? List.of() : List.copyOf(acl);
        }

        /** tombstone 优先于任何 ACL：删除即为不可见。 */
        public boolean isTombstoned() {
            return status == null || !STATUS_ACTIVE.equalsIgnoreCase(status.trim());
        }

        /**
         * 本资源是否为该成员所有。
         *
         * <p>owner 是显式允许规则的一部分，但仍要过 platform 功能/数据范围——
         * 本方法<b>不</b>构成完整判定。
         */
        public boolean isOwnedBy(String membershipId) {
            return membershipId != null && membershipId.equals(ownerMemberId);
        }
    }

    /** 供测试与断言使用的空事实视图。 */
    static Map<String, ResourceFact> emptyFacts() {
        return new LinkedHashMap<>();
    }

    /** 从一组资源事实里收集去重后的 subjectRef（提交给 platform 做组织候选匹配）。 */
    static Set<String> collectSubjectRefs(Collection<ResourceFact> facts) {
        Set<String> refs = new LinkedHashSet<>();
        if (facts == null) {
            return refs;
        }
        for (ResourceFact fact : facts) {
            if (fact == null) {
                continue;
            }
            for (AclRule rule : fact.acl()) {
                refs.add(rule.subjectRef());
            }
        }
        return refs;
    }

    /**
     * 父继承展开：把资源的父链（限于给定事实集合内）摊平。
     *
     * <p>语义：子资源可以<b>收窄</b>（自己带更严格 ACL），不能扩张；
     * 任一层 tombstone 即整体不可见。父不在给定集合内时按"父未知"处理，
     * 调用方必须拒绝而不是假定父已授权。
     */
    static List<ResourceFact> inheritanceChain(Map<String, ResourceFact> facts, ResourceFact child) {
        List<ResourceFact> chain = new ArrayList<>();
        ResourceFact current = child;
        int guard = 0;
        while (current != null && guard++ < 32) {
            chain.add(current);
            String parentRef = current.parentRef();
            current = parentRef == null ? null : facts.get(parentRef);
        }
        return chain;
    }
}
