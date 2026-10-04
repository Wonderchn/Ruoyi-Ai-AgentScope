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

package com.nageoffer.ai.ragent.core.ingest;

/**
 * 向量落点身份：块写到哪个逻辑分区、用哪个模型、必须是多少维，由知识库配置（L2）与部署配置（L1）合成
 * <p>
 * {@link #partition} 是逻辑分区键，与 {@code rag.core.vector.VectorSpaceId} 表示的物理空间（PG 下是共享表与共享索引，
 * Milvus 下是 collection）不是一回事，两者都别叫 collectionName；模型与维度随身携带，缺一个都不允许落到系统默认值
 *
 * <p><b>P1.3b：{@link #tenantId} 是落点身份的一部分，不是可选标签。</b>
 * 向量表是共享物理表，缺租户条件时"按文档删除"会删掉别人的行；把它放进落点身份，
 * 是为了让"这条向量属于谁"与"写到哪个分区、用哪个模型"在同一处、由同一个产生地决定。
 *
 * @param tenantId       所属租户，来自当前可信执行主体
 * @param partition      逻辑分区键，取自知识库的 collection_name
 * @param embeddingModel 嵌入模型 ID，取自知识库配置
 * @param dimension      向量维度，取自部署级配置，全局硬约束
 */
public record VectorTarget(String tenantId, String partition, String embeddingModel, int dimension) {

    public VectorTarget {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空——向量落点必须携带租户归属");
        }
        if (partition == null || partition.isBlank()) {
            throw new IllegalArgumentException("partition 不能为空");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("embeddingModel 不能为空，partition=" + partition
                    + "——嵌入模型是知识库级约束性配置，不允许回落到系统默认");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension 必须 > 0，实际 " + dimension);
        }
    }
}
