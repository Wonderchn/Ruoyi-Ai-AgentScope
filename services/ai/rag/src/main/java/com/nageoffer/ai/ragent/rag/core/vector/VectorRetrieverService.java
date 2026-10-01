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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;

import java.util.List;

/**
 * 向量检索服务接口。
 *
 * <p>封装对向量后端（pgvector / Milvus 等）的检索能力，从向量库中查找与问题最相关的
 * 若干文档片段（Chunk）。实现不得修改调用方传入的查询向量。
 *
 * <p><b>P1.3b 契约（重要）</b>：所有检索入口都<b>必须</b>携带
 * {@link AuthorizedRetrievalScope}。签名里没有"无作用域"的重载，是刻意为之——
 * 留着它就意味着存在一条不需要授权的调用路径，而底层的缺省拒绝守卫正是为了堵住这类绕过。
 *
 * <p>守卫语义（实现方必须遵守，且都在任何 IO 之前）：
 * <ol>
 *   <li>{@code scope == null} → {@link ClientException}。缺少授权上下文属于接线错误，
 *       不能静默返回空列表，否则会把"接线漏了"伪装成"没有命中"；</li>
 *   <li>{@code scope.isEmpty()} → 返回空列表，<b>不</b>做 embedding、<b>不</b>发 SQL/客户端请求；</li>
 *   <li>与请求侧 collection 求交后为空 → 返回空列表，绝不回落到"查全库"；</li>
 *   <li>只有到这一步才允许计算 embedding 并访问后端。</li>
 * </ol>
 */
public interface VectorRetrieverService {

    /**
     * 根据自然语言 Query 进行检索（需要 embedding）。
     *
     * @param scope         已授权检索作用域（不可为 {@code null}）
     * @param retrieveParam 向量检索请求参数
     * @return 命中文档 Chunk 列表，已按相似度倒序
     */
    List<RetrievedChunk> retrieve(AuthorizedRetrievalScope scope, RetrieveRequest retrieveParam);

    /**
     * 根据向量直接检索（复用已算好的 embedding）。
     * <p>
     * 调用方负责确保 vector 维度与后端 schema 一致；实现不得修改调用方传入的查询向量。
     *
     * @param scope         已授权检索作用域（不可为 {@code null}）
     * @param vector        查询向量
     * @param retrieveParam 向量检索请求参数
     */
    List<RetrievedChunk> retrieveByVector(AuthorizedRetrievalScope scope, float[] vector,
                                         RetrieveRequest retrieveParam);

    /**
     * 根据自然语言 Query 生成并归一化查询向量。
     *
     * <p>本方法不查库，但仍需要作用域：它会调用 embedding 模型，
     * 未授权请求不应产生任何模型调用。
     *
     * @param scope 已授权检索作用域（不可为 {@code null}）
     * @param query 用户自然语言问题
     */
    float[] embedAndNormalize(AuthorizedRetrievalScope scope, String query);

    /**
     * 是否支持在一次查询里跨多个 collection 过滤。
     *
     * <p>返回 true 时调用方用一次 {@link #retrieveByVector} 带总预算跨库召回；
     * 返回 false 时调用方退化为逐库并行 fan-out。两个分支下"预算即总量"的语义一致。
     */
    default boolean supportsGlobalRetrieval() {
        return false;
    }

    /**
     * 统一入口守卫：把 {@code null} 与空作用域收敛到同一个"抛错 / 空集"语义。
     *
     * <p>抽成静态方法是为了让所有实现共用同一判定，避免某个实现漏掉一条分支。
     *
     * @return {@code true} 表示应直接返回空结果、不做任何 IO
     * @throws ClientException 作用域为 {@code null}
     */
    static boolean mustReturnEmpty(AuthorizedRetrievalScope scope, String method) {
        if (scope == null) {
            throw new ClientException("authorized retrieval scope is required for " + method);
        }
        return scope.isEmpty();
    }
}
