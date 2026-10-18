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
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F22:2：ES 共享索引启动初始化的两条边界。
 *
 * <p>P1.2a 曾让 {@link EsKeywordIndexService#initSharedIndex()}（{@code @PostConstruct}）
 * 在旧能力关闭时直接抛 {@code ClosedCapabilityException}。后果是：一旦
 * {@code rag.keyword.type=es} 打开，应用会因为"关闭旧能力"而起不来——关闭的语义是
 * <b>不发生远端调用</b>，不是<b>拒绝启动</b>（与 {@code StorageInitializer} 的启动包装同口径）。
 *
 * <p>本测试钉住四件事：
 * <ol>
 *   <li>边界缺席/关闭时，{@code @PostConstruct} 入口<b>跳过</b>且对 ES 零交互；</li>
 *   <li>显式边界重载仍抛受控异常（请求/命令路径契约不弱化）；</li>
 *   <li>边界打开时初始化失败<b>不被吞掉</b>（缺 uris/ES 不可达必须响亮失败）；</li>
 *   <li>启动跳过之后，首次写入按需懒建共享索引——跳过不等于永久不建。</li>
 * </ol>
 */
@Tag("dev")
class EsKeywordIndexServiceStartupTest {

    /** 关闭态：与产品默认配置同义（legacy-listeners-enabled=false）。 */
    private static final SaasCapabilityBoundary CLOSED_BOUNDARY = new SaasCapabilityBoundary(
            new SaasBoundaryProperties(false, true, new SaasBoundaryProperties.CustomerApi(false), false));

    /** 打开态：仅用于正向对照，证明失败传播来自该开关之后的真实 ES 路径。 */
    private static final SaasCapabilityBoundary OPEN_BOUNDARY = new SaasCapabilityBoundary(
            new SaasBoundaryProperties(false, true, new SaasBoundaryProperties.CustomerApi(false), true));

    @Test
    @DisplayName("边界关闭：@PostConstruct 入口跳过初始化，ES 零交互")
    void closedBoundarySkipsStartupInitializationWithoutReachingEs() {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        EsKeywordIndexService service = new EsKeywordIndexService(
                esClient, new KeywordProperties(), providerOf(CLOSED_BOUNDARY));

        assertThatCode(service::initSharedIndex)
                .as("关闭态是默认状态：@PostConstruct 不得让整个 ApplicationContext 启动失败")
                .doesNotThrowAnyException();

        verifyNoInteractions(esClient);
    }

    @Test
    @DisplayName("边界缺席：按关闭处理（不默认放行），启动入口同样跳过")
    void absentBoundarySkipsStartupInitializationWithoutReachingEs() {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        EsKeywordIndexService service = new EsKeywordIndexService(
                esClient, new KeywordProperties(), providerOf(null));

        assertThatCode(service::initSharedIndex)
                .as("没有 SaasCapabilityBoundary bean 时语义是关闭，不是放行")
                .doesNotThrowAnyException();

        verifyNoInteractions(esClient);
    }

    @Test
    @DisplayName("显式边界重载：关闭态仍抛受控异常，且不含任何 ES 请求")
    void explicitBoundaryOverloadStillRejectsClosedCapability() {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        EsKeywordIndexService service = new EsKeywordIndexService(
                esClient, new KeywordProperties(), providerOf(CLOSED_BOUNDARY));

        assertThatThrownBy(() -> service.initSharedIndex(CLOSED_BOUNDARY))
                .isInstanceOf(SaasCapabilityBoundary.ClosedCapabilityException.class)
                .satisfies(error -> assertThat(
                        ((SaasCapabilityBoundary.ClosedCapabilityException) error).capability())
                        .isEqualTo(SaasCapabilityBoundary.LegacyCapability.ES_SHARED_INDEX_INITIALIZER));

        assertThatThrownBy(() -> service.initSharedIndex((SaasCapabilityBoundary) null))
                .as("边界缺失按关闭处理，显式重载不得静默返回")
                .isInstanceOf(SaasCapabilityBoundary.ClosedCapabilityException.class);

        verifyNoInteractions(esClient);
    }

    @Test
    @DisplayName("边界打开：初始化失败不被吞掉（缺 uris/ES 不可达必须响亮失败）")
    void openBoundaryDoesNotSwallowInitializationFailures() {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        EsKeywordIndexService service = new EsKeywordIndexService(
                esClient, new KeywordProperties(), providerOf(OPEN_BOUNDARY));

        // 未打桩的 indices() 返回 null：模拟"客户端不可用/uris 配置错误"的失败面。
        assertThatThrownBy(service::initSharedIndex)
                .as("跳过只适用于关闭态；打开后初始化失败必须冒泡，不能被启动包装吞掉")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ES 关键词共享索引创建失败");
    }

    /**
     * 启动跳过之后，首次写入仍必须进入共享索引确保路径（{@code ensureSharedIndex}）——
     * 跳过只关"启动期自动 I/O"，不关"按需懒建"。
     *
     * <p>本模块的测试用 subclass mock maker（{@code mockito-extensions/org.mockito.plugins.MockMaker}），
     * ES 客户端里 {@code exists(Function)} 是 final 方法，无法打桩；这里让
     * {@code indices()} 本身抛出哨兵异常，用"写入被包装成创建失败"证明懒建路径确实被走到。
     */
    @Test
    @DisplayName("启动跳过后首次写入仍进入共享索引确保路径（跳过不等于永久不建）")
    void firstWriteStillEntersSharedIndexEnsurePathAfterStartupSkip() {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        when(esClient.indices()).thenThrow(new IllegalStateException("lazy ensure reached"));

        EsKeywordIndexService service = new EsKeywordIndexService(
                esClient, new KeywordProperties(), providerOf(CLOSED_BOUNDARY));

        service.initSharedIndex();
        verifyNoInteractions(esClient);

        assertThatThrownBy(() -> service.indexDocumentChunks("000000", "kb_collection_1", "doc-1",
                List.of(new EmbeddedChunk(
                        new Chunk("chunk-1", 0, "hello", "hello", ChunkMetadata.empty()),
                        new float[] {0.1f, 0.2f}))))
                .as("首次写入必须尝试确保共享索引；该尝试失败时按既有契约响亮失败")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ES 关键词共享索引创建失败")
                .hasRootCauseMessage("lazy ensure reached");

        verify(esClient).indices();
    }

    /** 最小 {@link ObjectProvider}：命中时返回固定实例，未命中返回 {@code null}。 */
    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
