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

package org.ruoyi.aiidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 到 AI 节点屏障端点的 HTTP 通道（U10）。
 *
 * <p>失败一律返回 empty（调用方把"不可达"读作"无法证明已停"：保持 PENDING/
 * UNKNOWN，而不是假设节点已停）。不跟随重定向；2s 超时；只认单个 JSON 对象
 * 且 {@code code=200}。
 *
 * <p>仅在 {@code transport=http}（独立 AI 应用，默认）装配；内嵌
 * {@code transport=local} 由 {@link LocalAiBarrierPort} 提供同一端口。
 */
@Slf4j
@Component
@ConditionalOnExpression("'${ai.integration.enabled:false}' == 'true'"
        + " and '${ai.integration.transport:http}' != 'local'")
public class HttpAiBarrierClient implements RevocationBarrierCoordinator.AiBarrierPort {

    private final String baseUrl;
    private final String serviceCredential;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient httpClient;

    public HttpAiBarrierClient(AiIntegrationProperties properties) {
        this.baseUrl = properties.getAiBaseUrl();
        this.serviceCredential = properties.getServiceCredential();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Optional<Long> close(String tenantId, String barrierId, String reason) {
        return call("{\"action\":\"CLOSE\",\"tenantId\":\"" + escape(tenantId)
                + "\",\"barrierId\":\"" + escape(barrierId)
                + "\",\"reason\":\"" + escape(reason) + "\"}");
    }

    @Override
    public Optional<Long> activePermitCount(String tenantId) {
        return call("{\"action\":\"STATUS\",\"tenantId\":\"" + escape(tenantId) + "\"}");
    }

    @Override
    public void open(String tenantId, String barrierId) {
        Optional<Long> active = call("{\"action\":\"OPEN\",\"tenantId\":\"" + escape(tenantId)
                + "\",\"barrierId\":\"" + escape(barrierId) + "\"}");
        if (active.isEmpty() || active.get() != 0) { throw new IllegalStateException("barrier open unacknowledged"); }
    }

    private Optional<Long> call(String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/ai/v1/authorization/barriers"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .header("X-P04-Service-Credential", serviceCredential == null ? "" : serviceCredential)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("屏障调用失败：HTTP {}（节点不可达/拒绝都按无法证明处理）", response.statusCode());
                return Optional.empty();
            }
            JsonNode node = objectMapper.readTree(response.body());
            if (node == null || !node.isObject() || !node.path("code").isIntegralNumber() || node.path("code").intValue() != 200) {
                log.error("屏障调用响应形状非法：拒绝读取活跃数");
                return Optional.empty();
            }
            JsonNode active = node.path("activePermits");
            if (!active.isIntegralNumber() || !active.canConvertToLong() || active.longValue() < 0) {
                return Optional.empty();
            }
            JsonNode sent = objectMapper.readTree(body);
            String action = sent.path("action").textValue();
            if (!node.path("status").isTextual()
                    || ("OPEN".equals(action) && !"OPEN".equals(node.path("status").textValue()))
                    || ("CLOSE".equals(action) && !"PENDING".equals(node.path("status").textValue()))
                    || (sent.has("barrierId") && !sent.path("barrierId").equals(node.path("barrierId")))) {
                return Optional.empty();
            }
            return Optional.of(active.longValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.error("屏障调用异常：{}（按无法证明处理）", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
