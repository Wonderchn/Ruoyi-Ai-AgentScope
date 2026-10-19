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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2/R10：投影 SQL 的租户谓词与授权范围静态护栏。
 *
 * <p>没有真库时，能钉住的是"每条图投影 SQL 都带 tenant 谓词、读侧都带 deleted=0、
 * 范围只按 kb/doc 谓词而不是 collection 子串"。
 */
@Tag("dev")
class GraphProjectionRepositorySqlTest {

    private static Map<String, String> sqls() {
        Map<String, String> sqls = new LinkedHashMap<>();
        sqls.put("FIND_KB", JdbcGraphProjectionRepository.SQL_FIND_KB);
        sqls.put("FIND_KB_BY_COLLECTION", JdbcGraphProjectionRepository.SQL_FIND_KB_BY_COLLECTION);
        sqls.put("FIND_DOCUMENT", JdbcGraphProjectionRepository.SQL_FIND_DOCUMENT);
        sqls.put("FIND_REBUILD_DOCUMENTS", JdbcGraphProjectionRepository.SQL_FIND_REBUILD_DOCUMENTS);
        sqls.put("LOAD_TEXT", JdbcGraphProjectionRepository.SQL_LOAD_TEXT);
        sqls.put("FIND_ENTITIES", JdbcGraphProjectionRepository.SQL_FIND_ENTITIES);
        sqls.put("FIND_RELATIONS", JdbcGraphProjectionRepository.SQL_FIND_RELATIONS);
        sqls.put("FIND_LABELS", JdbcGraphProjectionRepository.SQL_FIND_LABELS);
        sqls.put("COUNT_UNATTRIBUTED_ENTITIES", JdbcGraphProjectionRepository.SQL_COUNT_UNATTRIBUTED_ENTITIES);
        sqls.put("COUNT_UNATTRIBUTED_RELATIONS", JdbcGraphProjectionRepository.SQL_COUNT_UNATTRIBUTED_RELATIONS);
        sqls.put("STATS_ENTITIES", JdbcGraphProjectionRepository.SQL_STATS_ENTITIES);
        sqls.put("STATS_RELATIONS", JdbcGraphProjectionRepository.SQL_STATS_RELATIONS);
        sqls.put("STATS_DOCS", JdbcGraphProjectionRepository.SQL_STATS_DOCS);
        sqls.put("STATS_LAST_SYNCED", JdbcGraphProjectionRepository.SQL_STATS_LAST_SYNCED);
        sqls.put("DELETE_ENTITY", JdbcGraphProjectionRepository.SQL_DELETE_ENTITY);
        sqls.put("DELETE_RELATION", JdbcGraphProjectionRepository.SQL_DELETE_RELATION);
        sqls.put("DELETE_KB_ENTITY", JdbcGraphProjectionRepository.SQL_DELETE_KB_ENTITY);
        sqls.put("DELETE_KB_RELATION", JdbcGraphProjectionRepository.SQL_DELETE_KB_RELATION);
        sqls.put("INSERT_ENTITY", JdbcGraphProjectionRepository.SQL_INSERT_ENTITY);
        sqls.put("INSERT_RELATION", JdbcGraphProjectionRepository.SQL_INSERT_RELATION);
        return sqls;
    }

    @Test
    @DisplayName("每条投影/主库 SQL 都带 :tenant 与 tenant_id 谓词")
    void everyStatementIsTenantScoped() {
        sqls().forEach((name, sql) -> {
            assertTrue(sql.contains(":tenant"), name + " 缺少 :tenant 参数");
            assertTrue(sql.contains("tenant_id"), name + " 缺少 tenant_id 谓词");
            assertFalse(sql.toLowerCase().contains("where 1=1"), name + " 不得用恒真谓词绕过租户");
        });
    }

    @Test
    @DisplayName("投影读侧只按 kb/doc 范围过滤，绝不用 collection 子串判归属")
    void projectionReadsUseKbAndDocPredicates() {
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_ENTITIES.contains("kb_id IN (:kbs)"));
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_RELATIONS.contains("kb_id IN (:kbs)"));
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_ENTITIES.contains("e.doc_id = :docId")
                || JdbcGraphProjectionRepository.SQL_FIND_ENTITIES.contains(":docId IS NULL"));
        assertFalse(JdbcGraphProjectionRepository.SQL_FIND_ENTITIES.contains("LIKE"),
                "归属判定不得再用 collection + '_' 子串");
        assertFalse(JdbcGraphProjectionRepository.SQL_FIND_RELATIONS.contains("LIKE"));
    }

    @Test
    @DisplayName("读侧 SQL 带 deleted=0；写入/删除按 tenant+doc/kb replace")
    void softDeleteAndReplacePredicates() {
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_ENTITIES.contains("e.deleted = 0"));
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_RELATIONS.contains("r.deleted = 0"));
        assertTrue(JdbcGraphProjectionRepository.SQL_DELETE_ENTITY.contains("doc_id = :docId"));
        assertTrue(JdbcGraphProjectionRepository.SQL_DELETE_RELATION.contains("doc_id = :docId"));
        assertTrue(JdbcGraphProjectionRepository.SQL_DELETE_KB_ENTITY.contains("kb_id = :kbId"));
        assertTrue(JdbcGraphProjectionRepository.SQL_DELETE_KB_RELATION.contains("kb_id = :kbId"));
    }

    @Test
    @DisplayName("主库 join 保证软删文档/知识库不返回；collection 同名由 tenant 谓词隔离")
    void joinsExcludeDeletedCatalogRows() {
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_ENTITIES.contains("d.deleted = 0"));
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_ENTITIES.contains("k.deleted = 0"));
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_KB_BY_COLLECTION.contains("collection_name = :collectionName"));
        assertTrue(JdbcGraphProjectionRepository.SQL_FIND_KB_BY_COLLECTION.contains("tenant_id = :tenant"));
    }
}
