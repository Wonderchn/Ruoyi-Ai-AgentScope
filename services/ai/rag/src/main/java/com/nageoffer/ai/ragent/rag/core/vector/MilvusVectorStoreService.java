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

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 向量写侧实现（P1.3b 租户贯通）。
 *
 * <p>所有逻辑库共用同一个物理 collection，{@code tenant_id} 标量字段就是唯一的隔离边界：
 * <ul>
 *   <li>写入 / upsert 的行必须与 {@code id}、{@code collection_name} 并列带顶层 {@code tenant_id}；</li>
 *   <li>每个删除表达式必须是"租户条件 ∧ 目标条件"，不得只有 {@code id} 或 {@code doc_id}。</li>
 * </ul>
 *
 * <p>{@code id} 全局唯一只是主键的属性，不是授权依据：跨租户下按 {@code id} 删除同样会命中
 * 别人的行。历史缺陷（已修）：删除过滤器曾只有 {@code id} / {@code collection_name + doc_id}，
 * 没有租户条件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    private static final Gson GSON = new Gson();

    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public void indexDocumentChunks(String tenantId, String collectionName, String docId, List<EmbeddedChunk> chunks) {
        VectorStoreService.requireTenant(tenantId);
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不允许为空"));

        final int dim = ragDefaultProperties.getDimension();
        List<float[]> vectors = extractVectors(chunks, dim);

        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            EmbeddedChunk chunk = chunks.get(i);

            String content = chunk.content() == null ? "" : chunk.content();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = buildMetadata(docId, chunk);

            JsonObject row = new JsonObject();
            // 物理主键带租户前缀：Milvus 只支持单字段主键，无法像 PG 那样建 (tenant_id, id) 复合唯一键，
            // 于是"跨租户同 chunkId 互相覆盖"只能靠主键本身承载租户来消除。
            row.addProperty("id", physicalKey(tenantId, chunk.chunkId()));
            // 逻辑块 ID 单独存：读回时业务身份来自这里，而不是物理主键。
            row.addProperty("chunk_id", chunk.chunkId());
            // tenant_id 与 id / collection_name 并列的顶层标量字段：共享物理 collection 下它就是授权边界，
            // 缺了它检索侧的租户过滤永远不会命中，写入本身也就没有归属可言
            row.addProperty("tenant_id", tenantId);
            row.addProperty("collection_name", collectionName);
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors.get(i)));

            rows.add(row);
        }

        InsertReq req = InsertReq.builder()
                .collectionName(sharedCollection())
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Milvus chunk 建立/写入向量索引成功, tenantId={}, collection={}, rows={}",
                tenantId, collectionName, resp.getInsertCnt());
    }

    @Override
    public void updateChunk(String tenantId, String collectionName, String docId, EmbeddedChunk chunk) {
        VectorStoreService.requireTenant(tenantId);
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 对象不能为空"));

        final int dim = ragDefaultProperties.getDimension();
        float[] vector = extractVector(chunk, dim);

        String chunkPk = physicalKey(tenantId, chunk.chunkId());

        String content = chunk.content() == null ? "" : chunk.content();
        if (content.length() > 65535) {
            content = content.substring(0, 65535);
        }

        JsonObject metadata = buildMetadata(docId, chunk);

        JsonObject row = new JsonObject();
        row.addProperty("id", chunkPk);
        // chunk_id 保存**逻辑**块 ID：物理主键已带租户前缀，读回时只有这个字段能还原业务身份。
        row.addProperty("chunk_id", chunk.chunkId());
        // 与 insert 同口径：tenant_id 是顶层标量字段，upsert 覆盖的是"本租户的这一行"
        row.addProperty("tenant_id", tenantId);
        row.addProperty("collection_name", collectionName);
        row.addProperty("content", content);
        row.add("metadata", metadata);
        row.add("embedding", toJsonArray(vector));

        UpsertReq upsertReq = UpsertReq.builder()
                .collectionName(sharedCollection())
                .data(List.of(row))
                .build();

        UpsertResp resp = milvusClient.upsert(upsertReq);
        log.info("Milvus 更新 chunk 向量索引成功, tenantId={}, collection={}, docId={}, chunkId={}, upsertCnt={}",
                tenantId, collectionName, docId, chunkPk, resp.getUpsertCnt());
    }

    @Override
    public void deleteDocumentVectors(String tenantId, String collectionName, String docId) {
        VectorStoreService.requireTenant(tenantId);
        // 共享 collection 下多库共存，doc_id 不再天然隔离，必须叠加 collection_name 限定；
        // 但真正的授权边界是 tenant_id：旧表达式只有 collection_name + doc_id，没有租户条件，
        // 于是"删除某文档向量"可以删掉别的租户同名 collection 里的同名文档。租户条件必须在最前面。
        String filter = tenantClause(tenantId)
                + " and collection_name == \"" + escape(collectionName) + "\""
                + " and metadata[\"doc_id\"] == \"" + escape(docId) + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定文档的所有 chunk 向量索引成功, tenantId={}, collection={}, docId={}, deleteCnt={}",
                tenantId, collectionName, docId, resp.getDeleteCnt());
    }

    @Override
    public void deleteChunkById(String tenantId, String collectionName, String chunkId) {
        VectorStoreService.requireTenant(tenantId);
        // 删的是本租户的**物理主键**（见 physicalKey）。仍保留租户条件作为第二道边界：
        // 主键带租户前缀使跨租户覆盖不可能，租户条件使跨租户删除不可能——两者都不依赖调用方自律。
        String filter = tenantClause(tenantId) + " and id == \"" + escape(physicalKey(tenantId, chunkId)) + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定 chunk 向量索引成功, tenantId={}, collection={}, chunkId={}, deleteCnt={}",
                tenantId, collectionName, chunkId, resp.getDeleteCnt());
    }

    @Override
    public void deleteChunksByIds(String tenantId, String collectionName, List<String> chunkIds) {
        VectorStoreService.requireTenant(tenantId);
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String idList = chunkIds.stream()
                .map(id -> physicalKey(tenantId, id))
                .map(MilvusVectorStoreService::escape)
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        // 批量形式与单条同口径：租户条件 ∧ 主键 in 列表，逐条都不得逃出租户边界
        String filter = tenantClause(tenantId) + " and id in [" + idList + "]";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 批量删除 chunk 向量索引成功, tenantId={}, collection={}, count={}, deleteCnt={}",
                tenantId, collectionName, chunkIds.size(), resp.getDeleteCnt());
    }

    /**
     * 构造租户过滤子句。
     *
     * <p>共享物理 collection 下 {@code tenant_id} 标量字段就是唯一的隔离边界，因此写侧每个删除
     * 表达式都<b>必须</b>以它开头，且不可省略、不可后置（与
     * {@link MilvusVectorRetrieverService#buildFilter} 同口径）。
     */
    static String tenantClause(String tenantId) {
        return "tenant_id == \"" + escape(tenantId) + "\"";
    }

    /**
     * 租户作用域的物理主键。
     *
     * <p><b>为什么需要它。</b>PG 侧用复合唯一键 {@code (tenant_id, id)} 消除"跨租户同 chunkId
     * 互相覆盖"，但 <b>Milvus 的主键只能是单一字段</b>，同样的修法在这里不成立。
     * 于是这个不变量只能由主键自身承载：把租户编进主键，两个租户的同一个 chunkId
     * 就落在两行上，upsert 不再互相覆盖。
     *
     * <p><b>为什么是 {@code tenantId + ":" + chunkId} 而不是哈希。</b>
     * 这个函数是确定性的、可逆读的，运维排查时能从主键直接看出归属；
     * 哈希会牺牲可读性却换不来额外保证（chunkId 本身已是雪花 ID，不需要再压缩长度）。
     * 分隔符用 {@code ":"}：租户 ID 契约本身就禁止冒号（{@code ExecutionPrincipal.requireTenantId}），
     * 因此这个拼接不会有歧义。
     *
     * <p><b>读回。</b>业务身份不从这个主键反推，而由同行的 {@code chunk_id} 标量字段给出，
     * 见 {@code MilvusVectorRetrieverService}。
     */
    static String physicalKey(String tenantId, String chunkId) {
        return tenantId + ":" + (chunkId == null ? "" : chunkId);
    }

    /**
     * 转义表达式字符串字面量里的反斜杠与双引号，避免租户名 / 库名 / chunkId 改写过滤条件。
     *
     * <p>null 归一成空串而不是字面量 {@code "null"}：真实数据里不存在 {@code tenant_id = ""}
     * 的行（{@code ExecutionPrincipal.requireTenantId} 拒绝空白租户），因此这是"查不到/删不到"，
     * 而不是"少一个条件"。
     *
     * <p>版本条件（{@code kb_version} / {@code doc_version}）暂不进表达式：这两个字段要等 V3
     * 写路径真正把它们写进行之后才谈得上过滤，现在拼接只会恒不匹配、静默删不到任何行。
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<float[]> extractVectors(List<EmbeddedChunk> chunks, int expectedDim) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            vectors.add(extractVector(chunk, expectedDim));
        }
        return vectors;
    }

    private float[] extractVector(EmbeddedChunk chunk, int expectedDim) {
        float[] vector = chunk.embedding();
        if (vector == null || vector.length == 0) {
            throw new ClientException("向量不能为空");
        }
        if (vector.length != expectedDim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + expectedDim);
        }
        return vector;
    }

    private JsonArray toJsonArray(float[] v) {
        JsonArray arr = new JsonArray(v.length);
        for (float x : v) {
            arr.add(x);
        }
        return arr;
    }

    private JsonObject buildMetadata(String docId, EmbeddedChunk chunk) {
        JsonObject metadata = new JsonObject();
        // 结构化元数据统一走唯一序列化点
        chunk.metadata().toMap().forEach((k, v) -> metadata.add(k, GSON.toJsonTree(v)));

        // collection_name 已提升为顶层标量字段，不再冗余写入 metadata
        metadata.addProperty("doc_id", docId);
        metadata.addProperty("chunk_index", chunk.index());
        return metadata;
    }

    /**
     * 全 Milvus 共用的物理 collection（所有知识库的 chunk 都写在这里，按 collection_name 标量字段区分）
     */
    private String sharedCollection() {
        return ragDefaultProperties.getCollectionName();
    }
}
