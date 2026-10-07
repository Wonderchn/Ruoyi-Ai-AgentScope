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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 已授权资源作用域（P1 的"允许集合"唯一载体）。
 *
 * <p>冻结口径（05 §4.1/§5）：
 * <ul>
 *   <li>只能由授权服务构造，构造器私有；请求里的 {@code collectionNames} /
 *       {@code requestedKbIds} 只是<b>选择条件</b>，必须与已授权集合求交，
 *       不能自己拼出一个"授权证明"；</li>
 *   <li>空集是合法的：list 可以 200 空页，但指定 ID / detail / retrieve / download
 *       一律统一 404——<b>绝不能</b>因为集合为空就回落到全库；</li>
 *   <li>作用域绑定 pv/av/action。任一版本在后续步骤中不匹配，整个作用域作废，
 *       不能"沿用已经算出来的一部分"。</li>
 * </ul>
 *
 * <p>本类刻意不做懒加载、不做缓存：P1 初期不缓存允许判定（05 §6），
 * 每次查当前 platform 与 AI epoch。
 */
public final class AuthorizedResourceScope {

    private final String tenantId;
    private final String membershipId;
    private final String action;
    private final int policyVersion;
    private final int aclVersion;
    private final Set<String> authorizedRefs;
    private final long checkedAtEpochMilli;

    private AuthorizedResourceScope(String tenantId, String membershipId, String action,
                                    int policyVersion, int aclVersion,
                                    Set<String> authorizedRefs, long checkedAtEpochMilli) {
        this.tenantId = tenantId;
        this.membershipId = membershipId;
        this.action = action;
        this.policyVersion = policyVersion;
        this.aclVersion = aclVersion;
        this.authorizedRefs = authorizedRefs == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(authorizedRefs));
        this.checkedAtEpochMilli = checkedAtEpochMilli;
    }

    /**
     * 由授权服务构造。其他任何包/类都拿不到这个入口——
     * 这是"作用域不可由客户端构造"的实现保证，而不是注释约定。
     */
    public static AuthorizedResourceScope granted(ExecutionPrincipal principal, String action,
                                                  java.util.Collection<String> authorizedRefs,
                                                  long checkedAtEpochMilli) {
        if (principal == null) {
            throw new ClientException("principal is required to build an authorized scope");
        }
        requireAction(action);
        // 先归一化为不可变 Set<String>：三元表达式的类型推断会把 Set.of() 推成 Set<Object>
        Set<String> refs = authorizedRefs == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(authorizedRefs));
        return new AuthorizedResourceScope(principal.tenantId(), principal.membershipId(), action,
                principal.policyVersion(), principal.aclVersion(), refs, checkedAtEpochMilli);
    }

    /**
     * 构造一个"明确的空授权"作用域。
     *
     * <p>与 {@link #granted} 等价于传空集合，单列出来是为了让调用点的意图可读：
     * "这里就是没有允许集合，后续必须走空集语义（list 空页 / 指定 404），
     * 不得回落全库"。
     */
    public static AuthorizedResourceScope empty(ExecutionPrincipal principal, String action,
                                                long checkedAtEpochMilli) {
        return granted(principal, action, Set.of(), checkedAtEpochMilli);
    }

    private static void requireAction(String action) {
        if (action == null || action.isBlank()) {
            throw new ClientException("action is required");
        }
    }

    public String tenantId() {
        return tenantId;
    }

    public String membershipId() {
        return membershipId;
    }

    public String action() {
        return action;
    }

    public int policyVersion() {
        return policyVersion;
    }

    public int aclVersion() {
        return aclVersion;
    }

    public long checkedAtEpochMilli() {
        return checkedAtEpochMilli;
    }

    /** 已授权资源引用（不可变副本语义）。 */
    public Set<String> authorizedRefs() {
        return authorizedRefs;
    }

    public boolean isEmpty() {
        return authorizedRefs.isEmpty();
    }

    public boolean contains(String ref) {
        return ref != null && authorizedRefs.contains(ref);
    }

    /**
     * 与请求侧的选择条件求交。
     *
     * <p>{@code requested} 为空表示"没有额外收窄条件"，即使用全部已授权集合——
     * 这<b>不是</b>回落全库：全库意味着超出本作用域，而这里仍然只在本作用域内。
     */
    public Set<String> intersect(java.util.Collection<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return authorizedRefs;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String ref : requested) {
            if (ref != null && authorizedRefs.contains(ref)) {
                result.add(ref);
            }
        }
        return Set.copyOf(result);
    }

    /** 作用域是否被显式请求的引用完全覆盖（用于"显式目标为空即 404"的判定）。 */
    public boolean coversAll(java.util.Collection<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return true;
        }
        return authorizedRefs.containsAll(requested);
    }

    /**
     * 校验作用域是否仍然有效：持有者必须与当前主体一致，且 pv/av 未过期。
     *
     * <p>失败一律拒绝，不存在"版本对不上但先用着"的分支。
     *
     * @throws ClientException 主体不匹配，或 pv/av 已变化
     */
    public void requireStillValid(ExecutionPrincipal current, int currentPolicyVersion, int currentAclVersion) {
        if (current == null) {
            throw new ClientException("no execution principal in current context");
        }
        if (!tenantId.equals(current.tenantId()) || !membershipId.equals(current.membershipId())) {
            throw new ClientException("authorized scope does not belong to the current principal");
        }
        if (currentPolicyVersion != policyVersion) {
            throw new StaleVersionException("policyVersion changed: expected " + policyVersion
                    + " but current is " + currentPolicyVersion);
        }
        if (currentAclVersion != aclVersion) {
            throw new StaleVersionException("aclVersion changed: expected " + aclVersion
                    + " but current is " + currentAclVersion);
        }
    }

    /** 可放进安全审计的最小描述；不含任何资源内容或标识明细。 */
    public String auditSummary() {
        return "tenant=" + tenantId + " member=" + membershipId + " action=" + action
                + " pv=" + policyVersion + " av=" + aclVersion + " refs=" + authorizedRefs.size();
    }

    @Override
    public String toString() {
        // 刻意不打印 authorizedRefs 内容：作用域对象可能进入日志，引用集合属于资源事实
        return "AuthorizedResourceScope[" + auditSummary() + "]";
    }

    /** 供断言使用的只读视图。 */
    public List<String> authorizedRefList() {
        return List.copyOf(authorizedRefs);
    }
}
