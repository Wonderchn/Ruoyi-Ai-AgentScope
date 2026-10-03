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

package com.nageoffer.ai.ragent.runtime.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 异步执行重新获取当前主体/策略事实（U02：异步只保存引用，不保存过期 JWT）。
 *
 * <p>调用平台 {@code POST /internal/platform/v1/authorization/current}（服务凭证保护，
 * 无凭证/不可用/成员失效一律拒绝，不降级放行）。
 */
@Component
@ConditionalOnProperty(name = "ai.integration.security.enabled", havingValue = "true")
public class PlatformFactsClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String credential;

    public PlatformFactsClient(@Value("${ai.integration.platform-base-url:}") String baseUrl,
                               @Value("${ai.integration.platform-service-credential:}") String credential) {
        if (baseUrl.isBlank() || credential.isBlank()) {
            throw new IllegalStateException("platform facts configuration required");
        }
        this.baseUrl = baseUrl;
        this.credential = credential;
    }

    public record CurrentFacts(boolean enabled, int policyVersion) {
    }

    public CurrentFacts currentFacts(String tenantId, String subject, String membershipId) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "tenantId", tenantId, "subject", subject, "membershipId", membershipId));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/platform/v1/authorization/current"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("X-P04-Service-Credential", credential)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE,
                        "platform current facts rejected: " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (root.path("code").asInt() != 200 || !data.has("enabled") || !data.has("policyVersion")) {
                throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, "platform current facts invalid");
            }
            return new CurrentFacts(data.path("enabled").asBoolean(false), data.path("policyVersion").asInt(0));
        } catch (RunApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, "platform current facts interrupted");
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, "platform current facts unavailable");
        }
    }
}
