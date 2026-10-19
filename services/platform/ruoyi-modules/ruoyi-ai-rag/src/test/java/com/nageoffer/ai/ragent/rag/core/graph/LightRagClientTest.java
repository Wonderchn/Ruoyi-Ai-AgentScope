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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.GraphProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import okhttp3.OkHttpClient;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class LightRagClientTest {

    private MockWebServer server;
    private LightRagClient client;
    private SearchChannelProperties searchProperties;
    private GraphProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new GraphProperties();
        properties.getLightrag().setBaseUrl(server.url("/").toString());
        searchProperties = new SearchChannelProperties();
        client = new LightRagClient(new OkHttpClient(), objectMapper, properties, searchProperties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    @DisplayName("删库只命中库名精确归属的文档，不连带前缀重叠的别库")
    void deleteByCollectionMatchesExactCollectionOnly() throws Exception {
        // 回归：旧 contains("kb_") 谓词会把 kb_hr 的两篇文档一并选中，删 kb 连带删光 kb_hr 的图谱数据且不可逆
        server.enqueue(json("""
                {"statuses":{"processed":[
                  {"id":"doc-kb","file_path":"kb_1954071234567890100"},
                  {"id":"doc-kb-hr-1","file_path":"kb_hr_1954071234567890200"},
                  {"id":"doc-kb-hr-2","file_path":"kb_hr_1954071234567890300"}
                ]}}
                """));
        server.enqueue(json("{}"));

        client.deleteByCollection("kb");

        RecordedRequest listRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(listRequest);
        assertEquals("/documents", listRequest.getTarget());

        RecordedRequest deleteRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(deleteRequest, "kb 名下有文档，应发起删除请求");
        assertEquals("/documents/delete_document", deleteRequest.getTarget());
        assertEquals(List.of("doc-kb"), docIdsOf(deleteRequest));
    }

    @Test
    @DisplayName("检索证据按库名精确归属切分主份与补充份")
    void retrieveByScopeSplitsByExactCollection() {
        // 回归：旧 contains 谓词会把 kb_hr 的证据归入 kb 的主份，跨库证据混进定向主路
        server.enqueue(json("""
                {"response":"ctx","references":[
                  {"reference_id":"r0","file_path":"kb_hr_1954071234567890200","content":["别库证据"]},
                  {"reference_id":"r1","file_path":"kb_1954071234567890100","content":["本库证据"]}
                ]}
                """));

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of("kb"));

        assertEquals(List.of("r1"), ids(evidence.matched()));
        assertEquals(List.of("r0"), ids(evidence.unmatched()));
        assertEquals("1954071234567890100", evidence.matched().get(0).getDocId());
    }

    @Test
    @DisplayName("过滤生效时 references 缺失的兜底上下文被丢弃而非无主入池")
    void fallbackContextIsDroppedWhenFilteringByCollection() {
        // 兜底块无 file_path、无法归属，放行等于绕过有效库过滤
        server.enqueue(json("{\"response\":\"整段上下文\"}"));

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of("kb"));

        assertTrue(evidence.matched().isEmpty());
        assertTrue(evidence.unmatched().isEmpty());
    }

    @Test
    @DisplayName("删除链路不受检索通道预算限制")
    void deletionIsNotBoundByChannelBudget() throws Exception {
        // 回归：检索预算若污染删除链路，大库文档枚举超预算会中断删除，静默留下图谱残留
        searchProperties.getChannels().setTimeoutMs(200);
        server.enqueue(json("""
                {"statuses":{"processed":[
                  {"id":"doc-kb","file_path":"kb_1954071234567890100"}
                ]}}
                """).newBuilder().bodyDelay(1, TimeUnit.SECONDS).build());
        server.enqueue(json("{}"));

        client.deleteByCollection("kb");

        server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest deleteRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(deleteRequest, "删除请求应照常发出，不被 200ms 检索预算掐断");
        assertEquals(List.of("doc-kb"), docIdsOf(deleteRequest));
    }

    @Test
    @DisplayName("客户端超时取通道级预算，超限调用降级为空证据")
    void clientTimeoutFollowsChannelBudget() {
        searchProperties.getChannels().setTimeoutMs(200);
        server.enqueue(json("{\"response\":\"ctx\"}").newBuilder().bodyDelay(1, TimeUnit.SECONDS).build());

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of());

        assertTrue(evidence.matched().isEmpty());
    }

    @Test
    @DisplayName("R11 /health 2xx=UP、非2xx=DOWN、超时=TIMEOUT")
    void probeDistinguishesUpDownTimeout() {
        server.enqueue(json("{\"status\":\"healthy\"}").newBuilder().code(200).build());
        LightRagClient.ProbeResult up = client.probe();
        assertTrue(up.reachable());
        assertFalse(up.timedOut());
        assertTrue(up.latencyMs() >= 0);

        server.enqueue(json("{}").newBuilder().code(500).build());
        LightRagClient.ProbeResult down = client.probe();
        assertFalse(down.reachable());
        assertFalse(down.timedOut());

        properties.getStatus().setTimeoutMs(100);
        server.enqueue(json("{}").newBuilder().headersDelay(1, TimeUnit.SECONDS).build());
        LightRagClient.ProbeResult timeout = client.probe();
        assertFalse(timeout.reachable());
        assertTrue(timeout.timedOut());
    }

    @Test
    @DisplayName("R7 严格 /graphs：非 2xx 抛错而不是降级 null")
    void strictGraphFetchThrowsOnFailure() throws IOException {
        server.enqueue(json("{}").newBuilder().code(503).build());
        assertThrows(IOException.class, () -> client.fetchGraphStrict("*", 2, 50));

        server.enqueue(json("{\"nodes\":[{\"id\":\"n1\"}],\"edges\":[],\"is_truncated\":false}"));
        assertNotNull(client.fetchGraphStrict("*", 2, 50).path("nodes").get(0));
    }

    @Test
    @DisplayName("R7 /documents 状态机：processed/pending/failed/missing 分类")
    void documentStateClassifiesLightRagGroups() throws Exception {
        String statuses = """
                {"statuses":{
                  "processed":[{"id":"lr-1","file_path":"kb_100"}],
                  "pending":[{"id":"lr-2","file_path":"kb_101"}],
                  "failed":[{"id":"lr-3","file_path":"kb_102"}]
                }}
                """;
        server.enqueue(json(statuses));
        assertEquals(LightRagClient.DocumentStatus.PROCESSED, client.documentState("kb_100", "100").status());
        server.enqueue(json(statuses));
        assertEquals(LightRagClient.DocumentStatus.PENDING, client.documentState("kb_101", "101").status());
        server.enqueue(json(statuses));
        assertEquals(LightRagClient.DocumentStatus.FAILED, client.documentState("kb_102", "102").status());
        server.enqueue(json(statuses));
        assertEquals(LightRagClient.DocumentStatus.MISSING, client.documentState("kb_999", "999").status());
    }

    @Test
    @DisplayName("R7 严格写入：非 2xx 抛错，调用方据此 503 而不是假成功")
    void strictInsertThrowsOnFailure() {
        server.enqueue(json("{}").newBuilder().code(500).build());
        assertThrows(IOException.class, () -> client.insertTextOrThrow("正文", "kb_100"));
    }

    private MockResponse json(String body) {
        return new MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private List<String> docIdsOf(RecordedRequest request) throws Exception {
        JsonNode body = objectMapper.readTree(request.getBody().utf8());
        List<String> ids = new ArrayList<>();
        body.path("doc_ids").forEach(node -> ids.add(node.asText()));
        return ids;
    }

    private static List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::getId).toList();
    }
}
