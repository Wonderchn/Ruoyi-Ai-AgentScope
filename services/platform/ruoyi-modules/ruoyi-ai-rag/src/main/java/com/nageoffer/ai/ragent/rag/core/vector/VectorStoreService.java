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

import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

import java.util.List;

/**
 * 向量存储服务接口（写入 / 删除侧）。
 *
 * <p><b>P1.3b 契约</b>：每个方法的第一个参数都是 {@code tenantId}。这不是可选修饰，
 * 而是这条写路径的必需事实——向量表是<b>共享物理表</b>：
 * <ul>
 *   <li>"按 chunkId 删除"在跨租户下会删掉别人的行；</li>
 *   <li>upsert 的冲突目标若是全局主键，缺 tenant 条件时后写者会覆盖先写者。</li>
 * </ul>
 * 因此所有 SQL / 客户端调用的 WHERE 与 ON CONFLICT 都必须带上 tenant。
 *
 * <p>实现必须对 null / 空白 tenantId 直接拒绝（见 {@link #requireTenant(String)}），
 * 不得默认成某个租户、也不得省略该条件。
 */
public interface VectorStoreService {

    /**
     * 批量建立文档的向量索引。
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     * @param chunks         文档切片列表，须包含已计算好的 embedding
     */
    void indexDocumentChunks(String tenantId, String collectionName, String docId, List<EmbeddedChunk> chunks);

    /**
     * 更新单个 chunk 的向量索引。
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 向量空间名称
     * @param docId          文档唯一标识
     * @param chunk          待更新的文档切片，须包含最新的 embedding
     */
    void updateChunk(String tenantId, String collectionName, String docId, EmbeddedChunk chunk);

    /**
     * 删除文档的所有向量索引。
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 向量空间名称
     * @param docId          文档唯一标识
     */
    void deleteDocumentVectors(String tenantId, String collectionName, String docId);

    /**
     * 删除指定的单个 chunk 向量索引。
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 向量空间名称
     * @param chunkId        chunk 的唯一标识
     */
    void deleteChunkById(String tenantId, String collectionName, String chunkId);

    /**
     * 批量删除指定 chunk 的向量索引。
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 向量空间名称
     * @param chunkIds       chunk 唯一标识列表
     */
    void deleteChunksByIds(String tenantId, String collectionName, List<String> chunkIds);

    /**
     * 统一的租户前置校验。
     *
     * <p>所有实现的第一行都应调用它：没有租户就没有任何一条可以安全执行的向量写语句，
     * 因此这里抛错，而不是返回、也不是回落到"不带 tenant 的旧语句"。
     *
     * <p>复用 {@link ExecutionPrincipal#requireTenantId(String)} 的字符契约
     * （1..64、不含冒号、无首尾空白），保证与主体侧判定完全一致。
     *
     * @throws ClientException tenantId 非法
     */
    static void requireTenant(String tenantId) {
        ExecutionPrincipal.requireTenantId(tenantId);
    }
}
