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

import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreAdmin implements VectorStoreAdmin {

    // Validate the index on the table this connection actually resolves, not a name
    // anywhere in the database. Only migrations own DDL; application accounts use DML.
    private static final String VECTOR_INDEX_QUERY = """
            SELECT EXISTS (
              SELECT 1 FROM pg_index i
              JOIN pg_class t ON t.oid = i.indrelid
              JOIN pg_namespace ns ON ns.oid = t.relnamespace
              JOIN pg_class idx ON idx.oid = i.indexrelid
              JOIN pg_am am ON am.oid = idx.relam
              JOIN pg_opclass opc ON opc.oid = i.indclass[0]
              JOIN pg_namespace opns ON opns.oid = opc.opcnamespace
              JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = i.indkey[0]
              WHERE t.oid = to_regclass('t_knowledge_vector') AND ns.nspname = 'ai'
                AND am.amname = 'hnsw' AND opc.opcname = 'vector_cosine_ops'
                AND opns.nspname = 'extensions' AND a.attname = 'embedding'
                AND a.atttypmod = ? AND i.indnkeyatts = 1
                AND i.indisvalid AND i.indisready
                AND i.indpred IS NULL AND i.indexprs IS NULL
            )
            """;
    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        if (!hasVectorIndex()) {
            throw new ServiceException("AI 域缺少有效的 HNSW cosine 向量索引，请先执行 AI 数据库迁移；运行账号不执行 DDL");
        }
        log.debug("AI 域迁移预建的 HNSW 向量索引已就绪");
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            return hasVectorIndex();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasVectorIndex() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                VECTOR_INDEX_QUERY, Boolean.class, ragDefaultProperties.getDimension()));
    }

    @Override
    public void dropVectorSpace(String collectionName) {
        // PG 为共享表：仅删除该 collection 的残留向量行，不动共享 HNSW 索引
        // 常规情况下文档删除已逐一清理，此处多为 0 行的兜底
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE collection_name = ?", collectionName);
        log.info("已删除 collection={} 的残留向量行，count={}", collectionName, deleted);
    }
}
