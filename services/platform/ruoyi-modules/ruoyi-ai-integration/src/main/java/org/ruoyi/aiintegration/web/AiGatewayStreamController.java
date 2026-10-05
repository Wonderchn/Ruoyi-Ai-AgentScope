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

package org.ruoyi.aiintegration.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ruoyi.aiintegration.config.AiIntegrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 专用受限传输：SSE 事件流与流式上传。
 *
 * <p>两者都使用独立超时/上限（不扩大普通转发的 2s/2MiB，也不扩大在线授权时限）；
 * 路径是精确映射，先于网关 catch-all 生效，且同样经过委托链（最小 scope）。
 */
@RestController
@RequestMapping("/api/ai/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiGatewayStreamController {

    static final String AI_INTERNAL_PREFIX = "/internal/ai/v1";

    private final GatewayAuthorizer authorizer;
    private final AiGatewayClient client;
    private final AiIntegrationProperties properties;

    public AiGatewayStreamController(GatewayAuthorizer authorizer, AiGatewayClient client,
                                     AiIntegrationProperties properties) {
        this.authorizer = authorizer;
        this.client = client;
        this.properties = properties;
    }

    /** 专用流式上传：不把通用 byte[] 代理改成无界缓冲。 */
    @PostMapping("/documents/uploads")
    public void upload(HttpServletRequest request, HttpServletResponse response) {
        try {
            GatewayAuthorizer.Authorized authorized = authorizer.authorize(request, "document.upload");
            long declared = request.getContentLengthLong();
            if (declared > properties.getUploadMaxBytes()) {
                writeError(response, P04ErrorCode.BAD_REQUEST);
                return;
            }
            URI target = targetUri(request, "/documents/uploads");
            Map<String, String> headers = GatewayHeaders.sanitize(request);
            if (authorized.delegationToken() != null) {
                headers.put("Authorization", "Bearer " + authorized.delegationToken());
            }
            if (!properties.isLocalTransport()) {
                headers.put("X-P04-Service-Credential", properties.getServiceCredential());
            }
            headers.put(RequestId.HEADER, RequestId.currentOrEmpty());
            client.forwardUploadStream(new AiGatewayClient.ForwardRequest("POST", target, Map.copyOf(headers), null,
                    () -> {
                        try {
                            return request.getInputStream();
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }), response, properties.getUploadMaxBytes(),
                    Math.max(60000, properties.getForwardTimeoutMillis() * 30));
        } catch (P04Exception e) {
            writeError(response, e.errorCode());
        } catch (AiGatewayClient.UpstreamUnavailableException e) {
            writeError(response, P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    /** 统一 SSE：afterSeq / Last-Event-ID 游标；空闲/总时长/建连均受限。 */
    @GetMapping("/documents/{docId}/source")
    public void source(@PathVariable String docId,HttpServletRequest request,HttpServletResponse response) {
        try {
            if(docId==null || !docId.matches("[A-Za-z0-9_-]{1,64}")) throw new P04Exception(P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            var authorized=authorizer.authorize(request,"document.download");
            var headers=GatewayHeaders.sanitize(request);
            if(authorized.delegationToken()!=null){headers.put("Authorization","Bearer "+authorized.delegationToken());}
            if(!properties.isLocalTransport()){headers.put("X-P04-Service-Credential",properties.getServiceCredential());}
            headers.put(RequestId.HEADER,RequestId.currentOrEmpty());
            client.forwardPrivateDocument(new AiGatewayClient.ForwardRequest("GET",targetUri(request,"/documents/"+docId+"/source"),Map.copyOf(headers),null),
                    response,50*1024*1024,120000,new AiGatewayClient.DeliveryAck(
                            URI.create(base()+AI_INTERNAL_PREFIX+"/authorization/deliveries/release"),
                            authorized.member().tenantId(),authorized.member().membershipId(),properties.getServiceCredential()));
        } catch(P04Exception e){writeError(response,e.errorCode());}
        catch(AiGatewayClient.UpstreamUnavailableException e){writeError(response,P04ErrorCode.AUTHORIZATION_UNAVAILABLE);}
    }

    @GetMapping("/runs/{runId}/events")
    public void events(@PathVariable String runId, HttpServletRequest request, HttpServletResponse response) {
        try {
            if (runId == null || runId.isBlank() || !runId.matches("[A-Za-z0-9_-]{1,64}")) {
                writeError(response, P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
                return;
            }
            GatewayAuthorizer.Authorized authorized = authorizer.authorize(request, "run.stream");
            URI target = targetUri(request, "/runs/" + runId + "/events");
            Map<String, String> headers = GatewayHeaders.sanitize(request);
            if (authorized.delegationToken() != null) {
                headers.put("Authorization", "Bearer " + authorized.delegationToken());
            }
            if (!properties.isLocalTransport()) {
                headers.put("X-P04-Service-Credential", properties.getServiceCredential());
            }
            headers.put(RequestId.HEADER, RequestId.currentOrEmpty());
            headers.put("Accept", "text/event-stream");
            client.forwardEventStream(new AiGatewayClient.ForwardRequest("GET", target, Map.copyOf(headers), null),
                    response, properties.getSseConnectTimeoutMillis(), properties.getSseIdleTimeoutMillis(),
                    properties.getSseMaxDurationMillis(),new AiGatewayClient.DeliveryAck(
                            URI.create(base()+AI_INTERNAL_PREFIX+"/authorization/deliveries/release"),
                            authorized.member().tenantId(),authorized.member().membershipId(),properties.getServiceCredential()));
        } catch (P04Exception e) {
            writeError(response, e.errorCode());
        } catch (AiGatewayClient.UpstreamUnavailableException e) {
            writeError(response, P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    /** 传输基地址：local 形态是本进程（占位主机，仅保持 URI 形状）；http 形态取真实 AI 基地址。 */
    private String base() {
        return properties.isLocalTransport() ? "http://local" : properties.getAiBaseUrl();
    }

    private URI targetUri(HttpServletRequest request, String subPath) {
        String query = request.getQueryString();
        String target = base() + AI_INTERNAL_PREFIX + subPath
                + (query == null || query.isBlank() ? "" : "?" + query);
        try {
            return new URI(target);
        } catch (URISyntaxException e) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST);
        }
    }

    private void writeError(HttpServletResponse response, P04ErrorCode code) {
        if (response.isCommitted()) {
            return;
        }
        try {
            response.setStatus(code.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader(RequestId.HEADER, RequestId.currentOrEmpty());
            String body = "{\"code\":" + code.httpStatus() + ",\"msg\":\"" + code.message()
                    + "\",\"data\":{\"errorCode\":\"" + code.name() + "\",\"retryable\":false}}";
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            response.getOutputStream().flush();
        } catch (java.io.IOException ignored) {
            // 响应已不可写
        }
    }
}
