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

package com.nageoffer.ai.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图谱读路径的范围解析（F08 安全核心）。
 *
 * <p>旧实现用 {@code collection + "_"} 子串判归属：collection 不是授权边界
 * （V7:2036-2040；跨租户可同名），且同租户 {@code kb}/{@code kb_hr} 合法共存，
 * 子串匹配既会串库也会把别库行带进主路。这里改为：
 * <ol>
 *   <li>功能级 {@code kb.read} 在线核查；</li>
 *   <li>授权服务解析"当前主体在该动作下的 KB 允许集合"；</li>
 *   <li>显式 kbId/collection/doc 必须落在允许集合内，否则统一 404；</li>
 *   <li>doc 过滤追加 doc 级 grant；collection 只作为 KB 选择器，经主库
 *       {@code (tenant_id, collection_name)} 唯一解析（0 或 &gt;1 行 → 404）。</li>
 * </ol>
 *
 * <p>空允许集合是合法的空范围：读路径返回 200 空图，绝不回落全库。
 */
public class GraphScopeResolver {

    /** 解析后的只读范围：tenant 来自执行主体，kbIds 非空才查投影。 */
    public record GraphScope(String tenantId, Set<String> kbIds, String docId) {

        public GraphScope {
            kbIds = kbIds == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(kbIds));
        }

        public boolean isEmpty() {
            return kbIds.isEmpty();
        }
    }

    private final GraphProjectionRepository repository;
    private final AiResourceAuthorizationService authorization;

    public GraphScopeResolver(GraphProjectionRepository repository,
                              AiResourceAuthorizationService authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    public GraphScope resolve(String kbId, String collection, String docId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String tenantId = principal.tenantId();
        authorization.requireFunction(principal, "kb.read", "tenant:graph");

        String requestedKb = StrUtil.isBlank(kbId) ? null : kbId.trim();
        AuthorizedResourceScope scope = authorization.resolveScope(principal, "kb.read",
                requestedKb == null ? List.of() : List.of("kb:" + requestedKb));
        Set<String> authorizedKbIds = kbIdsOf(scope);
        if (requestedKb != null && !authorizedKbIds.contains(requestedKb)) {
            throw notFound();
        }

        String requestedDoc = StrUtil.isBlank(docId) ? null : docId.trim();
        if (requestedDoc != null) {
            GraphProjectionRepository.DocRow doc = repository.findDocument(tenantId, requestedDoc)
                    .orElseThrow(GraphScopeResolver::notFound);
            if (requestedKb != null && !requestedKb.equals(doc.kbId())) {
                throw notFound();
            }
            if (!authorizedKbIds.contains(doc.kbId())) {
                throw notFound();
            }
            Verdict verdict = authorization.check(principal, "kb.read", "doc:" + requestedDoc);
            if (verdict == Verdict.UNKNOWN) {
                throw new ServiceException("platform authorization is unavailable");
            }
            if (verdict != Verdict.GRANT) {
                throw notFound();
            }
            return new GraphScope(tenantId, Set.of(doc.kbId()), requestedDoc);
        }

        if (StrUtil.isNotBlank(collection)) {
            List<GraphProjectionRepository.KbRow> rows =
                    repository.findKnowledgeBasesByCollection(tenantId, collection.trim());
            if (rows.size() != 1) {
                // 0 行 = 不存在或跨租户；>1 行 = 数据不合法。两者同外显，不猜一个。
                throw notFound();
            }
            GraphProjectionRepository.KbRow kb = rows.get(0);
            if (!authorizedKbIds.contains(kb.id())) {
                throw notFound();
            }
            return new GraphScope(tenantId, Set.of(kb.id()), null);
        }

        if (requestedKb != null) {
            repository.findKnowledgeBase(tenantId, requestedKb).orElseThrow(GraphScopeResolver::notFound);
            return new GraphScope(tenantId, Set.of(requestedKb), null);
        }

        return new GraphScope(tenantId, authorizedKbIds, null);
    }

    private static Set<String> kbIdsOf(AuthorizedResourceScope scope) {
        Set<String> kbIds = new LinkedHashSet<>();
        for (String ref : scope.authorizedRefs()) {
            if (ref != null && ref.startsWith("kb:") && ref.length() > 3) {
                kbIds.add(ref.substring(3));
            }
        }
        return kbIds;
    }

    private static GraphOperationException notFound() {
        return new GraphOperationException(GraphErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
    }
}
