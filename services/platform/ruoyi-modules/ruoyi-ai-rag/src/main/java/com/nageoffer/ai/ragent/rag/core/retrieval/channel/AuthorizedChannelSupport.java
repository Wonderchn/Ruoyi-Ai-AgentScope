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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 关键词 / 图谱通道共用的授权作用域消费工具（F05:4-R1）。
 *
 * <p>与向量通道同口径：
 * <ul>
 *   <li>空作用域（无授权 KB / 无授权 collection / 无已发布 chunk 引用）必须在任何后端调用之前交空卷；</li>
 *   <li>检索范围（{@code RetrievalScope} 的 target/supplement）只是"想查哪些库"，
 *       必须与 {@link AuthorizedRetrievalScope#authorizedCollections()} 求交；</li>
 *   <li>后端返回的每条证据必须同时满足：collection 在授权集合内、chunk id 在
 *       {@code publishedChunkRefs} 内（允许可选的 {@code chunk:} 前缀）。</li>
 * </ul>
 *
 * <p>本类只读作用域，不构造/不修改作用域；空集永远不会被解释成"查全库"。
 */
final class AuthorizedChannelSupport {

    private static final String CHUNK_REF_PREFIX = "chunk:";

    private AuthorizedChannelSupport() {
    }

    /**
     * 作用域是否可用于发起检索：非空、有授权 collection、有已发布 chunk 引用。
     */
    static boolean hasUsableScope(AuthorizedRetrievalScope scope) {
        return scope != null
                && !scope.isEmpty()
                && !scope.authorizedCollections().isEmpty()
                && !scope.publishedChunkRefs().isEmpty();
    }

    /**
     * 把请求侧 collection 列表与授权集合求交（保持请求顺序、去重）。
     *
     * <p>请求列表为空表示本次没有任何目标库，结果为空（不是"全部授权库"）。
     */
    static List<String> intersect(AuthorizedRetrievalScope scope, Collection<String> requested) {
        if (!hasUsableScope(scope) || requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<String> authorized = scope.authorizedCollections();
        Set<String> result = new LinkedHashSet<>();
        for (String collection : requested) {
            if (collection != null && authorized.contains(collection)) {
                result.add(collection);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 结果侧纵深防御：丢弃未授权 collection、未发布 chunk，保持输入顺序。
     */
    static List<RetrievedChunk> retainAuthorized(AuthorizedRetrievalScope scope,
                                                 List<RetrievedChunk> chunks) {
        if (!hasUsableScope(scope) || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Set<String> authorized = scope.authorizedCollections();
        List<RetrievedChunk> kept = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            if (chunk.getCollectionName() == null
                    || !authorized.contains(chunk.getCollectionName())) {
                continue;
            }
            if (!isPublished(scope, chunk.getId())) {
                continue;
            }
            kept.add(chunk);
        }
        return kept;
    }

    /**
     * chunk id 是否在已发布集合内；兼容后端 id 带 {@code chunk:} 前缀或已发布引用带该前缀两种形状。
     */
    static boolean isPublished(AuthorizedRetrievalScope scope, String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return false;
        }
        Set<String> published = scope.publishedChunkRefs();
        if (published.contains(chunkId)) {
            return true;
        }
        String stripped = chunkId.startsWith(CHUNK_REF_PREFIX)
                ? chunkId.substring(CHUNK_REF_PREFIX.length())
                : chunkId;
        if (stripped.isBlank()) {
            return false;
        }
        return published.contains(stripped) || published.contains(CHUNK_REF_PREFIX + stripped);
    }
}
