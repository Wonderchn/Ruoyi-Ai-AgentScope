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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** F08 读/重建测试的纯内存仓储，记录每次调用的 tenant/kb 范围以便断言隔离谓词。 */
final class FakeGraphProjectionRepository implements GraphProjectionRepository {

    final Map<String, KbRow> kbs = new LinkedHashMap<>();
    final Map<String, DocRow> docs = new LinkedHashMap<>();
    final Map<String, String> texts = new LinkedHashMap<>();
    final List<EntityRow> entities = new ArrayList<>();
    final List<RelationRow> relations = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    final List<String> replacedDocs = new ArrayList<>();
    final List<String> deletedKbs = new ArrayList<>();
    final List<EntityWriteRow> insertedEntities = new ArrayList<>();
    final List<RelationWriteRow> insertedRelations = new ArrayList<>();
    ProjectionStats stats = new ProjectionStats(0, 0, 0, null);
    int unattributed = 0;
    List<DocRow> rebuildDocuments = List.of();

    @Override
    public Optional<KbRow> findKnowledgeBase(String tenantId, String kbId) {
        calls.add("kb:" + tenantId + ":" + kbId);
        return Optional.ofNullable(kbs.get(kbId));
    }

    @Override
    public List<KbRow> findKnowledgeBasesByCollection(String tenantId, String collectionName) {
        calls.add("collection:" + tenantId + ":" + collectionName);
        return kbs.values().stream()
                .filter(kb -> collectionName.equals(kb.collectionName()))
                .toList();
    }

    @Override
    public Optional<DocRow> findDocument(String tenantId, String docId) {
        calls.add("doc:" + tenantId + ":" + docId);
        return Optional.ofNullable(docs.get(docId));
    }

    @Override
    public List<DocRow> findRebuildDocuments(String tenantId, String kbId) {
        calls.add("rebuild-docs:" + tenantId + ":" + kbId);
        return rebuildDocuments;
    }

    @Override
    public String loadDocumentText(String tenantId, String docId) {
        calls.add("text:" + tenantId + ":" + docId);
        return texts.get(docId);
    }

    @Override
    public List<EntityRow> findEntities(String tenantId, Collection<String> kbIds, String docId) {
        calls.add("entities:" + tenantId + ":" + String.join(",", new LinkedHashSet<>(kbIds)) + ":" + docId);
        return entities.stream()
                .filter(row -> kbIds.contains(row.kbId()))
                .filter(row -> docId == null || docId.equals(row.docId()))
                .toList();
    }

    @Override
    public List<RelationRow> findRelations(String tenantId, Collection<String> kbIds, String docId) {
        calls.add("relations:" + tenantId + ":" + String.join(",", new LinkedHashSet<>(kbIds)) + ":" + docId);
        return relations.stream()
                .filter(row -> kbIds.contains(row.kbId()))
                .filter(row -> docId == null || docId.equals(row.docId()))
                .toList();
    }

    @Override
    public List<String> findLabels(String tenantId, Collection<String> kbIds, String docId,
                                   String keyword, int limit) {
        calls.add("labels:" + tenantId + ":" + String.join(",", new LinkedHashSet<>(kbIds)) + ":"
                + docId + ":" + keyword + ":" + limit);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (EntityRow row : entities) {
            if (!kbIds.contains(row.kbId()) || (docId != null && !docId.equals(row.docId()))) {
                continue;
            }
            if (keyword != null && !keyword.isBlank()
                    && !row.name().toLowerCase().contains(keyword.toLowerCase())) {
                continue;
            }
            values.add(row.name());
        }
        return values.stream().limit(limit).toList();
    }

    @Override
    public int countUnattributed(String tenantId, Collection<String> kbIds, String docId) {
        return unattributed;
    }

    @Override
    public ProjectionStats stats(String tenantId) {
        calls.add("stats:" + tenantId);
        return stats;
    }

    @Override
    public void replaceDocumentProjection(String tenantId, String kbId, String docId,
                                          List<EntityWriteRow> entityRows,
                                          List<RelationWriteRow> relationRows) {
        replacedDocs.add(docId);
        insertedEntities.addAll(entityRows);
        insertedRelations.addAll(relationRows);
    }

    @Override
    public void deleteKnowledgeBaseProjection(String tenantId, String kbId) {
        deletedKbs.add(kbId);
    }
}
