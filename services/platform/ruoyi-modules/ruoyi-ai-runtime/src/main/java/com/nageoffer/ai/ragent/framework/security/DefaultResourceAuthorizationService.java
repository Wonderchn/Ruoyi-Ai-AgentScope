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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 资源授权默认实现：本地 registry/ACL 事实 ∩ platform 组织候选。
 *
 * <p>判定顺序（每一步失败原因不同，但对外都收敛到"拒绝"，不泄露细节）：
 * <ol>
 *   <li>主体存在且携带 pv/av；</li>
 *   <li>本地 epoch 存在且与主体 av 一致 —— 否则 STALE（409）；</li>
 *   <li>读资源事实（含父链）—— 事实源不可用即 UNKNOWN（503，不放行）；</li>
 *   <li>tombstone 检查（任一层删除即不可见）；</li>
 *   <li>收集 ACL subjectRef 与 owner 自身 member，一次性提交 platform 做组织候选匹配；</li>
 *   <li>求交：owner <b>或</b> 显式 grant，<b>且</b>各自都命中 platform 匹配。全空即 DENY。</li>
 * </ol>
 *
 * <p><b>不缓存</b>允许判定（05 §6）：每次调用都重读当前事实。
 *
 * <p>本类不负责返回 HTTP：调用方把 DENY 映射成统一 404、STALE 映射成 409、
 * UNKNOWN 映射成 503。这样"跨租户资源"与"不存在"天然同外显。
 */
