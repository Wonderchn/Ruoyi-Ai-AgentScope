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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 联网检索通道单元测试（离线，JDK HttpServer 127.0.0.1 随机端口，不依赖真实 Key）。
 *
 * <p>契约来源：F22:5 brief（DeepSeek Search `POST /v1/tools/search`，Bearer 鉴权，
 * {@code results[]} 含 title/url/snippet/summary/content/date/authority）。
 * 所有失败面只降级为空结果，绝不影响本地 RAG。
 */
@DisplayName("DeepSeek 联网检索通道")
@Tag("dev")
class DeepSeekWebSearchChannelTest {

    private static final String SECRET_KEY = "test-key-do-not-log-0718";

    private static final String SAMPLE_BODY = """
            {
              "results": [
                {
                  "title": "结果A",
                  "url": "https://example.com/a",
                  "snippet": "片段A",
                  "summary": "摘要A",
                  "content": "正文A",
                  "date": "2026-10-17",
                  "authority": "high",
                  "rerank_score": 0.93
                },
                {
                  "title": "结果B",
                  "url": "https://example.com/b",
                  "snippet": "片段B",
                  "summary": "摘要B"
                },
                {
                  "title": "结果C",
                  "url": "https://example.com/c",
                  "snippet": "片段C"
                }
              ]
            }
            """;

    private HttpServer server;
    private String stubUrl;

    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private volatile int responseCode = 200;
    private volatile String responseBody = SAMPLE_BODY;
    private volatile long responseDelayMs = 0;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tools/search", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        stubUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/tools/search";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("请求形状：POST JSON、Bearer 鉴权、query/count/need_content")
    void requestConstruction() throws Exception {
        DeepSeekWebSearchChannel channel = newChannel(defaultProperties(SECRET_KEY));

        channel.search(context("DeepSeek 联网搜索"));

        assertEquals("POST", lastMethod.get());
        assertEquals("/v1/tools/search", lastPath.get());
        assertEquals("Bearer " + SECRET_KEY, lastAuthorization.get());
        assertNotNull(lastContentType.get());
        assertTrue(lastContentType.get().contains("application/json"));

        JsonNode body = new ObjectMapper().readTree(lastBody.get());
        assertEquals("DeepSeek 联网搜索", body.path("query").asText());
        assertEquals(5, body.path("count").asInt());
        assertTrue(body.path("need_content").asBoolean(), "需要 content 字段做 content>summary>snippet 优先级");
    }

    @Test
    @DisplayName("响应映射：content > summary > snippet，text 带 title/date/authority/url，id 取 url")
    void mapsResultsWithContentPrecedence() {
        DeepSeekWebSearchChannel channel = newChannel(defaultProperties(SECRET_KEY));

        List<RetrievedChunk> chunks = channel.search(context("test")).getChunks();

        assertEquals(3, chunks.size());
        RetrievedChunk first = chunks.get(0);
        assertEquals("https://example.com/a", first.getId());
        assertEquals("正文A", bodyOf(first).split("\n")[1], "content 优先于 summary/snippet");
        assertTrue(first.getText().contains("结果A"));
        assertTrue(first.getText().contains("2026-10-17"));
        assertTrue(first.getText().contains("high"));
        assertTrue(first.getText().contains("来源: https://example.com/a"));

        // 无 content：取 summary；无 summary：取 snippet
        assertTrue(chunks.get(1).getText().contains("摘要B"));
        assertTrue(chunks.get(2).getText().contains("片段C"));

        for (int i = 0; i < chunks.size(); i++) {
            assertNotNull(chunks.get(i).getScore());
            if (i > 0) {
                assertTrue(chunks.get(i).getScore() < chunks.get(i - 1).getScore());
            }
        }
    }

