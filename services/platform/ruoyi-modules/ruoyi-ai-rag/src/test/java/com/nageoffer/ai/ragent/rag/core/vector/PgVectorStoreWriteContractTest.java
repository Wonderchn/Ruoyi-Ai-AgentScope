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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-VEC-1：{@code ai_knowledge_vector} 写入的**四列归属契约**护栏。
 *
 * <p>真机实锤（v4-R2）：部署表 {@code ai_knowledge_vector} 的 {@code document_id}/{@code doc_version}
 * 两列 {@code NOT NULL} 且无默认值，而 {@code PgVectorStoreService} 的写语句只带历史列集
 * （id/tenant_id/collection_name/content/metadata/embedding）⇒
 * {@code null value in column "document_id" ... violates not-null constraint} ⇒ 503。
 *
 * <p>V7 冻结契约把向量行的归属定义为四列合取（{@code V7__unified_ai_domain.sql}：
 * {@code tenant_id/deleted/document_id/doc_version}，任一无值即拒绝）。因此这里从**真实迁移文件**
 * 与**真实绑定参数**两侧钉死：
 * <ul>
 *   <li>写语句必须携带 {@code document_id} 与 {@code doc_version} 两列；</li>
 *   <li>{@code document_id} 的值必须是调用实参 docId，{@code doc_version} 的值必须来自在树权威
 *       {@code ai_knowledge_document.version}（F05:2 版本面），且读取带租户条件；</li>
 *   <li>文档级删除改用 {@code document_id} 列定位归属，租户条件不得放宽。</li>
 * </ul>
 */
@Tag("dev")
class PgVectorStoreWriteContractTest {

    private static final String TENANT = "000000";
    private static final String COLLECTION = "kb_collection_contract";
    private static final String DOC_ID = "doc-contract-1";
    private static final String CHUNK_ID = "chunk-contract-1";
    private static final Long DOC_VERSION = 7L;

    private static final String V7_MIGRATION =
            "services/platform/docs/script/sql/postgres/V7__unified_ai_domain.sql";

    @Test
    @DisplayName("V7 冻结迁移把 document_id / doc_version 定为 NOT NULL：写语句漏列即真机 503")
    void migrationDeclaresDocumentIdAndDocVersionNotNull() throws IOException {
        String sql = migrationSql();
        assertThat(sql)
                .as("V7:1848 四列契约的 document_id 列")
                .contains("ADD COLUMN document_id VARCHAR(20)");
        assertThat(sql)
                .as("V7:1848 四列契约的 doc_version 列")
                .contains("ADD COLUMN doc_version INTEGER");
        assertThat(sql)
                .as("V7:1991 document_id 必须 NOT NULL，写语句漏列会直接被数据库拒绝")
                .contains("ALTER COLUMN document_id SET NOT NULL");
        assertThat(sql)
                .as("V7:1992 doc_version 必须 NOT NULL，写语句漏列会直接被数据库拒绝")
                .contains("ALTER COLUMN doc_version SET NOT NULL");
    }

    @Test
    @DisplayName("indexDocumentChunks：INSERT 携 document_id＋doc_version，值取自 docId 与文档版本")
    @SuppressWarnings("unchecked")
    void indexDocumentChunksCarriesTheDocumentOwnershipColumns() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenReturn(DOC_VERSION);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());
        EmbeddedChunk chunk = chunk(CHUNK_ID);

        service.indexDocumentChunks(TENANT, COLLECTION, DOC_ID, List.of(chunk));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ParameterizedPreparedStatementSetter<EmbeddedChunk>> setterCaptor =
                ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), anyList(), anyInt(), setterCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .as("INSERT 列清单必须含 document_id 与 doc_version，否则真机 NOT NULL 违约：%s", sql)
                .contains("document_id")
                .contains("doc_version");

        PreparedStatement ps = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(ps, chunk);
        verify(ps).setString(4, DOC_ID);
        verify(ps).setLong(5, DOC_VERSION);
    }

    @Test
    @DisplayName("updateChunk：UPSERT 携 document_id＋doc_version，并在冲突更新时一起刷新")
    @SuppressWarnings("unchecked")
    void updateChunkUpsertCarriesTheDocumentOwnershipColumns() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenReturn(DOC_VERSION);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());

        service.updateChunk(TENANT, COLLECTION, DOC_ID, chunk(CHUNK_ID));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .as("UPSERT 列清单必须含 document_id 与 doc_version：%s", sql)
                .contains("document_id")
                .contains("doc_version");
        assertThat(sql)
                .as("冲突更新也必须刷新归属列，不能停在插入时的旧值：%s", sql)
                .contains("document_id = EXCLUDED.document_id")
                .contains("doc_version = EXCLUDED.doc_version");
        assertThat(sql)
                .as("冲突目标仍是复合键 (tenant_id, id)，不得退化为 id：%s", sql)
                .contains("ON CONFLICT (tenant_id, id)");

        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).as("第一个绑定参数是 chunkId").isEqualTo(CHUNK_ID);
        assertThat(args[1]).as("第二个绑定参数是租户").isEqualTo(TENANT);
        assertThat(args[3]).as("document_id 绑定 docId 实参").isEqualTo(DOC_ID);
        assertThat(args[4]).as("doc_version 绑定 ai_knowledge_document.version").isEqualTo(DOC_VERSION);
    }

    @Test
    @DisplayName("doc_version 值源：带租户条件从 ai_knowledge_document.version 读取，不猜值")
    void docVersionIsReadFromTheKnowledgeDocumentWithTenantScope() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenReturn(DOC_VERSION);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());

        service.updateChunk(TENANT, COLLECTION, DOC_ID, chunk(CHUNK_ID));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), any(), any());
        String sql = sqlCaptor.getValue().toLowerCase();
        assertThat(sql)
                .as("版本值必须取自知识文档版本面：%s", sql)
                .contains("from ai_knowledge_document");
        assertThat(sql)
                .as("读版本必须带租户条件，跨租户读等同越权：%s", sql)
                .contains("tenant_id");
    }

    @Test
    @DisplayName("文档级删除改用 document_id 列定位归属，租户条件保留在最前")
    void deleteDocumentVectorsUsesDocumentIdColumnAndStaysTenantScoped() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        PgVectorStoreService service = new PgVectorStoreService(jdbcTemplate, new ObjectMapper());

        service.deleteDocumentVectors(TENANT, COLLECTION, DOC_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .as("按四列契约用 document_id 列定位归属：%s", sql)
                .contains("document_id = ?");
        assertThat(sql)
                .as("租户条件必须保留，不得因换列而放宽：%s", sql)
                .contains("tenant_id = ?");
        assertThat(argsCaptor.getValue()[0]).as("租户是第一个绑定参数").isEqualTo(TENANT);
    }

    private static EmbeddedChunk chunk(String chunkId) {
        return new EmbeddedChunk(new Chunk(chunkId, 0, "content", "embedding text", ChunkMetadata.empty()),
                new float[] {1.0F, 0.0F});
    }

    private static String migrationSql() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(V7_MIGRATION);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new AssertionError("找不到 " + V7_MIGRATION + "（从 " + Path.of("").toAbsolutePath()
                + " 逐级向上 8 层）—— 该护栏不允许静默消失");
    }
}
