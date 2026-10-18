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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R12：V42 迁移的静态护栏（无真库，执行证据记 NOT_RUN）。
 *
 * <p>钉住"可重建派生、非权威"的形态：幂等建表、租户非空、关系标签 NOT NULL、
 * 唯一键、以及**绝不**新增权限/菜单行。
 */
@Tag("dev")
class GraphProjectionMigrationTest {

    private static final Path MIGRATION = Path.of("..", "..", "docs", "script", "sql", "postgres",
            "V42__knowledge_graph_projection.sql").toAbsolutePath().normalize();

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "缺少 V42 迁移文件: " + MIGRATION);
        return Files.readString(MIGRATION);
    }

    @Test
    @DisplayName("两表幂等建表、租户/外键/唯一键与 NOT NULL 标签")
    void tablesAreIdempotentAndTenantScoped() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS platform.ai_graph_entity"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS platform.ai_graph_relation"));
        assertTrue(sql.contains("CONSTRAINT uk_ai_graph_entity_doc_node UNIQUE (tenant_id, doc_id, node_id)"));
        assertTrue(sql.contains("CONSTRAINT uk_ai_graph_relation_doc_edge UNIQUE "
                + "(tenant_id, doc_id, source_node_id, target_node_id, relation_label)"));
        assertTrue(sql.contains("ck_ai_graph_entity_tenant"));
        assertTrue(sql.contains("ck_ai_graph_relation_tenant"));
        assertTrue(sql.matches("(?s).*relation_label\\s+VARCHAR\\(256\\)\\s+NOT NULL.*"),
                "relation_label 必须 NOT NULL（PG 唯一键 NULLS DISTINCT 会让重复边漏网）");
        assertTrue(sql.matches("(?s).*tenant_id\\s+VARCHAR\\(64\\)\\s+NOT NULL.*"));
        assertTrue(sql.matches("(?s).*id\\s+VARCHAR\\(20\\)\\s+NOT NULL PRIMARY KEY.*"));
    }

    @Test
    @DisplayName("表注释声明可重建派生、非权威；不新增权限/菜单行")
    void derivedNonAuthoritativeAndZeroPermissionRows() throws IOException {
        String sql = sql();
        String lower = sql.toLowerCase();
        assertTrue(sql.contains("可重建派生"));
        assertTrue(sql.contains("非权威"));
        assertFalse(lower.contains("sys_menu"), "F08 复用既有动作，不得新增菜单/权限行");
        assertFalse(lower.contains("sys_role_menu"));
        assertFalse(lower.contains("insert into"), "迁移只建表/索引/注释，不播种任何行");
    }
}
