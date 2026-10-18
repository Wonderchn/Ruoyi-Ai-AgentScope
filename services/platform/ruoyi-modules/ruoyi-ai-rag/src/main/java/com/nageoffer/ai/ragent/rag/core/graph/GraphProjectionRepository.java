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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 图谱投影仓储（主库唯一读源）。
 *
 * <p>F08 的隔离权威是主库投影表，不是 LightRAG 的 collection：LightRAG 单实例即单图，
 * collection 也不是授权边界（V7:2036-2040）。因此本接口的每一条 SQL 都必须带
 * {@code tenant_id} 谓词；读侧范围只允许 {@code kb_id IN (:kbs)} 与 {@code doc_id}。
 *
 * <p>投影行是"可重建派生、非权威"数据：写入只由 {@link GraphProjectionService} 按文档
 * replace，读路径绝不回写。
 */
public interface GraphProjectionRepository {

    /** 知识库主库事实（collection_name 是向量落点选择符，不是授权边界）。 */
    record KbRow(String id, String name, String collectionName) {
    }

    /** 文档主库事实（status/chunkCount 用于重建前置条件）。 */
    record DocRow(String id, String kbId, String docName, String collectionName,
                  String status, Integer chunkCount) {
    }

    /** 投影实体行 + 主库 join 出的来源元数据。 */
    record EntityRow(String nodeId, String entityKey, String name, String entityType, String description,
                     String docId, String kbId, String docName, String collectionName) {
    }

    /** 投影关系行 + 主库 join 出的来源元数据；空 sourceFilePath 表示来源由两端节点派生。 */
    record RelationRow(String sourceNodeId, String targetNodeId, String sourceKey, String targetKey,
                       String relationLabel, String description, String sourceFilePath,
                       String docId, String kbId, String docName, String collectionName) {
    }

    /** 投影统计（状态卡）。 */
    record ProjectionStats(long entities, long relations, long docs, Long lastSyncedAtEpochMs) {
    }

    /** 待写入实体行（doc/kb/tenant 由调用参数给出；id 由仓储生成）。 */
    record EntityWriteRow(String nodeId, String entityKey, String name, String entityType,
                          String description, String sourceFilePath) {
    }

    /** 待写入关系行。 */
    record RelationWriteRow(String sourceNodeId, String targetNodeId, String sourceKey, String targetKey,
                            String relationLabel, String description, String sourceFilePath) {
    }

    Optional<KbRow> findKnowledgeBase(String tenantId, String kbId);

    /** collection 非唯一时返回多行，由调用方按"0 或 >1 → 404"处理。 */
    List<KbRow> findKnowledgeBasesByCollection(String tenantId, String collectionName);

    Optional<DocRow> findDocument(String tenantId, String docId);

    /** 重建候选：status=success 且 chunk_count>0，按创建序（稳定）。 */
    List<DocRow> findRebuildDocuments(String tenantId, String kbId);

    /** 按 chunk_index 拼接正文；无分块返回 null/空。 */
    String loadDocumentText(String tenantId, String docId);

    List<EntityRow> findEntities(String tenantId, Collection<String> kbIds, String docId);

    List<RelationRow> findRelations(String tenantId, Collection<String> kbIds, String docId);

    /** 标签聚合：空 keyword 按出现次数降序，否则 ILIKE。 */
    List<String> findLabels(String tenantId, Collection<String> kbIds, String docId,
                            String keyword, int limit);

    /** 因主库 join 失败/来源不可解析而丢弃的投影行数（诊断，期望 0）。 */
    int countUnattributed(String tenantId, Collection<String> kbIds, String docId);

    ProjectionStats stats(String tenantId);

    /** 按 (tenant, doc) 先删后插；调用方保证在事务内。 */
    void replaceDocumentProjection(String tenantId, String kbId, String docId,
                                   List<EntityWriteRow> entities, List<RelationWriteRow> relations);

    /** KB 模式重建前清空该库全部投影（含已软删文档的残留行）。 */
    void deleteKnowledgeBaseProjection(String tenantId, String kbId);
}
