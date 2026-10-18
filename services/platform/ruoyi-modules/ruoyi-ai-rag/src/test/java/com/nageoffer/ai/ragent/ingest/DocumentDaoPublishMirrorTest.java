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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordIndexService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * F05:4-A：P2 发布后的可选后端双写。
 *
 * <p>钉住 A1/A2 的发布侧契约：只在发布成功后写、{@code _id=versionId:chunkKey}、
 * 同版本重放幂等、tombstone 按 doc 清理、可选后端缺席时零外呼且不额外查库、
 * 后端失败不阻断发布（best-effort）。
 */
@Tag("dev")
class DocumentDaoPublishMirrorTest {

    private static final String TENANT = "T1";
    private static final String DOC = "doc-1";
    private static final String OLD_VERSION = "v-old";
    private static final String VERSION = "v2";
    private static final String RUN = "run-1";
    private static final long FENCE = 7L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EsKeywordIndexService es = mock(EsKeywordIndexService.class);
    private final LightRagClient graph = mock(LightRagClient.class);

    @Test
    @DisplayName("发布成功且 ES/图谱在场：先清理旧文档，再写当前版本全部块")
    void publishMirrorsPublishedChunksToBothBackends() throws Exception {
        stubPublishPath(List.of(OLD_VERSION));
        DocumentDao dao = new DocumentDao(jdbc, es, graph);

        assertThat(dao.publishVersionFenced(TENANT, DOC, VERSION, RUN, FENCE)).isTrue();

        verify(es).deleteDocumentIndex(TENANT, "collection1", DOC);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EsKeywordIndexService.PublishedChunk>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(es).indexPublishedChunks(eq(TENANT), eq("collection1"), eq("kb-1"), eq(DOC),
                eq(VERSION), captor.capture());
        assertThat(captor.getValue()).containsExactly(
                new EsKeywordIndexService.PublishedChunk("c1", 0, "first"),
                new EsKeywordIndexService.PublishedChunk("c2", 1, "second"));
        verify(graph).deleteByDoc(DOC);
        verify(graph).insertText("first\n\nsecond", "collection1_doc-1");
        verifyNoMoreInteractions(es, graph);
    }

    @Test
    @DisplayName("同版本重放：同 _id 覆盖，重复发布保持幂等")
    void replayUsesTheSameVersionScopedIds() {
        // 当前发布指针已是本版本：publishVersionFenced 走幂等早返回，双写仍按同形重放
        stubPublishPath(List.of(VERSION));
        DocumentDao dao = new DocumentDao(jdbc, es, graph);

        assertThat(dao.publishVersionFenced(TENANT, DOC, VERSION, RUN, FENCE)).isTrue();
        assertThat(dao.publishVersionFenced(TENANT, DOC, VERSION, RUN, FENCE)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EsKeywordIndexService.PublishedChunk>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(es, times(2)).indexPublishedChunks(eq(TENANT), eq("collection1"), eq("kb-1"), eq(DOC),
                eq(VERSION), captor.capture());
        assertThat(captor.getAllValues().get(0)).isEqualTo(captor.getAllValues().get(1));
        verify(es, times(2)).deleteDocumentIndex(TENANT, "collection1", DOC);
        verify(graph, times(2)).insertText("first\n\nsecond", "collection1_doc-1");
    }

    @Test
    @DisplayName("发布被拒绝（无当前文档/fence 过期）：零双写")
    void rejectedPublishNeverMirrors() {
        doReturn(List.of()).when(jdbc).query(
                contains("SELECT published_version_id FROM ai_document"), any(RowMapper.class),
                eq(TENANT), eq(DOC));
        DocumentDao dao = new DocumentDao(jdbc, es, graph);

        assertThat(dao.publishVersionFenced(TENANT, DOC, VERSION, RUN, FENCE)).isFalse();

        verifyNoInteractions(es, graph);
        verify(jdbc, never()).query(contains("FROM ai_document_chunk c"), any(RowMapper.class),
                any(), any());
    }

    @Test
    @DisplayName("发布事务失败（无可见 chunk）：零双写")
    void publishWithoutVisibleChunksNeverMirrors() {
        stubPublishPath(List.of(OLD_VERSION));
        doReturn(0).when(jdbc).update(contains("UPDATE ai_document_chunk SET state='PUBLISHED'"),
                any(Object[].class));
        DocumentDao dao = new DocumentDao(jdbc, es, graph);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dao.publishVersionFenced(TENANT, DOC, VERSION, RUN, FENCE))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(es, graph);
    }

    @Test
    @DisplayName("可选后端均缺席（默认 pg/none/无 graph）：零外呼、零额外查库")
    void absentBackendsKeepTheDefaultPublishPath() {
        stubPublishPath(List.of(OLD_VERSION));
        DocumentDao dao = new DocumentDao(jdbc);

        assertThat(dao.publishVersionFenced(TENANT, DOC, VERSION, RUN, FENCE)).isTrue();

        verify(jdbc, never()).query(contains("platform.ai_knowledge_base"), any(RowMapper.class),
                any(), any());
    }