    @Test
    @DisplayName("count 截断：请求 count 上限 50，响应按解析后的 count 截断")
    void countDefaultCapAndTruncation() throws Exception {
        SearchChannelProperties properties = defaultProperties(SECRET_KEY);
        properties.getChannels().getWebSearch().setCount(2);
        List<RetrievedChunk> chunks = newChannel(properties).search(context("test")).getChunks();
        assertEquals(2, chunks.size());
        assertEquals("https://example.com/a", chunks.get(0).getId());
        assertEquals("https://example.com/b", chunks.get(1).getId());
        JsonNode body = new ObjectMapper().readTree(lastBody.get());
        assertEquals(2, body.path("count").asInt());

        properties.getChannels().getWebSearch().setCount(100);
        newChannel(properties).search(context("test"));
        assertEquals(50, new ObjectMapper().readTree(lastBody.get()).path("count").asInt(),
                "DeepSeek count 上限 50");

        properties.getChannels().getWebSearch().setCount(0);
        newChannel(properties).search(context("test"));
        assertEquals(5, new ObjectMapper().readTree(lastBody.get()).path("count").asInt(),
                "非法 count 回退默认 5");
    }

    @Test
    @DisplayName("启用判定：开关 × provider=deepseek × key 非空，缺一不可；配置 key 空时回退 DEEPSEEK_API_KEY")
    void isEnabledGate() {
        assertTrue(newChannel(defaultProperties(SECRET_KEY)).isEnabled(context("q")));

        SearchChannelProperties disabled = defaultProperties(SECRET_KEY);
        disabled.getChannels().getWebSearch().setEnabled(false);
        assertFalse(newChannel(disabled).isEnabled(context("q")));

        SearchChannelProperties youcom = defaultProperties(SECRET_KEY);
        youcom.getChannels().getWebSearch().setProvider("youcom");
        assertFalse(newChannel(youcom).isEnabled(context("q")));

        SearchChannelProperties noKey = defaultProperties("");
        assertFalse(newChannelWithEnv(noKey, null).isEnabled(context("q")));
        assertTrue(newChannelWithEnv(noKey, "env-deepseek-key").isEnabled(context("q")),
                "api-key 为空时回退 DEEPSEEK_API_KEY");
    }

    @Test
    @DisplayName("避免双跑：provider=deepseek 且只有 DeepSeek key 时，You.com 通道 isEnabled=false")
    void youComChannelStaysDisabledWhenOnlyDeepSeekKeyExists() {
        SearchChannelProperties properties = defaultProperties(SECRET_KEY);
        WebSearchChannel youCom = new WebSearchChannel(
                new OkHttpClient(), new ObjectMapper(), properties) {
            @Override
            protected String readEnv(String name) {
                return null;
            }
        };

        assertFalse(youCom.isEnabled(context("q")),
                "provider=deepseek 且无 YDC_API_KEY 时 You.com 通道天然停用，不产生双跑");
    }

    @Test
    @DisplayName("失败降级：401/429/500、坏 JSON、超时均返回空结果且不抛")
    void failuresDegradeToEmpty() {
        for (int code : new int[]{401, 429, 500}) {
            responseCode = code;
            assertTrue(newChannel(defaultProperties(SECRET_KEY)).search(context("test")).getChunks().isEmpty(),
                    "HTTP " + code + " 应降级为空结果");
        }

        responseBody = "not-a-json{{{";
        assertTrue(newChannel(defaultProperties(SECRET_KEY)).search(context("test")).getChunks().isEmpty());

        responseDelayMs = 3000;
        SearchChannelProperties timeout = defaultProperties(SECRET_KEY);
        timeout.getChannels().getWebSearch().setTimeoutSeconds(1);
        assertTrue(newChannel(timeout).search(context("test")).getChunks().isEmpty());
    }

    @Test
    @DisplayName("空问题不发请求，直接空结果")
    void blankQuerySkips() {
        SearchChannelResult result = newChannel(defaultProperties(SECRET_KEY)).search(context("  "));
        assertTrue(result.getChunks().isEmpty());
        assertNull(lastPath.get(), "空问题不应发起请求");
    }

