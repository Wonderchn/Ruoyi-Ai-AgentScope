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

package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.sink.RelationalChunkSink;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorRetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP-032：知识/向量链（摄取 → 索引 → 检索）内嵌装配的判据。
 *
 * <p>这一组盯的是"检索/索引路径没有后端"这一类成因：
 * <ol>
 *   <li>PG 向量三件套与非合并服务都在 {@code com.nageoffer.ai.ragent.*} 下，
 *       platform 扫描根是 {@code org.ruoyi} → 它们从来不是 bean
 *       （{@link #knowledgeChainIsAssembledWhenPgVectorIsSelected()} 钉真装配，
 *       {@link #knowledgeChainIsAbsentForOtherVectorBackends()} 钉门控）；</li>
 *   <li>{@code PgVector*} 自身带 {@code rag.vector.type=pg} 条件，本组必须用同一个判据，
 *       否则内嵌态与独立态会装配不同的后端
 *       （{@link #assemblySharesTheSameVectorBackendConditionAsTheImplementations()}）；</li>
 *   <li>向量落点的维度是 fail-closed 的，缺配置不得猜一个值
 *       （{@link #vectorDimensionIsFailClosedNotGuessed()}）。</li>
 * </ol>
 *
 * <p>另有两条行为判据来自既有实现的契约，本包只把它们在内嵌装配下<em>复现</em>：
 * {@link #vectorWriteFailsLoudlyWhenTheTenantSchemaIsMissing()} 证明"迁移未部署时响亮失败、
 * 绝不回落到无租户旧语句"；{@link #vectorWriteRejectsAMissingTenant()}
 * 证明"没有租户就不写"。
 */
@Tag("dev")
class AiEmbeddedKnowledgeConfigurationTest {

    private static final String[] EMBEDDED_LOCAL = {
            "ai.integration.enabled=true", "ai.integration.transport=local"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedKnowledgeConfiguration.class, Fixture.class);

    // ------------------------------------------------------------------ 装配

    @Test
    @DisplayName("rag.vector.type=pg 时知识/向量链完整装配（索引、检索、形状校验、嵌入、chunk 落库）")
    void knowledgeChainIsAssembledWhenPgVectorIsSelected() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.vector.type=pg")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorStoreService.class);
                    assertThat(context.getBean(VectorStoreService.class)).isInstanceOf(PgVectorStoreService.class);
                    assertThat(context).hasSingleBean(VectorRetrieverService.class);
                    assertThat(context.getBean(VectorRetrieverService.class))
                            .isInstanceOf(PgVectorRetrieverService.class);
                    assertThat(context).hasSingleBean(VectorStoreAdmin.class);
                    assertThat(context.getBean(VectorStoreAdmin.class)).isInstanceOf(PgVectorStoreAdmin.class);
                    assertThat(context).hasSingleBean(ChunkEmbeddingService.class);
                    assertThat(context).hasSingleBean(RelationalChunkSink.class);
                    assertThat(context).hasSingleBean(TokenCounterService.class);
                    assertThat(context).hasSingleBean(RAGDefaultProperties.class);
                });
    }

    @Test
    @DisplayName("非 pg 向量后端与未开启集成时不装配知识链（负例：不得靠扫描或默认值生效）")
    void knowledgeChainIsAbsentForOtherVectorBackends() {
        // 显式 milvus：本组不装配（Milvus 属可选技术后端 WP-053）
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.vector.type=milvus")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(VectorStoreService.class);
                    assertThat(context).doesNotHaveBean(VectorRetrieverService.class);
                    assertThat(context).doesNotHaveBean(ChunkEmbeddingService.class);
                    assertThat(context).doesNotHaveBean(RelationalChunkSink.class);
                });
        // 完全没有 rag.vector.type：`havingValue` 无 matchIfMissing 时条件不成立 → 同样不装配。
        // 这正是为什么 application-embedded.yml 必须显式写出 rag.vector.type=pg。
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(VectorStoreService.class);
                });
        // 传输不是 local：缺席
        runner.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http",
                        "rag.vector.type=pg")
                .run(context -> assertThat(context).doesNotHaveBean(VectorStoreService.class));
        // 集成总开关关闭：缺席
        runner.withPropertyValues("ai.integration.transport=local", "rag.vector.type=pg")
                .run(context -> assertThat(context).doesNotHaveBean(VectorStoreService.class));
    }

    @Test
    @DisplayName("本组与 PgVector* 实现类使用同一个后端判据（避免内嵌/独立装配分叉）")
    void assemblySharesTheSameVectorBackendConditionAsTheImplementations() {
        for (Class<?> type : List.of(PgVectorStoreService.class, PgVectorRetrieverService.class,
                PgVectorStoreAdmin.class)) {
            var condition = type.getAnnotation(
                    org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
            assertThat(condition)
                    .as("%s 必须带 rag.vector.type=pg 条件；本组沿用同一判据", type.getSimpleName())
                    .isNotNull();
            assertThat(condition.name()).containsExactly("rag.vector.type");
            assertThat(condition.havingValue()).isEqualTo("pg");
        }
    }

    @Test
    @DisplayName("真实内嵌 profile 显式声明 rag.vector.type=pg 与 rag.default.dimension")
    void embeddedProfileDeclaresTheVectorBackendAndDimension() throws IOException {
        Path yml = Path.of("..", "..", "ruoyi-admin", "src", "main", "resources",
                "application-embedded.yml").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(yml),
                "内嵌 profile 不在预期位置: " + yml);
        String yaml = Files.readString(yml);
        // 条件注解没有 matchIfMissing，"缺配置就不装配"——所以必须显式写出
        assertThat(Pattern.compile("(?m)^\\s{2}vector:\\s*$\\n\\s{4}type:\\s*pg\\s*$").matcher(yaml).find())
                .as("rag.vector.type 必须显式为 pg：条件注解无 matchIfMissing，缺失即不装配任何向量 bean")
                .isTrue();
        assertThat(Pattern.compile("(?m)^\\s{4}dimension:\\s*\\$\\{RAG_EMBEDDING_DIMENSION:1536}")
                .matcher(yaml).find())
                .as("rag.default.dimension 必须显式声明并与冻结迁移的物理维度一致")
                .isTrue();
    }

    // ------------------------------------------------------------------ 行为（fail-closed）

    @Test
    @DisplayName("向量维度 fail-closed：缺维度拒绝、租户缺一不可，绝不猜一个值")
    void vectorDimensionIsFailClosedNotGuessed() {
        RAGDefaultProperties properties = new RAGDefaultProperties();
        VectorTargetResolver resolver = new VectorTargetResolver(properties);
        ExecutionPrincipal principal = new ExecutionPrincipal("000000", "1",
                ExecutionPrincipal.canonicalMembershipId("000000", "1"), 1, 1, Set.of(),
                "jti-1", "platform:local", 0L, 0L);
        KnowledgeBaseDO kb = new KnowledgeBaseDO();
        kb.setId("kb-1");
        kb.setEmbeddingModel("qwen-emb-8b");
        kb.setCollectionName("kb_collection_1");

        // 缺维度：拒绝（不是回落到 1536 之类的默认值）
        properties.setDimension(null);
        assertThatThrownBy(() -> resolver.resolve(principal, kb))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("rag.default.dimension");

        // 非正维度：同样拒绝
        properties.setDimension(0);
        assertThatThrownBy(() -> resolver.resolve(principal, kb))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("rag.default.dimension");

        // 无执行主体：不得默认某个租户
        properties.setDimension(1536);
        assertThatThrownBy(() -> resolver.resolve((ExecutionPrincipal) null, kb))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("缺少执行主体");

        // 知识库缺嵌入模型：拒绝
        kb.setEmbeddingModel("  ");
        assertThatThrownBy(() -> resolver.resolve(principal, kb))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("未配置嵌入模型");

        // 正例：维度与租户都就位时落点带租户
        kb.setEmbeddingModel("qwen-emb-8b");
        VectorTarget target = resolver.resolve(principal, kb);
        assertThat(target.dimension()).isEqualTo(1536);
        assertThat(target.tenantId()).isEqualTo("000000");
    }

    @Test
    @DisplayName("向量写入在租户 schema 缺失时响亮失败，不回落到无租户旧语句")
    void vectorWriteFailsLoudlyWhenTheTenantSchemaIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BadSqlGrammarException missingSchema = new BadSqlGrammarException(
                "indexDocumentChunks", "INSERT INTO ai_knowledge_vector ...",
                new SQLException("column \"tenant_id\" does not exist", "42703"));
        when(jdbc.batchUpdate(anyString(), anyList(), any(Integer.class), any())).thenThrow(missingSchema);

        PgVectorStoreService service = new PgVectorStoreService(jdbc, new ObjectMapper());
        List<EmbeddedChunk> chunks = List.of(chunk("chunk-1", "内容"));

        assertThatThrownBy(() -> service.indexDocumentChunks("000000", "kb_collection_1", "doc-1", chunks))
                .as("迁移未部署时唯一正确的行为是响亮失败，绝不降级到不带 tenant_id 的语句")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id")
                .hasMessageContaining("V3");
    }

    @Test
    @DisplayName("没有租户就不写向量（跨租户写入的前置拒绝）")
    void vectorWriteRejectsAMissingTenant() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PgVectorStoreService service = new PgVectorStoreService(jdbc, new ObjectMapper());
        List<EmbeddedChunk> chunks = List.of(chunk("chunk-1", "内容"));

        // requireTenant 的拒绝语义是 RuntimeException（不是 IllegalArgumentException）——
        // 断言按实际契约写，不为凑类型而改产品代码。
        assertThatThrownBy(() -> service.indexDocumentChunks(null, "kb_collection_1", "doc-1", chunks))
                .as("空租户必须在触达任何 SQL 之前被拒绝")
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.indexDocumentChunks("   ", "kb_collection_1", "doc-1", chunks))
                .as("纯空白租户同样必须被拒绝（不能让 '   ' 变成一个租户名）")
                .isInstanceOf(RuntimeException.class);
        // 反向锚点：证明上面的断言不是在"任何异常都算过"——有租户时不会在签名检查处抛错，
        // 而是走到 SQL（batchUpdate 返回每个 chunk 的更新行数）后正常返回。
        org.mockito.Mockito.when(jdbc.batchUpdate(anyString(), anyList(), any(Integer.class), any()))
                .thenReturn(new int[][] {{1}});
        service.indexDocumentChunks("000000", "kb_collection_1", "doc-1", chunks);
    }

    private static EmbeddedChunk chunk(String chunkId, String content) {
        return new EmbeddedChunk(new Chunk(chunkId, 0, content, content, ChunkMetadata.empty()),
                new float[] {0.1f, 0.2f});
    }

    /** 只为满足构造依赖；行为断言不依赖这些替身。 */
    @Configuration(proxyBeanMethods = false)
    static class Fixture {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        EmbeddingService embeddingService() {
            return mock(EmbeddingService.class);
        }

        @Bean
        KnowledgeChunkMapper knowledgeChunkMapper() {
            return mock(KnowledgeChunkMapper.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
