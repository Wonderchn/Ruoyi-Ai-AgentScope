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

package com.nageoffer.ai.ragent.rag.core.retrieval.channel;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.rag.config.EsClientConfig;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.keyword.EsKeywordRetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieval.AuthorizedRetrievalScope;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F05:4-ES-LIVE：关键词通道的真实 ES 检索证据（test-only，不改产品代码）。
 *
 * <p>用产品自身的 {@code indexPublishedChunks}（{@code _id=<versionId>:<chunkKey>}）写入夹具，
 * 再走真实 {@link EsKeywordRetrieverService} + {@link KeywordSearchChannel}：
 * <ul>
 *   <li>发布引用命中的 chunk 必须被真实 ES 检索到并通过 R1 授权过滤；</li>
 *   <li>发布引用不含该 chunk 时，同一真实检索必须被结果侧过滤为空；</li>
 *   <li>清理后按 tenant/collection/doc 的 {@code _count} 回落到 0。</li>
 * </ul>
 *
 * <p>默认不运行：需 {@code ES_LIVE=1}；{@code ES_PASSWORD} 缺失时 assume skip（不失败）。
 * 密码只从环境读取，不打印、不写入任何断言消息。
 */
@Tag("dev")
@EnabledIfEnvironmentVariable(named = "ES_LIVE", matches = "1")
class KeywordSearchChannelLiveTest {

    private static final String ES_URIS = "http://127.0.0.1:9200";
    private static final String ES_USERNAME = "elastic";

    @Test
    @DisplayName("真实 ES：发布 chunk 可检索，未发布引用被过滤，清理后计数回落")
    void publishedChunkIsRetrievableAndUnpublishedChunkIsFiltered() throws Exception {
        String password = System.getenv("ES_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "ES_PASSWORD is not provided; live ES leg skipped");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String tenant = "tl-" + suffix;
        String collection = "kb_f05live_" + suffix;
        String kbId = "kb-" + suffix;
        String version = "ver-" + suffix;
        String chunkKey = "c-" + suffix;
        String docId = "doc-" + suffix;
        String token = "f05livetoken" + suffix;
        String content = "P2 ES live evidence " + token;

        KeywordProperties properties = liveProperties(password);
        ElasticsearchClient client = new EsClientConfig().elasticsearchClient(properties);
        EsKeywordIndexService indexService = new EsKeywordIndexService(client, properties, boundaryProvider());
        KeywordSearchChannel channel = new KeywordSearchChannel(
                new EsKeywordRetrieverService(client, properties), new SearchChannelProperties(), Runnable::run);

        try {
            indexService.indexPublishedChunks(tenant, collection, kbId, docId, version,
                    List.of(new EsKeywordIndexService.PublishedChunk(chunkKey, 0, content)));
            refresh(client);
            assertEquals(1L, documentCount(client, tenant, collection, docId),
                    "fixture document should be visible after refresh");

            AuthorizedRetrievalScope published = scope(tenant, kbId, docId, collection, version, chunkKey);
            List<RetrievedChunk> hits = channel.search(context(token, collection, published)).getChunks();
            assertTrue(hits.stream().anyMatch(hit -> (version + ":" + chunkKey).equals(hit.getId())
                            && collection.equals(hit.getCollectionName())),
                    "expected the fixture chunk in real ES hits, ids=" + ids(hits));

            AuthorizedRetrievalScope unpublished = scope(tenant, kbId, docId, collection,
                    "ver-not-published", "c-not-published");
            assertTrue(channel.search(context(token, collection, unpublished)).getChunks().isEmpty(),
                    "a chunk outside publishedChunkRefs must be filtered after the real ES call");
        } finally {
            indexService.deleteDocumentIndex(tenant, collection, docId);
            refresh(client);
            assertEquals(0L, documentCount(client, tenant, collection, docId),
                    "cleanup must remove every fixture document");
        }
    }

    private static KeywordProperties liveProperties(String password) {
        KeywordProperties properties = new KeywordProperties();
        properties.setType("es");
        properties.getEs().setUris(ES_URIS);
        properties.getEs().setUsername(ES_USERNAME);
        properties.getEs().setPassword(password);
        return properties;
    }

    private static AuthorizedRetrievalScope scope(String tenant, String kbId, String docId,
                                                  String collection, String version, String chunkKey) {
        ExecutionPrincipal principal = new ExecutionPrincipal(tenant, "1",
                ExecutionPrincipal.canonicalMembershipId(tenant, "1"), 1, 1,
                Set.of("kb.retrieve"), "jti-test", "platform:local", 1_000L, 2_000L);
        AuthorizedResourceScope resource = AuthorizedResourceScope.granted(principal, "kb.retrieve",
                List.of("kb:" + kbId, "doc:" + docId), 1_000L);
        return AuthorizedRetrievalScope.of(resource, Set.of("kb:" + kbId), Set.of("doc:" + docId),
                Set.of("chunk:" + version + ":" + chunkKey), Set.of("doc:" + docId), Set.of(collection));
    }

    private static SearchContext context(String question, String collection, AuthorizedRetrievalScope scope) {
        NodeScore intent = NodeScore.builder()
                .node(IntentNode.builder()
                        .id("kb")
                        .name("kb")
                        .collectionNames(List.of(collection))
                        .build())
                .score(0.9)
                .build();
        return SearchContext.builder()
                .originalQuestion(question)
                .budget(new RetrievalBudget(10, 20, 5))
                .retrievalScope(new RetrievalScope(true, 0.9, List.of(intent),
                        List.of(collection), List.of()))
                .authorizedScope(scope)
                .build();
    }

    private static void refresh(ElasticsearchClient client) throws IOException {
        client.indices().refresh(r -> r.index(new KeywordProperties().sharedIndex()));
    }

    private static long documentCount(ElasticsearchClient client, String tenant, String collection,
                                      String docId) throws IOException {
        return client.count(c -> c.index(new KeywordProperties().sharedIndex())
                .query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("tenant_id").value(tenant)))
                        .filter(f -> f.term(t -> t.field("collection_name").value(collection)))
                        .filter(f -> f.term(t -> t.field("doc_id").value(docId)))))).count();
    }

    private static List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::getId).toList();
    }

    private static ObjectProvider<SaasCapabilityBoundary> boundaryProvider() {
        SaasCapabilityBoundary boundary = new SaasCapabilityBoundary(new SaasBoundaryProperties(
                false, true, new SaasBoundaryProperties.CustomerApi(false), true));
        return new ObjectProvider<>() {
            @Override
            public SaasCapabilityBoundary getObject(Object... args) {
                return boundary;
            }

            @Override
            public SaasCapabilityBoundary getIfAvailable() {
                return boundary;
            }

            @Override
            public SaasCapabilityBoundary getIfUnique() {
                return boundary;
            }

            @Override
            public SaasCapabilityBoundary getObject() {
                return boundary;
            }
        };
    }
}