    @Test
    @DisplayName("失败与成功日志均不得出现 API Key")
    void apiKeyNeverLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(DeepSeekWebSearchChannel.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            newChannel(defaultProperties(SECRET_KEY)).search(context("成功路径"));
            responseCode = 401;
            newChannel(defaultProperties(SECRET_KEY)).search(context("失败路径"));

            assertFalse(appender.list.isEmpty(), "测试必须实际产生日志，否则断言空转");
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage() != null
                                && event.getFormattedMessage().contains(SECRET_KEY),
                        "日志不得包含 API Key: " + event.getFormattedMessage());
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("通道元信息：名称 DeepSeekWebSearch、类型 WEB_SEARCH")
    void channelWiring() {
        DeepSeekWebSearchChannel channel = newChannel(defaultProperties(SECRET_KEY));
        assertEquals("DeepSeekWebSearch", channel.getName());
        assertEquals(SearchChannelType.WEB_SEARCH, channel.getType());
    }

    // ============== F22:6 出口 allow-list ==============

    @Test
    @DisplayName("出口策略拒绝：零请求、空结果，不抛出")
    void deniedEgressMakesNoRequestAndReturnsEmpty() {
        DeepSeekWebSearchChannel channel = newChannel(defaultProperties(SECRET_KEY));
        ReflectionTestUtils.setField(channel, "egressPolicy", providerOf(policy("youcom")));

        SearchChannelResult result = channel.search(context("联邦检索"));

        assertTrue(result.getChunks().isEmpty());
        assertNull(lastPath.get(), "出口拒绝时不得发起任何请求");
    }

    @Test
    @DisplayName("出口策略 bean 缺席：fail-closed，零请求、空结果")
    void absentEgressPolicyMakesNoRequestAndReturnsEmpty() {
        DeepSeekWebSearchChannel channel = newChannel(defaultProperties(SECRET_KEY));
        ReflectionTestUtils.setField(channel, "egressPolicy", null);

        SearchChannelResult result = channel.search(context("联邦检索"));

        assertTrue(result.getChunks().isEmpty());
        assertNull(lastPath.get(), "策略缺席必须按拒绝处理，不得外呼");
    }

    // ============== helpers ==============

    private SearchChannelProperties defaultProperties(String apiKey) {
        SearchChannelProperties properties = new SearchChannelProperties();
        SearchChannelProperties.WebSearch webSearch = properties.getChannels().getWebSearch();
        webSearch.setEnabled(true);
        webSearch.setProvider("deepseek");
        webSearch.setCount(5);
        webSearch.setTimeoutSeconds(5);
        webSearch.getDeepseek().setApiKey(apiKey);
        webSearch.getDeepseek().setApiUrl(stubUrl);
        return properties;
    }

    private DeepSeekWebSearchChannel newChannel(SearchChannelProperties properties) {
        return newChannelWithEnv(properties, null);
    }

    private DeepSeekWebSearchChannel newChannelWithEnv(SearchChannelProperties properties, String envValue) {
        DeepSeekWebSearchChannel channel = new DeepSeekWebSearchChannel(
                new OkHttpClient(), new ObjectMapper(), properties) {
            @Override
            protected String readEnv(String name) {
                return "DEEPSEEK_API_KEY".equals(name) ? envValue : null;
            }
        };
        // F22:6：显式注入允许策略，既有正例断言不弱化
        ReflectionTestUtils.setField(channel, "egressPolicy", providerOf(policy("deepseek")));
        return channel;
    }

    static EgressPolicy policy(String... providers) {
        EgressPolicy policy = new EgressPolicy();
        policy.setEnabled(true);
        policy.setAllowedProviders(String.join(",", providers));
        return policy;
    }

    static <T> ObjectProvider<T> providerOf(T value) {
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

    private SearchContext context(String question) {
        return SearchContext.builder()
                .originalQuestion(question)
                .budget(RetrievalBudget.uniform(10))
                .build();
    }

    /** 取 text 中标题行之后的主体行（用于断言 content 优先级）。 */
    private static String bodyOf(RetrievedChunk chunk) {
        return chunk.getText().replace("\r\n", "\n");
    }
}
