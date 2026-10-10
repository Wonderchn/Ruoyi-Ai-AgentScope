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

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * S2-F05-A2（第二层）：向量索引就绪检查的真库判据——真机 create-KB 503 的第二个根因。
 *
 * <p>事实：冻结迁移 {@code V7__unified_ai_domain.sql:653-663} 把 {@code ai_knowledge_vector}
 * 建在 <b>platform</b> schema；检查 SQL 的 {@code t.oid = to_regclass('ai_knowledge_vector')}
 * 本就按连接的 search_path 解析到该表（类注释的原意即"校验连接实际解析到的表，而不是库里
 * 任意一个同名"），但 WHERE 里多写了一条 {@code AND ns.nspname = 'ai'} 断言 ⇒ 真机全条件
 * （hnsw / vector_cosine_ops / opns='extensions' / embedding / atttypmod=1536 / valid / ready）
 * 只差 ns 这一条 ⇒ EXISTS 恒 false ⇒ {@code ensureVectorSpace} 恒抛 503 ⇒ create KB 永久 503。
 *
 * <p>判据（真库、真实现、真 catalog；非 mock）：
 * <ul>
 *   <li>按冻结 DDL 自建 {@code platform.ai_knowledge_vector} + hnsw cosine 索引后，
 *       {@code ensureVectorSpace} 必须放行、{@code vectorSpaceExists}=true；</li>
 *   <li>负例：同表去掉 hnsw 索引后必须拒绝（证明判定不是"恒真"），并在 finally 恢复索引。</li>
 * </ul>
 *
 * <p>需要 {@code -Dragent.vector.test.jdbc-url=jdbc:postgresql://host:port/db}
 * （库须含 pgvector@extensions；URL 不必带 currentSchema，本测试自设 search_path=platform,extensions）；
 * 凭据可用 {@code -Dragent.vector.test.jdbc-user} / {@code -Dragent.vector.test.jdbc-password}
 * 覆盖（缺省 postgres/空）。未提供 URL 时显式跳过并给出原因。
 * <b>本测试会 DROP/重建 platform.ai_knowledge_vector ⇒ 必须连独占隔离库，不得指向共享主库。</b>
 */
@Tag("dev")
@EnabledIfSystemProperty(named = PgVectorStoreAdminPostgresTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL（含 pgvector 的 extensions schema）：-D"
                + PgVectorStoreAdminPostgresTest.URL_PROPERTY + "=jdbc:postgresql://host:port/db")
class PgVectorStoreAdminPostgresTest {

    static final String URL_PROPERTY = "ragent.vector.test.jdbc-url";
    static final String USER_PROPERTY = "ragent.vector.test.jdbc-user";
    static final String PASSWORD_PROPERTY = "ragent.vector.test.jdbc-password";

    /** 与冻结形状 vector(1536) 及运行维度一致（检查项 atttypmod=1536 的前提）。 */
    private static final int DIMENSION = 1536;

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        String url = System.getProperty(URL_PROPERTY);
        // @EnabledIfSystemProperty 已保证 url 存在；这里再断言一次，防止注解被误改后静默跑空。
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(System.getProperty(USER_PROPERTY, "postgres"));
        dataSource.setPassword(System.getProperty(PASSWORD_PROPERTY, ""));
        // 与运行实例同口径：未限定表名（to_regclass('ai_knowledge_vector')）经 search_path 解析到
        // platform 的表；vector 类型在 extensions schema。
        dataSource.setCurrentSchema("platform,extensions");
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS platform");
        jdbc.execute("DROP TABLE IF EXISTS platform.ai_knowledge_vector CASCADE");
        for (String ddl : frozenVectorDdl()) {
            jdbc.execute(ddl);
        }

        // 环境锚点 1：未限定名确实解析到 platform 的表（否则本判据测的不是真机事实）。
        assertThat(jdbc.queryForObject(
                "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE c.oid = to_regclass('ai_knowledge_vector')", String.class))
                .as("V7:653 把 ai_knowledge_vector 建在 platform；连接的 search_path 必须解析到它")
                .isEqualTo("platform");
        // 环境锚点 2：embedding 列型必须是 vector(1536)（检查项 atttypmod=1536 的前提）。
        assertThat(jdbc.queryForObject(
                "SELECT a.atttypmod FROM pg_attribute a"
                        + " WHERE a.attrelid = 'platform.ai_knowledge_vector'::regclass"
                        + " AND a.attname = 'embedding' AND a.attnum > 0", Integer.class))
                .as("vector(1536) 的 atttypmod 必须是 1536")
                .isEqualTo(DIMENSION);
    }

    /** 冻结迁移 V7__unified_ai_domain.sql:653-663 逐字（表 + 三条索引）。 */
    private static List<String> frozenVectorDdl() {
        return List.of(
                "CREATE TABLE platform.ai_knowledge_vector ("
                        + "id VARCHAR(20) PRIMARY KEY, collection_name VARCHAR(64) NOT NULL,"
                        + " content TEXT, metadata JSONB, embedding vector(1536))",
                "CREATE INDEX idx_kv_collection_name ON platform.ai_knowledge_vector (collection_name)",
                "CREATE INDEX idx_kv_metadata ON platform.ai_knowledge_vector USING gin(metadata)",
                "CREATE INDEX idx_kv_embedding ON platform.ai_knowledge_vector"
                        + " USING hnsw (embedding vector_cosine_ops)");
    }

    private static PgVectorStoreAdmin admin() {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setDimension(DIMENSION);
        return new PgVectorStoreAdmin(jdbc, properties);
    }

    @Test
    @DisplayName("迁移预建的 hnsw 索引被真实现识别：ensureVectorSpace 放行、vectorSpaceExists=true"
            + "（修复前红：ns='ai' 断言与 V7:653 的 platform schema 矛盾 ⇒ EXISTS 恒 false）")
    void migrationBuiltIndexOnTheResolvedTableIsRecognized() {
        PgVectorStoreAdmin admin = admin();
        assertThat(admin.vectorSpaceExists(new VectorSpaceId()))
                .as("真 catalog 上 hnsw cosine 索引就绪 ⇒ 必须为 true")
                .isTrue();
        assertThatCode(() -> admin.ensureVectorSpace(new VectorSpaceSpec()))
                .as("就绪时不得抛 503（修复前此处抛 ServiceException）")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("负例：同表去掉 hnsw 索引后必须拒绝（判定不是恒真），finally 恢复索引")
    void withoutTheHnswIndexTheCheckRefuses() {
        assertThat(admin().vectorSpaceExists(new VectorSpaceId()))
                .as("前置：此刻索引应就绪——保证后面的 false 源于 drop 而不是判定恒 false")
                .isTrue();
        jdbc.execute("DROP INDEX platform.idx_kv_embedding");
        try {
            assertThat(admin().vectorSpaceExists(new VectorSpaceId()))
                    .as("索引被去掉后必须为 false")
                    .isFalse();
            assertThatThrownBy(() -> admin().ensureVectorSpace(new VectorSpaceSpec()))
                    .as("索引被去掉后 ensureVectorSpace 必须以迁移行动项拒绝")
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("迁移");
        } finally {
            jdbc.execute("CREATE INDEX idx_kv_embedding ON platform.ai_knowledge_vector"
                    + " USING hnsw (embedding vector_cosine_ops)");
        }
    }
}
