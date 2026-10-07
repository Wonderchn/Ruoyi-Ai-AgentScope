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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 已授权检索作用域（P1.3b 的检索层唯一授权载体）。
 *
 * <p>与 {@link AuthorizedResourceScope} 的分工：后者是"通用资源允许集合"，
 * 本类是它在<b>检索语义</b>上的投影——把允许集合按 KB / DOC / chunk 分层，
 * 并额外带上已发布版本与 tombstone 视图，供向量/关键词/图谱各通道与
 * metadata enrichment、重排前复核共用。
 *
 * <p>冻结口径（05 §5 U07）：
 * <ul>
 *   <li>{@code collections} 是<b>选择条件</b>（"在哪些逻辑库里查"），
 *       {@code authorizedKbRefs} 才是<b>授权事实</b>。两者必须求交；
 *       请求里的 collectionNames 不能自己构成授权；</li>
 *   <li>空作用域必须返回空结果，且<b>在 embedding / SQL / client / rerank 之前</b>就拒绝：
 *       不允许"空集合 → 查全库"的 fallback；</li>
 *   <li>作用域绑定 tenant/member/action/pv/av；任一版本变化即整作用域作废；</li>
 *   <li>作用域对象只由授权解析器构造，检索层不得自行 new。</li>
 * </ul>
 */
public final class AuthorizedRetrievalScope {

    private final AuthorizedResourceScope resourceScope;
    private final Set<String> authorizedKbRefs;
    private final Set<String> authorizedDocRefs;
    private final Set<String> publishedChunkRefs;
    private final Set<String> collections;

    private AuthorizedRetrievalScope(AuthorizedResourceScope resourceScope,
                                     Set<String> authorizedKbRefs,
                                     Set<String> authorizedDocRefs,
                                     Set<String> publishedChunkRefs,
                                     Set<String> collections) {
        this.resourceScope = resourceScope;
        this.authorizedKbRefs = Set.copyOf(authorizedKbRefs);
        this.authorizedDocRefs = Set.copyOf(authorizedDocRefs);
        this.publishedChunkRefs = Set.copyOf(publishedChunkRefs);
        this.collections = Set.copyOf(collections);
    }

    /**
     * 由授权层构造；其他包/类拿不到入口——"作用域不可由客户端构造"是结构保证，不是注释约定。
     */
    public static AuthorizedRetrievalScope of(AuthorizedResourceScope resourceScope,
                                              Collection<String> authorizedKbRefs,
                                              Collection<String> authorizedDocRefs,
                                              Collection<String> publishedChunkRefs,
                                              Collection<String> collections) {
        if (resourceScope == null) {
            throw new ClientException("resource scope is required to build a retrieval scope");
        }
        return new AuthorizedRetrievalScope(resourceScope,
                authorizedKbRefs == null ? Set.of() : new LinkedHashSet<>(authorizedKbRefs),
                authorizedDocRefs == null ? Set.of() : new LinkedHashSet<>(authorizedDocRefs),
                publishedChunkRefs == null ? Set.of() : new LinkedHashSet<>(publishedChunkRefs),
                collections == null ? Set.of() : new LinkedHashSet<>(collections));
    }

    /** 无主体时的唯一合法取值：既不授权也不允许回落全库。 */
    public static AuthorizedRetrievalScope denied() {
        return new AuthorizedRetrievalScope(null, Set.of(), Set.of(), Set.of(), Set.of());
    }

    public String tenantId() {
        return resourceScope == null ? null : resourceScope.tenantId();
    }

    public String membershipId() {
        return resourceScope == null ? null : resourceScope.membershipId();
    }

    public String action() {
        return resourceScope == null ? null : resourceScope.action();
    }

    public int policyVersion() {
        return resourceScope == null ? 0 : resourceScope.policyVersion();
    }

    public int aclVersion() {
        return resourceScope == null ? 0 : resourceScope.aclVersion();
    }

    /**
     * 是否为空授权。
     *
     * <p>空 = 没有任何授权 KB。调用方必须走"空集语义"（list 200 空页 / 指定 ID 404），
     * <b>不得</b>把它当成"没有限制"。
     */
    public boolean isEmpty() {
        return resourceScope == null || authorizedKbRefs.isEmpty();
    }

    public Set<String> authorizedKbRefs() {
        return authorizedKbRefs;
    }

    public Set<String> authorizedDocRefs() {
        return authorizedDocRefs;
    }

    public Set<String> publishedChunkRefs() {
        return publishedChunkRefs;
    }

    /** 授权 KB 对应的逻辑 collection 名（供向量/关键词后端构造 IN 条件）。 */
    public Set<String> authorizedCollections() {
        return collections;
    }

    /**
     * 与请求侧选择条件求交后的 collection 列表。
     *
     * <p>请求给的 collectionNames 只是"想查哪些库"；不在授权集合里的一律剔除。
     * 请求为空表示不额外收窄（仍限于授权集合），<b>不是</b>查全库。
     *
     * @return 有序去重后的 collection 名；空表示本次不该发起任何检索
     */
    public List<String> effectiveCollections(Collection<String> requestedCollections) {
        if (isEmpty() || collections.isEmpty()) {
            return List.of();
        }
        if (requestedCollections == null || requestedCollections.isEmpty()) {
            return List.copyOf(collections);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String requested : requestedCollections) {
            if (requested != null && collections.contains(requested)) {
                result.add(requested);
            }
        }
        return List.copyOf(result);
    }

    /** 指定 KB 引用是否在授权集合内。 */
    public boolean allowsKb(String kbRef) {
        return kbRef != null && authorizedKbRefs.contains(kbRef);
    }

    /** 指定文档引用是否在授权集合内（考虑父 KB 授权与子收窄的结果）。 */
    public boolean allowsDoc(String docRef) {
        return docRef != null && authorizedDocRefs.contains(docRef);
    }

    /**
     * 复核一批候选是否仍属于本作用域（重排前 / 输出前使用）。
     *
     * @param candidates 候选文档或 chunk 引用
     * @return 仍被允许的候选，保持输入顺序
     */
    public List<String> retainAllowed(Collection<String> candidates) {
        if (candidates == null || candidates.isEmpty() || isEmpty()) {
            return List.of();
        }
        List<String> kept = new java.util.ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (candidate != null && (authorizedDocRefs.contains(candidate)
                    || publishedChunkRefs.contains(candidate))) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /**
     * 校验作用域是否仍对当前主体有效（撤权/版本变化后必须整作用域作废）。
     *
     * @throws ClientException 主体不匹配
     * @throws com.nageoffer.ai.ragent.framework.security.StaleVersionException pv/av 变化
     */
    public void requireStillValid(ExecutionPrincipal current, int currentPolicyVersion, int currentAclVersion) {
        if (resourceScope == null) {
            throw new ClientException("no authorized retrieval scope in current context");
        }
        resourceScope.requireStillValid(current, currentPolicyVersion, currentAclVersion);
    }

    /** 可放进安全审计的最小描述；不含资源内容或引用明细。 */
    public String auditSummary() {
        return "tenant=" + tenantId() + " member=" + membershipId() + " action=" + action()
                + " pv=" + policyVersion() + " av=" + aclVersion()
                + " kbs=" + authorizedKbRefs.size() + " docs=" + authorizedDocRefs.size()
                + " collections=" + collections.size();
    }

    @Override
    public String toString() {
        return "AuthorizedRetrievalScope[" + auditSummary() + "]";
    }
}
