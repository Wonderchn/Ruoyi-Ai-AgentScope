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

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F05:4-A：P2 发布双写的 ES 主键契约。
 *
 * <p>{@code _id} 必须等于 {@code <versionId>:<chunkKey>}——与 {@code P2PublishedChunkSql.id}
 * 同形，关键词通道的 {@code publishedChunkRefs} 才能与授权集合匹配；元信息必须带
 * tenant/doc/kb/collection，生命周期清理按这些键过滤。
 */
@Tag("dev")
class EsKeywordIndexPublishedWriteTest {

    private final EsKeywordIndexService service = new EsKeywordIndexService(
            mock(ElasticsearchClient.class), new KeywordProperties(), null);

    @Test
    @DisplayName("P2 块写入：_id=versionId:chunkKey，元信息带租户/文档/知识库/collection")
    void publishedChunksUseVersionScopedIds() {
        BulkRequest request = service.buildPublishedBulk("shared_idx", "T1", "collection1", "kb-1",
                "doc-1", "v2", List.of(
                        new EsKeywordIndexService.PublishedChunk("c1", 0, "first"),
                        new EsKeywordIndexService.PublishedChunk("c2", 1, "second")));

        assertThat(request).isNotNull();
        assertThat(request.operations()).hasSize(2);
        assertThat(request.operations().get(0).index().id()).isEqualTo("v2:c1");
        assertThat(request.operations().get(1).index().id()).isEqualTo("v2:c2");
        assertThat(request.operations().get(0).index().index()).isEqualTo("shared_idx");
        assertThat(documentOf(request.operations().get(0).index()))
                .containsEntry("tenant_id", "T1")
                .containsEntry("collection_name", "collection1")
                .containsEntry("doc_id", "doc-1")
                .containsEntry("kb_id", "kb-1")
                .containsEntry("version_id", "v2")
                .containsEntry("chunk_index", 0)
                .containsEntry("content", "first");
    }

    @Test
    @DisplayName("无可写块：不构造 bulk 请求（零 ES 调用）")
    void emptyOrBlankChunksProduceNoRequest() {
        assertThat(service.buildPublishedBulk("shared_idx", "T1", "collection1", "kb-1",
                "doc-1", "v2", List.of())).isNull();
        assertThat(service.buildPublishedBulk("shared_idx", "T1", "collection1", "kb-1",
                "doc-1", "v2", List.of(new EsKeywordIndexService.PublishedChunk(" ", 0, "x")))).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> documentOf(IndexOperation operation) {
        return (Map<String, Object>) operation.document();
    }
}
