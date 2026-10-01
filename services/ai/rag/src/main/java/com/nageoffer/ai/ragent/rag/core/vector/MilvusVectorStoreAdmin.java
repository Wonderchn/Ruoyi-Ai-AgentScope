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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStoreAdmin implements VectorStoreAdmin {

    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        // 全 Milvus 共用一个物理 collection，幂等确保其存在；各知识库以 collection_name 标量字段区分
        String sharedCollection = ragDefaultProperties.getCollectionName();
        boolean exists = Boolean.TRUE.equals(milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(sharedCollection).build()
        ));
        if (exists) {
            return;
        }

        List<CreateCollectionReq.FieldSchema> fieldSchemaList = new ArrayList<>();

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("id")
                        .dataType(DataType.VarChar)
                        // chunkId 为雪花主键（最长 19 位），与 PG t_knowledge_vector.id VARCHAR(20) 对齐
                        .maxLength(20)
                        .isPrimaryKey(true)
                        .autoID(false)
                        .build()
        );

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("collection_name")
                        .dataType(DataType.VarChar)
                        .maxLength(64)
                        .build()
        );

        // P1.3b：共享 collection 里所有租户的行混在一起，租户必须是显式标量字段。
        // 没有这个字段时，写行携带的 tenant_id 会被 Milvus 直接拒绝，而检索侧的
        // tenant 过滤也无从成立——即"写入被拒"好过"写进去了但读不出来/读串了"。
        // 长度与 ExecutionPrincipal 的 tenantId 契约（1..64）一致。
        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("tenant_id")
                        .dataType(DataType.VarChar)
                        .maxLength(64)
                        .build()
        );

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("content")
                        .dataType(DataType.VarChar)
                        .maxLength(65535)
                        .build()
        );

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("metadata")
                        .dataType(DataType.JSON)
                        .build()
        );

        fieldSchemaList.add(
                CreateCollectionReq.FieldSchema.builder()
                        .name("embedding")
                        .dataType(DataType.FloatVector)
                        .dimension(ragDefaultProperties.getDimension())
                        .build()
        );

        CreateCollectionReq.CollectionSchema collectionSchema = CreateCollectionReq.CollectionSchema
                .builder()
                .fieldSchemaList(fieldSchemaList)
                .build();

        IndexParam hnswIndex = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .indexName("embedding")
                .extraParams(Map.of(
                        "M", "48",
                        "efConstruction", "200",
                        "mmap.enabled", "false"
                ))
                .build();

        // 共享 collection 下每次检索都是「collection_name 过滤 + ANN」，为标量字段建倒排索引，避免大数据量时的全量标量扫描
        IndexParam collectionNameIndex = IndexParam.builder()
                .fieldName("collection_name")
                .indexType(IndexParam.IndexType.INVERTED)
                .indexName("collection_name")
                .build();

        // 租户条件出现在每一次检索与每一次删除的 filter 里，因此它同样需要倒排索引：
        // 少这个索引，租户过滤会退化成全量标量扫描，隔离在语义上成立但代价不可接受。
        IndexParam tenantIdIndex = IndexParam.builder()
                .fieldName("tenant_id")
                .indexType(IndexParam.IndexType.INVERTED)
                .indexName("tenant_id")
                .build();

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(sharedCollection)
                .collectionSchema(collectionSchema)
                .primaryFieldName("id")
                .vectorFieldName("embedding")
                .metricType(ragDefaultProperties.getMetricType())
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .indexParams(List.of(hnswIndex, collectionNameIndex, tenantIdIndex))
                .description("RAG 共享向量存储")
                .build();

        milvusClient.createCollection(createReq);
        log.info("已创建 Milvus 共享 collection: {}", sharedCollection);
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        // 共享 collection 模型下，存在性即共享 collection 是否已创建（忽略传入的逻辑名）
        return Boolean.TRUE.equals(milvusClient.hasCollection(
                HasCollectionReq.builder().collectionName(ragDefaultProperties.getCollectionName()).build()
        ));
    }

    @Override
    public void dropVectorSpace(String collectionName) {
        // 共享 collection 模型：按 collection_name 标量字段删除该知识库的行，而非 drop 整个 collection。
        //
        // P1.3b 已知缺口（尚未修）：本方法仍然只按 collection_name 过滤，没有租户条件。
        // 在共享 collection 上，这意味着若两个租户存在同名 collection_name，拆除操作会跨租户删行。
        // 完整的修法是让该入口同样接收并校验 tenantId（涉及 VectorStoreAdmin 接口签名，
        // 超出本单元范围，已记录在 P1-closeout §7.3）。
        //
        // 这里先加一道"空值即拒绝"的兜底：空 collection_name 会让 filter 变成
        // collection_name == ""，而拼错成空串的前缀式删除是本方法最容易造成大范围误删的形态。
        if (collectionName == null || collectionName.isBlank()) {
            throw new ClientException("dropVectorSpace 需要明确的 collection_name：空值会匹配到全部行");
        }
        String filter = "collection_name == \"" + escapeFilterValue(collectionName) + "\"";
        DeleteResp resp = milvusClient.delete(DeleteReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .filter(filter)
                .build());
        log.info("已删除 collection_name={} 的向量行，deleteCnt={}", collectionName, resp.getDeleteCnt());
    }

    /**
     * Milvus 表达式里的字符串字面量转义，与 {@code MilvusVectorStoreService} / 
     * {@code MilvusVectorRetrieverService} 同一语义：反斜杠加倍、双引号转义。
     *
     * <p>三个类各自持有一份而不是抽公共工具，是因为它们分属"管理面 / 写侧 / 读侧"三条独立路径，
     * 抽公共类会让任一路径的转义改动同时影响另外两条；代价是三处必须保持一致。
     */
    static String escapeFilterValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
