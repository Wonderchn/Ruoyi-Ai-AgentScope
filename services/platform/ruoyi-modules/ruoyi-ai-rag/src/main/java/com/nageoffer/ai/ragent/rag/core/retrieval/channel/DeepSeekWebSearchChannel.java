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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek 联网检索通道（F22:5）。
 *
 * <p><b>目标。</b>用既有 {@code DEEPSEEK_API_KEY} 走 DeepSeek Search，为内嵌 RAG 增加
 * 时效性网页召回；结果与本地通道一起进入既有 RRF 融合，通道间无优先级。
 *
 * <p><b>启用条件（缺一不可）。</b>{@code rag.search.channels.web-search.enabled=true} ×
 * {@code provider=deepseek} × key 可解析（配置 {@code deepseek.api-key} 优先，为空回退
 * 环境变量 {@code DEEPSEEK_API_KEY}）。默认 provider=youcom 时本通道不启用，
 * 既有 {@link WebSearchChannel} 行为不变。
 *
 * <p><b>失败只降级。</b>网络异常、非 2xx、坏 JSON、超时一律 warn + 空结果，绝不向调用链抛出；
 * 日志只记录状态码/结果数/耗时，<b>不记录 API Key</b>。
 *
 * <p><b>结果映射。</b>正文取 {@code content > summary > snippet}；title/date/authority/url
 * 编入 {@code text}（{@link RetrievedChunk} 无 metadata map，且本切片不改该模型），
 * id 取 url 作为去重键；分数用通道内名次倒数（无量纲，与既有联网通道一致，
 * 多通道时由 RRF 重新按名次融合）。
 */
@Slf4j
public class DeepSeekWebSearchChannel implements SearchChannel {

    /**
     * API Key 环境变量名（与既有 DeepSeek 模型客户端同源）。
     */
    private static final String ENV_API_KEY = "DEEPSEEK_API_KEY";

    private static final MediaType JSON = MediaType.parse("application/json");

    /**
     * DeepSeek Search 单次返回条数上限。
     */
    private static final int MAX_COUNT = 50;

    /**
     * 配置非法时的默认条数。
     */
    private static final int DEFAULT_COUNT = 5;

    /**
     * F22:6 统一出口 allow-list 的 provider id（与 DeepSeek 模型客户端同源）。
     */
    private static final String EGRESS_PROVIDER = "deepseek";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SearchChannelProperties properties;

    /** F22:6：缺席即拒绝；字段注入覆盖内嵌 @Bean 装配（本类不是组件扫描 bean）。 */
    @Autowired(required = false)
    private ObjectProvider<EgressPolicy> egressPolicy;

