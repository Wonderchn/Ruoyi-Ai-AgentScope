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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 联网检索通道真实 API 集成测试（F22:5 真机证据）。
 *
 * <p>仅当环境变量 {@code DEEPSEEK_API_KEY} 存在时运行（1 次真实调用），日常构建自动跳过。
 * 密钥只从该环境变量读取；不打印密钥、不打印响应全文，只打印条数与耗时。
 */
@DisplayName("DeepSeek 联网检索通道（真实 API）")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@Tag("dev")
class DeepSeekWebSearchChannelLiveTest {

    private static final Pattern HTTP_URL = Pattern.compile("https?://\\S+");

    @Test
    @DisplayName("真实检索返回非空 Chunk，且至少一条带 http(s) 来源链接")
    void liveSearchReturnsChunks() {
        SearchChannelProperties properties = new SearchChannelProperties();
        SearchChannelProperties.WebSearch webSearch = properties.getChannels().getWebSearch();
        webSearch.setEnabled(true);
        webSearch.setProvider("deepseek");
        webSearch.setCount(3);
        webSearch.setTimeoutSeconds(15);
        // deepseek.api-key 留空 -> 回退环境变量 DEEPSEEK_API_KEY；apiUrl 使用官方默认值

        DeepSeekWebSearchChannel channel = new DeepSeekWebSearchChannel(
                new OkHttpClient(), new ObjectMapper(), properties);
        // F22:6：真实检索也必须显式允许 deepseek，缺策略即零外呼
        ReflectionTestUtils.setField(channel, "egressPolicy",
                DeepSeekWebSearchChannelTest.providerOf(DeepSeekWebSearchChannelTest.policy("deepseek")));

        SearchContext context = SearchContext.builder()
                .originalQuestion("DeepSeek search API release notes October 2026")
                .budget(RetrievalBudget.uniform(3))
                .build();
        assertTrue(channel.isEnabled(context), "DEEPSEEK_API_KEY 存在时应启用");

        SearchChannelResult result;
        try {
            result = channel.search(context);
        } catch (RuntimeException e) {
            String apiKey = System.getenv("DEEPSEEK_API_KEY");
            assertFalse(e.getMessage() != null && e.getMessage().contains(apiKey),
                    "异常消息不得包含 API Key");
            throw e;
        }

        assertNotNull(result, "search 必须返回结果对象");
        assertFalse(result.getChunks().isEmpty(), "真实 API 应返回至少一条结果");
        assertTrue(result.getLatencyMs() >= 0, "耗时不得为负");
        assertTrue(result.getChunks().stream()
                        .anyMatch(chunk -> chunk.getText() != null
                                && HTTP_URL.matcher(chunk.getText()).find()),
                "至少一条 Chunk 文本应包含 http(s) URL");
        assertEquals(SearchChannelType.WEB_SEARCH, result.getChannelType());

        // 只打印条数与耗时：不打印 key，也不打印响应全文
        System.out.println("[LIVE] DeepSeek 返回 " + result.getChunks().size()
                + " 个 Chunk, 耗时 " + result.getLatencyMs() + "ms");
    }
}
