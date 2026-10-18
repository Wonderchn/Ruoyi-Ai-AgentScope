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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AiGatewayStreamController.class);

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
        forwardUpload(request, response, "document.upload", "/documents/uploads");
    }

    /**
     * admin 知识库上传（{@code POST /api/ai/v1/knowledge-base/{kbId}/docs/upload}）。
     *
     * <p><b>为什么复用同一条专用流通道（F05:1 薄适配）。</b>该 admin 端点此前由网关
     * catch-all 分支承接，multipart <b>表单字段</b>不随转送体到达内层
     * （{@code BodyProvidingRequest} 只暴露 query）⇒ 内层 {@code @ModelAttribute} 绑成
     * null ⇒ {@code SourceType.normalize} / {@code MethodArgumentNotValid} ⇒ 漏斗 503
     * （F05:3 卡 §2.2:62 实录）。复用 workbench/P2 已验证的<b>传输机制</b>即等价：
     * 精确映射（先于 catch-all 生效）+ multipart 旁路排除
     * （{@link GatewayMultipartConfiguration} / {@link GatewayRawUploadFilter} <b>成对</b>
     * 登记该路径）+ local 形态 {@code MultipartTargetRequest} 进程内直调，原始 multipart
     * 部件（文件 + 表单字段）整包到内层；内层（{@code KnowledgeDocumentController} +
     * {@code KnowledgeDocumentUploadRequest}）<b>零形状改动</b>（200 + 整数 {@code code:200} +
     * {@code KnowledgeDocumentVO}）。
     *
     * <p><b>权限面零增量</b>：动作仍是既有 {@code document.upload}——与 catch-all 路由表
     * {@code AiGatewayController} 中同一路径登记的是同一 canonical 动作、同一条权限行
     * （V5 7118），不新增动作、不新增权限行、不新增迁移。
     *
     * <p><b>不建通用网关 multipart 转发</b>：只有这一条精确路径走本方法，不做
     * {@code /knowledge-base/**} 通配。
     */
    @PostMapping("/knowledge-base/{kbId}/docs/upload")
    public void uploadKnowledgeDocument(@PathVariable String kbId, HttpServletRequest request,
                                        HttpServletResponse response) {
        forwardUpload(request, response, "document.upload",
                "/knowledge-base/" + kbId + "/docs/upload");
    }

    /**
     * 技能包导入（F11:4）：5 MiB 级 multipart 不能走通用网关（2 MiB 上限、且 local
     * 通用转发不保 multipart 字段），故复用本专用上传通道——与知识库上传同一传输与上限
     * 语义（{@code uploadMaxBytes}）、同一委托链。
     *
     * <p><b>权限面零增量</b>：动作复用既有 {@code skill.write}；该动作在
     * {@code PLATFORM_ADMIN_ACTIONS} 内，{@link GatewayAuthorizer} 已补平台管理第二道门，
     * 只持 scope 的非平台管理员在此 403（与既有 skill 路由同口径）。
     *
     * <p><b>不要**在 ROUTES 再登记 import**：本方法为精确映射、先于 catch-all 生效；
     * 额外 ROUTES 行会成为不可达死路由。export 仍走 ROUTES（GET 字节分支）。
     */
    @PostMapping("/agent-catalog/agent-skills/import")
    public void importSkillPackage(HttpServletRequest request, HttpServletResponse response) {
        forwardUpload(request, response, "skill.write", "/agent-catalog/agent-skills/import");
    }

    /**
     * 两条上传路径的共用传输：授权 → 目标 URI → 净化头 → 原始体流式转发。
     *
     * <p>抽出来是为了让"两条路径走完全一致的传输与上限语义"成为**结构性事实**，
     * 而不是两段复制粘贴各自漂移（同一 {@code uploadMaxBytes} 上限、同一超时换算、
     * 同一条委托链）。与 {@link #streamAgent} 同一纪律。
     */
    private void forwardUpload(HttpServletRequest request, HttpServletResponse response,
                               String action, String subPath) {
        try {
            GatewayAuthorizer.Authorized authorized = authorizer.authorize(request, action);
            long declared = request.getContentLengthLong();
            if (declared > properties.getUploadMaxBytes()) {
                writeError(response, P04ErrorCode.BAD_REQUEST);
                return;
            }
            URI target = targetUri(request, subPath);
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

    /**
     * Agent 对话 SSE 流（F10 / C13.3-3）。
     *
     * <p><b>为什么必须走这条专用通道而不是网关通用白名单转发。</b>通用转发有
     * <b>2s / 2MiB</b> 上限且<b>不做 SSE</b>：Agent 一次运行最多 {@code agent.max-iters} 轮，
     * 每轮量级接近 RAG 单问全程。用通用通道的后果是把一条长流硬切成 2 秒超时，
     * 表现为客户端拿到 503 而不是流，且 2MiB 上限会在长回答时截断。
     *
     * <p>与方法 {@link #events} 同等对待：精确映射（先于网关 catch-all 生效）、
     * 独立建连/空闲/总时长预算、经过同一委托链（最小 scope）、逐帧交付回执。
     * <b>不做 {@code /agent/v1/**} 通配</b>（C3.2）：本方法只映射这一条路径。
     *
     * <p>scope 是 {@code run.stream}（C13.4 登记的动作映射），不新增动作、不新增权限行。
     */
    @GetMapping("/agent/v1/chat")
    public void agentChat(HttpServletRequest request, HttpServletResponse response) {
        streamAgent(request, response, "run.stream", "GET", "/agent/v1/chat", null);
    }

    /**
     * Agent 审批确认 SSE（F10 / C13.3-3）。
     *
     * <p>与 {@link #agentChat} 同一条专用通道，差别有三处，都不是可选项：
     * <ol>
     *   <li><b>方法是 POST</b>，带 JSON body（{@code conversationId/messageId/approved}）。
     *       通用转发的 SSE 分支此前硬写 GET，POST 体根本传不上去；本包在
     *       {@code AiGatewayClient.forwardEventStream} 里改为按 {@code method()} 选择
     *       GET/POST，并在这里显式传 body。</li>
     *   <li><b>scope 是 {@code run.approve}</b>，不是 {@code run.stream} —— 审批是
     *       与"看流"不同的动作，复用 stream 权限等于把审批权扩散给只该看流的人。</li>
     *   <li><b>body 有界</b>：确认体是一个小 JSON 对象；超过上限直接 400，
     *       不读成无界缓冲（与上传通道的 {@code uploadMaxBytes} 同理，但用独立的更小上限）。</li>
     * </ol>
     */
    @PostMapping("/agent/v1/chat/confirm")
    public void agentChatConfirm(HttpServletRequest request, HttpServletResponse response) {
        try {
            long declared = request.getContentLengthLong();
            if (declared > CONFIRM_MAX_BYTES) {
                writeError(response, P04ErrorCode.BAD_REQUEST);
                return;
            }
            byte[] body;
            try (java.io.InputStream in = request.getInputStream()) {
                body = in.readNBytes(CONFIRM_MAX_BYTES + 1);
            }
            if (body.length > CONFIRM_MAX_BYTES) {
                writeError(response, P04ErrorCode.BAD_REQUEST);
                return;
            }
            streamAgent(request, response, "run.approve", "POST", "/agent/v1/chat/confirm", body);
        } catch (P04Exception e) {
            writeError(response, e.errorCode());
        } catch (AiGatewayClient.UpstreamUnavailableException e) {
            writeError(response, P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        } catch (java.io.IOException e) {
            writeError(response, P04ErrorCode.BAD_REQUEST);
        }
    }

    /**
     * 两条 Agent SSE 的共用传输：授权 → 目标 URI → 净化头 → 专用事件流。
     *
     * <p>与 {@link #events} 逐项同形，唯一区别是方法/体由调用方给出。抽出来是为了让
     * "两条走完全一致的传输与交付回执语义"成为**结构性事实**，而不是两段复制粘贴
     * 各自漂移——本包的历史缺陷正是"两条路径各写一遍、其中一条漏了内部前缀"。
     */
    private void streamAgent(HttpServletRequest request, HttpServletResponse response, String action,
                             String method, String subPath, byte[] body) {
        try {
            GatewayAuthorizer.Authorized authorized = authorizer.authorize(request, action);
            URI target = targetUri(request, subPath);
            Map<String, String> headers = GatewayHeaders.sanitize(request);
            if (authorized.delegationToken() != null) {
                headers.put("Authorization", "Bearer " + authorized.delegationToken());
            }
            if (!properties.isLocalTransport()) {
                headers.put("X-P04-Service-Credential", properties.getServiceCredential());
            }
            headers.put(RequestId.HEADER, RequestId.currentOrEmpty());
            headers.put("Accept", "text/event-stream");
            if (body != null) {
                headers.put("Content-Type", "application/json;charset=UTF-8");
            }
            client.forwardEventStream(new AiGatewayClient.ForwardRequest(method, target, Map.copyOf(headers), body),
                    response, properties.getSseConnectTimeoutMillis(), properties.getSseIdleTimeoutMillis(),
                    properties.getSseMaxDurationMillis(), new AiGatewayClient.DeliveryAck(
                            URI.create(base() + AI_INTERNAL_PREFIX + "/authorization/deliveries/release"),
                            authorized.member().tenantId(), authorized.member().membershipId(),
                            properties.getServiceCredential()));
        } catch (P04Exception e) {
            writeError(response, e.errorCode());
        } catch (AiGatewayClient.UpstreamUnavailableException e) {
            writeError(response, P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    /**
     * 审批确认体的上限。
     *
     * <p>确认体只有 {@code conversationId/messageId/approved} 三个字段，取 64 KiB ——
     * 远大于任何合法确认体，又远小于通用转发的 2 MiB，使"超大确认体"在网关层就被拒绝，
     * 不进内层、不占内存。刻意不复用 {@code uploadMaxBytes}：把上传上限借给一个
     * 固定形状的小 JSON 会把界写松。
     */
    static final int CONFIRM_MAX_BYTES = 64 * 1024;

    @GetMapping("/runs/{runId}/events")
    public void events(@PathVariable String runId, HttpServletRequest request, HttpServletResponse response) {
        if (runId == null || runId.isBlank() || !runId.matches("[A-Za-z0-9_-]{1,64}")) {
            writeError(response, P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            return;
        }
        streamEvents(request, response, "run.stream", "/runs/" + runId + "/events");
    }

    /**
     * F13:2 AIFlow 运行起跑 SSE（{@code POST /api/ai/v1/flows/{uuid}/run}）。
     *
     * <p>与 Agent chat 同一条专用流通道：精确映射（先于 catch-all 生效）、独立建连/空闲/
     * 总时长预算、同一委托链（动作 {@code flow.run}）、逐帧交付回执。
     * 体上限 256 KiB（§4.3）；uuid 只接受 32 位小写 hex。
     */
    @PostMapping("/flows/{uuid}/run")
    public void flowRun(@PathVariable String uuid, HttpServletRequest request, HttpServletResponse response) {
        try {
            if (uuid == null || !uuid.matches("[0-9a-f]{32}")) {
                writeError(response, P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
                return;
            }
            long declared = request.getContentLengthLong();
            if (declared > FLOW_RUN_MAX_BYTES) {
                writeError(response, P04ErrorCode.BAD_REQUEST);
                return;
            }
            byte[] body;
            try (java.io.InputStream in = request.getInputStream()) {
                body = in.readNBytes(FLOW_RUN_MAX_BYTES + 1);
            }
            if (body.length > FLOW_RUN_MAX_BYTES) {
                writeError(response, P04ErrorCode.BAD_REQUEST);
                return;
            }
            streamAgent(request, response, "flow.run", "POST", "/flows/" + uuid + "/run", body);
        } catch (P04Exception e) {
            writeError(response, e.errorCode());
        } catch (AiGatewayClient.UpstreamUnavailableException e) {
            writeError(response, P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        } catch (java.io.IOException e) {
            writeError(response, P04ErrorCode.BAD_REQUEST);
        }
    }

    /**
     * F13:2 AIFlow 续流 SSE（D3，{@code GET /api/ai/v1/flows/runs/{runtimeUuid}/events}）。
     *
     * <p>动作 {@code flow.run.read}（owner-only 由内层账本复核）；不新增第三个动作。
     */
    @GetMapping("/flows/runs/{runtimeUuid}/events")
    public void flowRunEvents(@PathVariable String runtimeUuid, HttpServletRequest request,
                              HttpServletResponse response) {
        if (runtimeUuid == null || !runtimeUuid.matches("[0-9a-f]{32}")) {
            writeError(response, P04ErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            return;
        }
        streamEvents(request, response, "flow.run.read", "/flows/runs/" + runtimeUuid + "/events");
    }

    /** 通用事件流转发（run.stream / flow.run.read 共用同一传输语义）。 */
    private void streamEvents(HttpServletRequest request, HttpServletResponse response, String action,
                              String subPath) {
        try {
            GatewayAuthorizer.Authorized authorized = authorizer.authorize(request, action);
            URI target = targetUri(request, subPath);
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
                    properties.getSseMaxDurationMillis(), new AiGatewayClient.DeliveryAck(
                            URI.create(base() + AI_INTERNAL_PREFIX + "/authorization/deliveries/release"),
                            authorized.member().tenantId(), authorized.member().membershipId(),
                            properties.getServiceCredential()));
        } catch (P04Exception e) {
            writeError(response, e.errorCode());
        } catch (AiGatewayClient.UpstreamUnavailableException e) {
            writeError(response, P04ErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
    }

    /** F13:2 flow.run 请求体上限（§4.3：256 KiB）。 */
    static final int FLOW_RUN_MAX_BYTES = 256 * 1024;

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
            // 响应已提交（典型：SSE 已写出后逐帧交付回执失败）：不能改状态码，但不能静默
            // （R12 卡2 F-W6-P1 可观测化；此前此分支无任何痕迹）。
            log.warn("ai-gateway-stream error after commit: errorCode={} requestId={}",
                    code.name(), RequestId.currentOrEmpty());
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