    public DeepSeekWebSearchChannel(@Qualifier("syncHttpClient") OkHttpClient httpClient,
                                    ObjectMapper objectMapper,
                                    SearchChannelProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "DeepSeekWebSearch";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.WEB_SEARCH;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        SearchChannelProperties.WebSearch config = properties.getChannels().getWebSearch();
        return config.isEnabled()
                && "deepseek".equalsIgnoreCase(config.getProvider())
                && StrUtil.isNotBlank(resolveApiKey());
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            // F22:6：联网外发前必须过统一 allow-list；拒绝按既有降级语义交空结果，不抛到检索链
            if (!egressAllowed()) {
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            String query = context.getMainQuestion();
            if (StrUtil.isBlank(query)) {
                log.info("DeepSeek 联网检索问题为空，跳过");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            SearchChannelProperties.WebSearch config = properties.getChannels().getWebSearch();
            String apiUrl = config.getDeepseek().getApiUrl();
            HttpUrl httpUrl = HttpUrl.parse(apiUrl);
            if (httpUrl == null) {
                log.warn("DeepSeek 联网检索 api-url 配置非法，返回空结果");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            int count = resolveCount(config);
            ObjectNode body = objectMapper.createObjectNode()
                    .put("query", query)
                    .put("count", count)
                    // 需要 content 字段才能按 content > summary > snippet 取正文；
                    // 服务端未返回 content 时仍按 summary/snippet 降级。
                    .put("need_content", true);

            Request request = new Request.Builder()
                    .url(httpUrl)
                    .header("Authorization", "Bearer " + resolveApiKey())
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .build();

            // 按通道配置收紧超时；newBuilder 复用连接池与线程池，代价可忽略。
            int timeoutSeconds = Math.max(1, config.getTimeoutSeconds());
            OkHttpClient client = httpClient.newBuilder()
                    .callTimeout(Duration.ofSeconds(timeoutSeconds))
                    .readTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            List<RetrievedChunk> chunks;
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    // 401 鉴权失败 / 429 限流 / 5xx 服务端异常等统一降级为空结果（不打印 Key）。
                    log.warn("DeepSeek 联网检索请求失败, code={}, 返回空结果", response.code());
                    return emptyResult(System.currentTimeMillis() - startTime);
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                chunks = parseChunks(responseBody, count);
            }

            long latency = System.currentTimeMillis() - startTime;
            log.info("DeepSeek 联网检索完成，检索到 {} 个 Chunk，耗时 {}ms", chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.WEB_SEARCH)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            // 联网检索属于补充通道，任何异常（含超时、响应解析失败）都不允许向上抛出。
            log.warn("DeepSeek 联网检索失败，降级为空结果: {}", e.getMessage());
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 出口策略：bean 缺席即拒绝；拒绝只 warn（含 provider 名，不含凭据），不抛出。
     */
    private boolean egressAllowed() {
        EgressPolicy policy = egressPolicy == null ? null : egressPolicy.getIfAvailable();
        if (policy == null) {
            log.warn("DeepSeek 联网检索出口策略缺席，按拒绝处理 provider={}", EGRESS_PROVIDER);
            return false;
        }
        try {
            policy.requireAllowed(EGRESS_PROVIDER);
            return true;
        } catch (RuntimeException denied) {
            log.warn("DeepSeek 联网检索出口未允许，按拒绝处理 provider={}", EGRESS_PROVIDER);
            return false;
        }
    }

    /**
     * 解析 {@code {"results":[...]}}，按配置 count 截断。
     */
    private List<RetrievedChunk> parseChunks(String body, int maxResults) throws Exception {
        JsonNode results = objectMapper.readTree(body).path("results");
        if (!results.isArray()) {
            return List.of();
        }

        List<RetrievedChunk> chunks = new ArrayList<>();
        for (JsonNode item : results) {
            RetrievedChunk chunk = toChunk(item, chunks.size());
            if (chunk != null) {
                chunks.add(chunk);
                if (chunks.size() >= maxResults) {
                    break;
                }
            }
        }
        return chunks;
    }

    /**
     * 单条结果映射：正文 content &gt; summary &gt; snippet；title/date/authority/url 编入 text。
     */
    private RetrievedChunk toChunk(JsonNode item, int rank) {
        String url = text(item, "url");
        String title = text(item, "title");
        String content = firstNonBlank(
                text(item, "content"),
                text(item, "summary"),
                text(item, "snippet"));
        String date = text(item, "date");
        String authority = text(item, "authority");

        StringBuilder text = new StringBuilder();
        if (StrUtil.isNotBlank(title)) {
            text.append("【").append(title).append("】\n");
        }
        if (StrUtil.isNotBlank(content)) {
            text.append(content).append('\n');
        }
        if (StrUtil.isNotBlank(date)) {
            text.append("日期: ").append(date).append('\n');
        }
        if (StrUtil.isNotBlank(authority)) {
            text.append("权威度: ").append(authority).append('\n');
        }
        if (StrUtil.isNotBlank(url)) {
            text.append("来源: ").append(url);
        }

        String chunkText = text.toString().trim();
        if (StrUtil.isBlank(chunkText)) {
            return null;
        }
        return RetrievedChunk.builder()
                .id(StrUtil.isNotBlank(url) ? url : null)
                .text(chunkText)
                .score(1.0f / (rank + 1))
                .build();
    }

    private static String text(JsonNode item, String field) {
        return item.path(field).asText("");
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StrUtil.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * 解析请求/截断条数：非法值回退默认 5，超过 DeepSeek 上限 50 截断。
     */
    private int resolveCount(SearchChannelProperties.WebSearch config) {
        int count = config.getCount() > 0 ? config.getCount() : DEFAULT_COUNT;
        return Math.min(count, MAX_COUNT);
    }

    /**
     * 解析 API Key：配置 deepseek.api-key 优先，为空回退环境变量 DEEPSEEK_API_KEY。
     */
    private String resolveApiKey() {
        String apiKey = properties.getChannels().getWebSearch().getDeepseek().getApiKey();
        return StrUtil.isNotBlank(apiKey) ? apiKey : readEnv(ENV_API_KEY);
    }

    /**
     * 读取环境变量（可测试性：单元测试可覆盖此方法屏蔽真实环境）。
     */
    protected String readEnv(String name) {
        return System.getenv(name);
    }
}
