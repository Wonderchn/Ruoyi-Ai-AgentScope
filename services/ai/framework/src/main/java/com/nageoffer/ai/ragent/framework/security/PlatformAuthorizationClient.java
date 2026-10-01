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

package com.nageoffer.ai.ragent.framework.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * AI → platform 的在线授权复核客户端（F1 冻结路径
 * {@code POST /internal/platform/v1/authorization/check}）。
 *
 * <p>关键约束（Spec §7.2）：
 * <ul>
 *   <li>用<b>独立的服务凭证</b>，不复用浏览器 token，也不把"回环地址可达"当作授权；</li>
 *   <li>权限检查必须在查询幂等命中/返回原 runId <b>之前</b>执行，撤权后不能借重放取回结果；</li>
 *   <li>超时 / 503 / 坏响应一律判为 {@code 503 AUTHORIZATION_UNAVAILABLE} 并<b>不放行</b>。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class PlatformAuthorizationClient implements AuthorizationChecker {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthorizationClient.class);

    /** 服务凭证头（与 platform 侧同名）。 */
    public static final String SERVICE_CREDENTIAL_HEADER = "X-P04-Service-Credential";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ObjectMapper responseMapper;
    private final P04SecurityProperties properties;

    public PlatformAuthorizationClient(HttpClient httpClient, ObjectMapper objectMapper,
                                       P04SecurityProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.responseMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.properties = properties;
    }

    public record AuthorizeRequest(String tenantId, String subject, String membershipId, Integer policyVersion,
                                   String action, String resourceRef) {
    }

    /**
     * @throws P04AiException 平台侧的符号码（403/404/409 等），或 503 {@code AUTHORIZATION_UNAVAILABLE}
     */
    @Override
    public AuthorizeResult check(DelegatedPrincipal principal, String action, String resourceRef) {
        String credential = properties.getPlatform().getServiceCredential();
        if (credential == null || credential.isBlank()) {
            // 无凭证不得降级放行
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE, "service credential is not configured");
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(new AuthorizeRequest(principal.tenantId(), principal.subject(),
                    principal.membershipId(), principal.policyVersion(), action, resourceRef));
        } catch (Exception e) {
            throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getPlatform().getAuthorizationUrl()))
                .timeout(Duration.ofMillis(properties.getPlatform().getTimeoutMillis()))
                .header("Content-Type", "application/json")
                .header(SERVICE_CREDENTIAL_HEADER, credential)
                .header(AiRequestIdFilter.HEADER, AiRequestIdFilter.currentOrEmpty())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        } catch (Exception e) {
            // 连接失败、超时等：不可用即不放行
            log.warn("platform authorization unavailable type={} requestId={}",
                    e.getClass().getSimpleName(), AiRequestIdFilter.currentOrEmpty());
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }

        JsonNode root;
        try {
            root = responseMapper.readTree(response.body());
        } catch (Exception e) {
            // 坏响应
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }

        int status = response.statusCode();
        if (root == null || !root.isObject() || !root.path("code").isIntegralNumber()
                || !root.path("code").canConvertToInt() || root.path("code").intValue() != status
                || !root.path("data").isObject()) {
            throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        JsonNode data = root.get("data");
        if (status == ApiEnvelope.SUCCESS_CODE && data.path("allowed").isBoolean()
                && data.path("allowed").booleanValue() && data.path("policyVersion").isIntegralNumber()
                && data.path("policyVersion").canConvertToInt() && !data.has("errorCode")) {
            return new AuthorizeResult(true, data.get("policyVersion").intValue());
        }

        // 平台侧拒绝：透传同一符号码，保证跨服务断言可对齐
        JsonNode symbolic = data.path("errorCode");
        if (symbolic.isTextual() && !symbolic.textValue().isBlank()) {
            try {
                P04AiErrorCode errorCode = P04AiErrorCode.valueOf(symbolic.textValue());
                if (errorCode.httpStatus() == status) {
                    throw new P04AiException(errorCode);
                }
            } catch (IllegalArgumentException ignored) {
                throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
            }
        }
        throw new P04AiException(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE);
    }
}
