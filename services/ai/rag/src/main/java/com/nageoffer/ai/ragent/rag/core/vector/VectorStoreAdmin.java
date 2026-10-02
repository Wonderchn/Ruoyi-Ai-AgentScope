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

/**
 * 向量空间元数据/索引管理（与检索解耦）
 * 用于确保空间存在：不存在就按规格创建；存在则校验兼容性
 *
 * <p><b>P1.3b：为什么"建"不带租户而"拆"必须带。</b>
 * 物理向量空间是<b>共享</b>的——PG 是一张共享表，Milvus 是一个共享 collection，
 * 租户行靠 {@code tenant_id} 区分。因此：
 * <ul>
 *   <li>{@code ensureVectorSpace} / {@code vectorSpaceExists} 只操作共享空间的<b>结构</b>
 *       （建表/建 collection/建索引），结构本身不属于任何租户，加租户参数是无意义的；
 *   <li>{@code dropVectorSpace} 会<b>删数据行</b>，而在共享空间上按
 *       {@code collection_name} 单独过滤并不构成归属判定：两个租户可以各有同名知识库，
 *       于是"删掉这个知识库的向量"会删到别人的行。所以它必须带租户。
 * </ul>
 */
public interface VectorStoreAdmin {

    /**
     * 幂等：确保向量空间存在（不存在则创建）
     *
     * @param spec 向量空间规格（跨引擎统一定义）
     */
    void ensureVectorSpace(VectorSpaceSpec spec);

    /**
     * 只判断存在性（不创建）
     */
    boolean vectorSpaceExists(VectorSpaceId spaceId);

    /**
     * 幂等：销毁向量空间（与 {@link #ensureVectorSpace} 对应）
     * <p>
     * - Milvus：删除该知识库对应的 collection（不存在则跳过）
     * - PG：删除共享表中属于该 collection 的残留向量行（不动共享 HNSW 索引）
     *
     * <p>两个后端的删除条件都<b>必须</b>同时限定租户；缺租户一律拒绝，
     * 不得退化成只按 collection_name 删除。
     *
     * @param tenantId       所属租户（不可为空）
     * @param collectionName 知识库 collection 名称
     */
    void dropVectorSpace(String tenantId, String collectionName);

    /**
     * 租户前置校验，语义与 {@code VectorStoreService.requireTenant} 一致。
     */
    static void requireTenant(String tenantId) {
        com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal.requireTenantId(tenantId);
    }
}
