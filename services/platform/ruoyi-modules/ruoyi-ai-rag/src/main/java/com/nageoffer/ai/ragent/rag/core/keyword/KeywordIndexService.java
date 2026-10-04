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

package com.nageoffer.ai.ragent.rag.core.keyword;

import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;

import java.util.List;

/**
 * 关键词索引服务 SPI：与向量写入的 {@link com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService}
 * 对称，把 chunk 的关键词文本写入全文检索引擎
 * <p>
 * 写入时文档主键（ES {@code _id}）必须等于向量库主键 chunkId，否则跨模态去重与融合无法对齐；所有知识库
 * 写同一物理索引、以 {@code collection_name} 区分，与向量库共享 collection 同构；实现由
 * {@code rag.keyword.type} 选择，none 时无实现注册，写侧装饰器也随之不注册
 *
 * <p><b>P1.3b 契约</b>：与 {@code VectorStoreService} 一样，每个方法的第一个参数都是
 * {@code tenantId}，且每个条件都必须真正参与过滤。
 *
 * <p>注意 {@code deleteChunkById} 的历史注释曾说"chunkId 为全局唯一雪花主键，直接按 _id 删除，
 * 无需再限定 collection_name"——那句话把<b>主键唯一性</b>当成了<b>授权依据</b>。
 * 唯一性是"不会撞车"，不是"有权删除"：一旦某处生成非预期 id（迁移、导入、不同 id 生成器、
 * 未来换 ID 策略），仅按 id 删除就会跨租户删数据，而且共享索引上没有任何东西会拦住它。
 * 因此删除一律同时限定租户。
 */
public interface KeywordIndexService {

    /**
     * 批量建立文档分块的关键词索引
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 知识库 collection 名称（写入 collection_name 字段用于区分）
     * @param docId          文档唯一标识
     * @param chunks         文档切片列表
     */
    void indexDocumentChunks(String tenantId, String collectionName, String docId, List<EmbeddedChunk> chunks);

    /**
     * 更新单个 chunk 的关键词索引
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 知识库 collection 名称
     * @param docId          文档唯一标识
     * @param chunk          待更新的文档切片
     */
    void updateChunk(String tenantId, String collectionName, String docId, EmbeddedChunk chunk);

    /**
     * 删除文档的所有关键词索引
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 知识库 collection 名称
     * @param docId          文档唯一标识
     */
    void deleteDocumentIndex(String tenantId, String collectionName, String docId);

    /**
     * 删除指定的单个 chunk 关键词索引
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 知识库 collection 名称
     * @param chunkId        chunk 唯一标识
     */
    void deleteChunkById(String tenantId, String collectionName, String chunkId);

    /**
     * 批量删除指定 chunk 的关键词索引
     *
     * @param tenantId       当前租户（不可为空）
     * @param collectionName 知识库 collection 名称
     * @param chunkIds       chunk 唯一标识列表
     */
    void deleteChunksByIds(String tenantId, String collectionName, List<String> chunkIds);

    /**
     * 删除整个知识库在共享索引中的全部关键词数据（删库清理用）
     *
     * @param tenantId       当前租户（不可为空）：共享索引上"同名知识库"可能存在于多个租户
     * @param collectionName 知识库 collection 名称
     */
    void deleteByCollection(String tenantId, String collectionName);

    /**
     * 租户前置校验，语义与 {@code VectorStoreService.requireTenant} 一致：
     * 缺租户时抛错，而不是退化成不带租户条件的语句。
     */
    static void requireTenant(String tenantId) {
        com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal.requireTenantId(tenantId);
    }
}