public class DefaultResourceAuthorizationService implements ResourceAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultResourceAuthorizationService.class);

    /** 单次 subject 匹配的候选上限（05 §4.1）。 */
    static final int MAX_SUBJECT_CANDIDATES = 200;

    /** 父链展开深度上限，防止脏数据形成环。 */
    private static final int MAX_INHERITANCE_DEPTH = 32;

    private final FactPort factPort;
    private final SubjectMatchPort subjectMatchPort;
    private final Clock clock;

    public DefaultResourceAuthorizationService(FactPort factPort, SubjectMatchPort subjectMatchPort, Clock clock) {
        this.factPort = factPort;
        this.subjectMatchPort = subjectMatchPort;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public AuthorizedResourceScope resolveScope(ExecutionPrincipal principal, String action,
                                                Collection<String> requested) {
        requirePrincipal(principal);
        if (action == null || action.isBlank()) {
            throw new ClientException("action is required");
        }

        requireAgreeingAclVersion(principal);

        Collection<String> candidates = requested == null ? List.of() : requested;
        if (candidates.isEmpty()) {
            // 没有指定候选：允许集合就是"当前 action 下所有已授权资源"。
            // 这里必须由 FactPort 按 tenant+action 全量列出——绝不允许调用方自己拼一个全库集合。
            candidates = factPort.facts(principal.tenantId(), List.of()).keySet();
        }

        Map<String, Verdict> verdicts = evaluate(principal, action, candidates);
        List<String> granted = new ArrayList<>();
        for (Map.Entry<String, Verdict> entry : verdicts.entrySet()) {
            if (entry.getValue() == Verdict.GRANT) {
                granted.add(entry.getKey());
            }
        }

        long checkedAt = clock.millis();
        if (granted.isEmpty()) {
            // 空集是合法结果，但必须显式标记为空：后续指定 ID/detail/retrieve 一律 404，
            // 不允许把空集当作"没有限制"。
            return AuthorizedResourceScope.empty(principal, action, checkedAt);
        }
        return AuthorizedResourceScope.granted(principal, action, granted, checkedAt);
    }

    @Override
    public Verdict check(ExecutionPrincipal principal, String action, String resourceRef) {
        requirePrincipal(principal);
        if (resourceRef == null || resourceRef.isBlank()) {
            // 没有资源引用不能当成"通配放行"
            return Verdict.DENY;
        }
        return checkBatch(principal, action, List.of(resourceRef)).getOrDefault(resourceRef, Verdict.DENY);
    }

    @Override
    public Map<String, Verdict> checkBatch(ExecutionPrincipal principal, String action,
                                           Collection<String> resourceRefs) {
        requirePrincipal(principal);
        return evaluate(principal, action, resourceRefs == null ? List.of() : resourceRefs);
    }

    @Override
    public int currentAclVersion(String tenantId) {
        ExecutionPrincipal.requireTenantId(tenantId);
        return readAclVersion(tenantId)
                .orElseThrow(() -> new ServiceException(
                        "acl epoch is missing for tenant " + tenantId + "; refusing to default it"));
    }

    private void requirePrincipal(ExecutionPrincipal principal) {
        if (principal == null) {
            throw new ClientException("no execution principal in current context");
        }
    }

    /** 读本地 epoch 并要求与主体 av 一致；缺失即拒绝（不默认 1）。 */
    private void requireAgreeingAclVersion(ExecutionPrincipal principal) {
        int current = readAclVersion(principal.tenantId())
                .orElseThrow(() -> new ServiceException(
                        "acl epoch is missing for tenant " + principal.tenantId()
                                + "; refusing to default it"));
        if (current != principal.aclVersion()) {
            throw new StaleVersionException("aclVersion changed: principal has " + principal.aclVersion()
                    + " but current is " + current);
        }
    }

    /**
     * 读 epoch，读失败即拒绝。
     *
     * <p>只保留异常类型名，不把底层驱动信息带进错误消息（可能含连接串）。
     */
    private Optional<Integer> readAclVersion(String tenantId) {
        try {
            return factPort.aclVersion(tenantId);
        } catch (RuntimeException e) {
            throw new ServiceException("acl epoch read failed for tenant " + tenantId
                    + ": " + e.getClass().getSimpleName());
        }
    }

    /** 读 epoch，失败返回 empty（由调用方决定 UNKNOWN 还是继续）。 */
    private Optional<Integer> readAclVersionQuietly(String tenantId) {
        try {
            return factPort.aclVersion(tenantId);
        } catch (RuntimeException e) {
            log.warn("acl epoch read failed tenant={} reason={}", tenantId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 核心求值：返回每个 ref 的 verdict。
     *
     * <p>只读取一次事实（同一版本快照），避免"前半段用了旧 ACL、后半段用了新 ACL"。
     */
    private Map<String, Verdict> evaluate(ExecutionPrincipal principal, String action,
                                          Collection<String> requested) {
        Map<String, Verdict> result = new LinkedHashMap<>();
        Set<String> refs = new LinkedHashSet<>();
        for (String ref : requested) {
            if (ref != null && !ref.isBlank()) {
                refs.add(ref);
            }
        }
        if (refs.isEmpty()) {
            return result;
        }

        // 版本前置检查：epoch 缺失或读失败 -> 全部 UNKNOWN；与主体不一致 -> 全部 STALE
        Optional<Integer> epoch = readAclVersionQuietly(principal.tenantId());
        if (epoch.isEmpty()) {
            refs.forEach(ref -> result.put(ref, Verdict.UNKNOWN));
            return result;
        }
        if (epoch.get() != principal.aclVersion()) {
            refs.forEach(ref -> result.put(ref, Verdict.STALE));
            return result;
        }

        Map<String, ResourceFact> facts;
        try {
            facts = factPort.facts(principal.tenantId(), refs);
        } catch (RuntimeException e) {
            log.warn("resource facts read failed tenant={} reason={}",
                    principal.tenantId(), e.getClass().getSimpleName());
            refs.forEach(ref -> result.put(ref, Verdict.UNKNOWN));
            return result;
        }
        if (facts == null) {
            refs.forEach(ref -> result.put(ref, Verdict.UNKNOWN));
            return result;
        }

        // 父链补读：父资源用于继承判定，必须与子资源在同一个快照语义下读取
        Map<String, ResourceFact> withParents = loadParents(principal.tenantId(), facts);

        // 收集所有相关 subjectRef（含父链），一次性提交 platform
        Set<String> subjectRefs = new LinkedHashSet<>();
        for (String ref : refs) {
            ResourceFact fact = withParents.get(ref);
            if (fact == null) {
                continue;
            }
            for (ResourceFact chainItem : inheritanceChain(withParents, fact)) {
                subjectRefs.addAll(collectSubjectRefs(List.of(chainItem)));
                if (!ownerSubjectRef(chainItem).isEmpty()) { subjectRefs.add(ownerSubjectRef(chainItem)); }
            }
            // owner 也必须过 platform：把 owner 自己的 member subject 一起提交，
            // 否则"平台已停用该成员/撤销其功能权限"的 owner 会因为没有 ACL 规则
            // 而绕过 platform 判定（owner 是允许规则的一部分，不是 platform 的替代品）。
            String ownerRef = ownerSubjectRef(fact);
            if (!ownerRef.isEmpty()) {
                subjectRefs.add(ownerRef);
            }
        }
        Set<String> matched = matchSubjects(principal, subjectRefs, refs, result, action);
        if (matched == null) {
            // 平台不可用或版本作废：全部 UNKNOWN/STALE，不放行
            return result;
        }

        for (String ref : refs) {
            if (result.containsKey(ref)) {
                continue;
            }
            ResourceFact fact = withParents.get(ref);
            if (fact == null) {
                // 资源不存在或不属于本租户：与"无权访问"同外显
                result.put(ref, Verdict.DENY);
                continue;
            }
            result.put(ref, decide(principal, action, withParents, fact, matched));
        }
        return result;
    }

    /** 补读父链；父缺失不补造。 */
    private Map<String, ResourceFact> loadParents(String tenantId, Map<String, ResourceFact> facts) {
        Map<String, ResourceFact> merged = new LinkedHashMap<>(facts);
        Set<String> pending = new LinkedHashSet<>();
        for (ResourceFact fact : facts.values()) {
            if (fact != null && fact.parentRef() != null && !merged.containsKey(fact.parentRef())) {
                pending.add(fact.parentRef());
            }
        }
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 8) {
            Map<String, ResourceFact> parents;
            try {
                parents = factPort.facts(tenantId, pending);
            } catch (RuntimeException e) {
                log.warn("parent facts read failed tenant={} reason={}", tenantId, e.getClass().getSimpleName());
                break;
            }
            if (parents == null || parents.isEmpty()) {
                break;
            }
            Set<String> next = new LinkedHashSet<>();
            for (Map.Entry<String, ResourceFact> entry : parents.entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
                if (entry.getValue() != null && entry.getValue().parentRef() != null
                        && !merged.containsKey(entry.getValue().parentRef())) {
                    next.add(entry.getValue().parentRef());
                }
            }
            pending = next;
        }
        return merged;
    }

    /**
     * 提交 platform 做组织候选匹配。
     *
     * @return 命中集合；平台不可用/响应不可信时返回 {@code null}，并把涉及的 ref 标成 UNKNOWN 或 STALE
     */
    private Set<String> matchSubjects(ExecutionPrincipal principal, Set<String> subjectRefs,
                                      Set<String> refs, Map<String, Verdict> result, String action) {
        if (subjectRefs.isEmpty()) {
            // 没有任何候选（例如资源无 owner 也无 ACL）：不需要问 platform，直接判 DENY
            return Set.of();
        }
        if (subjectMatchPort == null) {
            log.warn("subject match port is not configured; resource authorization cannot be completed");
            refs.forEach(ref -> result.put(ref, Verdict.UNKNOWN));
            return null;
        }
        if (subjectRefs.size() > MAX_SUBJECT_CANDIDATES) {
            // 超过单次上限必须拒绝而不是截断：截断会让"没提交的那些"静默变成无权
            log.warn("subject candidates exceed limit size={} limit={}",
                    subjectRefs.size(), MAX_SUBJECT_CANDIDATES);
            refs.forEach(ref -> result.put(ref, Verdict.UNKNOWN));
            return null;
        }
        MatchResult match;
        try {
            match = subjectMatchPort.match(principal, subjectRefs, principal.policyVersion(), action);
        } catch (StaleVersionException e) {
            refs.forEach(ref -> result.put(ref, Verdict.STALE));
            return null;
        } catch (RuntimeException e) {
            log.warn("subject match failed tenant={} reason={}",
                    principal.tenantId(), e.getClass().getSimpleName());
            refs.forEach(ref -> result.put(ref, Verdict.UNKNOWN));
            return null;
        }
        if (match == null || !match.agreesWith(principal.policyVersion())) {
            // 版本对不上：本次匹配结果整体作废，不能拼合不同版本的结果
            refs.forEach(ref -> result.put(ref, Verdict.STALE));
            return null;
        }
        return match.matched();
    }

    /** 单资源最终判定：tombstone → owner/显式 grant → platform 匹配。 */
    private Verdict decide(ExecutionPrincipal principal, String action,
                           Map<String, ResourceFact> facts, ResourceFact fact, Set<String> matchedSubjects) {
        List<ResourceFact> chain = inheritanceChain(facts, fact);
        if (chain.get(chain.size() - 1).parentRef() != null
                || chain.stream().map(ResourceFact::resourceRef).distinct().count() != chain.size()) {
            return Verdict.UNKNOWN;
        }
        for (ResourceFact item : chain) {
            if (item.isTombstoned()) {
                // 本层或任一层父被删除即不可见：父授权不能让删除的子资源复活
                return Verdict.DENY;
            }
        }

        // owner 允许的前提是 platform 也认可这个 member（owner subject 已在候选里提交过）
        long now = clock.instant().getEpochSecond();
        // 从根向子求交：子资源允许规则只能收窄父范围，不能恢复无权的父资源。
        java.util.Collections.reverse(chain);
        for (int index = 0; index < chain.size(); index++) {
            ResourceFact item = chain.get(index);
            boolean granted = item.isOwnedBy(principal.membershipId())
                    && matchedSubjects.contains(ownerSubjectRef(item));
            for (AclRule rule : item.acl()) {
                if (!rule.appliesTo(action)) {
                    continue;
                }
                if (rule.expiresAtEpochSecond() != null && rule.expiresAtEpochSecond() <= now) {
                    // 过期是时间判定：不能等定时清理才失效
                    continue;
                }
                if (matchedSubjects.contains(rule.subjectRef())) {
                    granted = true;
                }
            }
            // 根必须明确允许；无子 ACL 时继承父允许，有子 ACL 则再次求交。
            if (!granted && (index == 0 || !item.acl().isEmpty())) { return Verdict.DENY; }
        }
        // 两侧求交已完成：owner 或显式 grant 命中，且各自都过了 platform 匹配
        return Verdict.GRANT;
    }

    /** owner 对应的 member subject 编码；owner 缺失时不产生候选（该资源对任何人都是 DENY）。 */
    private static String ownerSubjectRef(ResourceFact fact) {
        String owner = fact.ownerMemberId();
        return owner == null || owner.isBlank() ? "" : "member:" + owner;
    }

    /** 父链展开（深度受限防环）。 */
    private static List<ResourceFact> inheritanceChain(Map<String, ResourceFact> facts, ResourceFact child) {
        List<ResourceFact> chain = new ArrayList<>();
        ResourceFact current = child;
        int guard = 0;
        while (current != null && guard++ < MAX_INHERITANCE_DEPTH) {
            chain.add(current);
            String parentRef = current.parentRef();
            current = parentRef == null ? null : facts.get(parentRef);
        }
        return chain;
    }

    /** 从资源事实收集去重后的 subjectRef。 */
    private static Set<String> collectSubjectRefs(Collection<ResourceFact> facts) {
        Set<String> refs = new LinkedHashSet<>();
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
}