    @Test
    @DisplayName("ES 写入失败：best-effort，发布仍成功")
    void esFailureDoesNotBlockPublish() {
        stubPublishPath(List.of(OLD_VERSION));
        doThrow(new IllegalStateException("es unavailable"))
                .when(es).deleteDocumentIndex(TENANT, "collection1", DOC);
        doThrow(new IllegalStateException("es unavailable"))
                .when(es).indexPublishedChunks(eq(TENANT), eq("collection1"), eq("kb-1"), eq(DOC),
                        eq(VERSION), any());
        DocumentDao dao = new DocumentDao(jdbc, es, graph);

        assertThat(dao.publishVersionFenced(TENANT, DOC, VERSION, RUN, FENCE)).isTrue();
        verify(graph).insertText("first\n\nsecond", "collection1_doc-1");
    }

    @Test
    @DisplayName("LightRAG 写入失败：best-effort，发布仍成功")
    void graphFailureDoesNotBlockPublish() {
        stubPublishPath(List.of(OLD_VERSION));
        doThrow(new IllegalStateException("lightrag unavailable"))
                .when(graph).deleteByDoc(DOC);
        doThrow(new IllegalStateException("lightrag unavailable"))
                .when(graph).insertText(any(), any());
        DocumentDao dao = new DocumentDao(jdbc, es, graph);

        assertThat(dao.publishVersionFenced(TENANT, DOC, VERSION, RUN, FENCE)).isTrue();
        verify(es).indexPublishedChunks(eq(TENANT), eq("collection1"), eq("kb-1"), eq(DOC),
                eq(VERSION), any());
    }

    @Test
    @DisplayName("tombstone：按 docId 清理 ES 与图谱")
    void tombstoneCleansBothOptionalBackends() {
        stubTombstonePath();
        DocumentDao dao = new DocumentDao(jdbc, es, graph);

        assertThat(dao.tombstoneDocument(TENANT, DOC)).isEqualTo(1);

        verify(es).deleteDocumentIndex(TENANT, "collection1", DOC);
        verify(graph).deleteByDoc(DOC);
    }

    @Test
    @DisplayName("tombstone 在可选后端缺席时不解析 collection、不外呼")
    void tombstoneWithoutBackendsKeepsTheDefaultPath() {
        when(jdbc.update(contains("UPDATE ai_resource SET status='TOMBSTONED'"), eq(TENANT), eq(DOC)))
                .thenReturn(1);
        when(jdbc.update(contains("UPDATE ai_document SET tombstoned_at=now()"), eq(TENANT), eq(DOC)))
                .thenReturn(1);
        DocumentDao dao = new DocumentDao(jdbc);

        assertThat(dao.tombstoneDocument(TENANT, DOC)).isEqualTo(1);

        verify(jdbc, never()).queryForObject(contains("FROM ai_document d"), any(RowMapper.class),
                any(), any());
    }

    // ------------------------------------------------------------------ stubs

    private void stubPublishPath(List<String> currentVersions) {
        doReturn(currentVersions).when(jdbc).query(
                contains("SELECT published_version_id FROM ai_document"), any(RowMapper.class),
                eq(TENANT), eq(DOC));
        doReturn(Boolean.TRUE).when(jdbc).queryForObject(
                contains("newer.version_order>older.version_order"), eq(Boolean.class),
                any(), any(), any());
        // 所有 UPDATE 都成功：按完整 varargs 数组匹配，避免不同参数个数的 SQL 互相遮蔽
        doReturn(1).when(jdbc).update(anyString(), any(Object[].class));
        doAnswer(invocation -> mirrorChunkRows(invocation.getArgument(1)))
                .when(jdbc).query(contains("FROM ai_document_chunk c"), any(RowMapper.class),
                        eq(TENANT), eq(VERSION));
    }

    private void stubTombstonePath() {
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return mapper.mapRow(row("kb-1", "collection1"), 0);
        }).when(jdbc).queryForObject(contains("FROM ai_document d"), any(RowMapper.class),
                eq(TENANT), eq(DOC));
        doReturn(1).when(jdbc).update(anyString(), any(Object[].class));
    }

    private static List<?> mirrorChunkRows(RowMapper<?> mapper) throws Exception {
        return List.of(
                mapper.mapRow(chunkRow("c1", 0, "first"), 0),
                mapper.mapRow(chunkRow("c2", 1, "second"), 1));
    }

    private static ResultSet chunkRow(String chunkKey, int chunkIndex, String content) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("chunk_key")).thenReturn(chunkKey);
        when(row.getInt("chunk_index")).thenReturn(chunkIndex);
        when(row.getString("content")).thenReturn(content);
        when(row.getString("kb_id")).thenReturn("kb-1");
        when(row.getString("collection_name")).thenReturn("collection1");
        return row;
    }

    private static ResultSet row(String kbId, String collectionName) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("kb_id")).thenReturn(kbId);
        when(row.getString("collection_name")).thenReturn(collectionName);
        return row;
    }
}
