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

package com.nageoffer.ai.ragent.shortdrama.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.shortdrama.service.DramaAccess;
import com.nageoffer.ai.ragent.shortdrama.service.DramaActor;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaAssetService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaProjectService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaScriptService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaStreamService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.function.Function;

/**
 * F21:0/1/5 短剧内层控制器（类级 {@code /internal/ai/v1}）。
 *
 * <p><b>公开面。</b>经网关白名单为 {@code /api/ai/v1/short-drama/**}。外部不能直接访问
 * {@code /internal/ai/v1/**}（内层边界过滤器关闭该前缀的直接入口）。
 *
 * <p><b>授权不在本层做功能权限判定。</b>网关按 ROUTES 的 canonical 动作判定平台权限；
 * 本层只复核委托主体确实持有该动作 scope（{@link DramaAccess}），并负责资源边界
 * （tenant + 属主 user）。两层同时成立才放行，缺一即拒绝。
 *
 * <p><b>信封必须整数 code。</b>所有 JSON 返回 {@link ApiEnvelope}（成功 200）。
 * 失败由 {@code AiInternalExceptionResolver} 映射为 {@code HTTP status == body.code}；
 * 本层不使用字符串 code 的旧 {@code Result}，否则网关会以 "missing envelope code" 收敛成 503。
 *
 * <p><b>GET 必须带交付回执。</b>网关 GET 走字节分支，200 响应同时要求两个回执头，
 * 缺一即 503。本类在数据读取成功后铸造许可；读取失败时尚未铸造，不留下 ACTIVE 许可。
 * 写面走 JSON 分支，不铸造回执（在 JSON 分支铸造会泄漏 ACTIVE 许可）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
public class ShortDramaController {

    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final ShortDramaProjectService projectService;
    private final ShortDramaScriptService scriptService;
    private final ShortDramaAssetService assetService;
    private final ShortDramaStreamService streamService;
    private final DeliveryPermits deliveryPermits;
    private final ObjectMapper objectMapper;

    public ShortDramaController(ShortDramaProjectService projectService,
                                ShortDramaScriptService scriptService,
                                ShortDramaAssetService assetService,
                                ShortDramaStreamService streamService,
                                DeliveryPermits deliveryPermits,
                                ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.scriptService = scriptService;
        this.assetService = assetService;
        this.streamService = streamService;
        this.deliveryPermits = deliveryPermits;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ F21:0 project

    @GetMapping("/short-drama/projects")
    public ResponseEntity<ApiEnvelope<List<ShortDramaDtos.ProjectView>>> listProjects() {
        return read("drama:projects", projectService::listProjects);
    }

    @PostMapping("/short-drama/projects")
    public ApiEnvelope<ShortDramaDtos.ProjectDetailView> createProject(
            @RequestBody ShortDramaDtos.CreateProjectRequest request) {
        return write(DramaAccess.ACTION_WRITE, actor -> projectService.createProject(actor, request));
    }

    @GetMapping("/short-drama/projects/{projectId}")
    public ResponseEntity<ApiEnvelope<ShortDramaDtos.ProjectDetailView>> getProject(
            @PathVariable long projectId) {
        return read("drama:project:" + projectId,
                actor -> projectService.getProject(actor, projectId));
    }

    @PutMapping("/short-drama/projects/{projectId}")
    public ApiEnvelope<ShortDramaDtos.ProjectDetailView> updateProject(
            @PathVariable long projectId,
            @RequestBody ShortDramaDtos.UpdateProjectRequest request) {
        return write(DramaAccess.ACTION_WRITE,
                actor -> projectService.updateProject(actor, projectId, request));
    }

    @DeleteMapping("/short-drama/projects/{projectId}")
    public ApiEnvelope<Void> deleteProject(@PathVariable long projectId) {
        return write(DramaAccess.ACTION_WRITE, actor -> {
            projectService.deleteProject(actor, projectId);
            return null;
        });
    }

    @PostMapping("/short-drama/projects/create-from-idea")
    public ApiEnvelope<ShortDramaDtos.ProjectDetailView> createFromIdea(
            @RequestBody ShortDramaDtos.CreateFromIdeaRequest request) {
        return write(DramaAccess.ACTION_GENERATE,
                actor -> projectService.createFromIdea(actor, request));
    }

    /**
     * SSE 流式创建。同步阶段的校验/幂等冲突在返回 emitter 之前抛出，由异常解析器
     * 返回整数信封；一旦开始模型调用，进度经 {@link SseStreamSink} 逐帧推送。
     */
    @PostMapping(value = "/short-drama/projects/create-from-idea/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createFromIdeaStream(
            @RequestBody ShortDramaDtos.CreateFromIdeaRequest request) {
        ExecutionPrincipal principal = DramaAccess.require(DramaAccess.ACTION_GENERATE);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        streamService.streamCreateFromIdea(DramaActor.from(principal), request,
                new SseStreamSink(emitter, objectMapper));
        return emitter;
    }

    // ------------------------------------------------------------------ F21:1 script

    @GetMapping("/short-drama/projects/{projectId}/scripts")
    public ResponseEntity<ApiEnvelope<List<ShortDramaDtos.ScriptView>>> listScripts(
            @PathVariable long projectId) {
        return read("drama:project:" + projectId + ":scripts",
                actor -> scriptService.listScripts(actor, projectId));
    }

    @PostMapping("/short-drama/projects/{projectId}/scripts")
    public ApiEnvelope<ShortDramaDtos.ScriptView> saveScript(
            @PathVariable long projectId,
            @RequestBody ShortDramaDtos.SaveScriptRequest request) {
        return write(DramaAccess.ACTION_WRITE,
                actor -> scriptService.saveScript(actor, projectId, request));
    }

    @PutMapping("/short-drama/projects/{projectId}/scripts/{scriptId}")
    public ApiEnvelope<ShortDramaDtos.ScriptView> updateScript(
            @PathVariable long projectId,
            @PathVariable long scriptId,
            @RequestBody ShortDramaDtos.SaveScriptRequest request) {
        ShortDramaDtos.SaveScriptRequest update = new ShortDramaDtos.SaveScriptRequest(
                scriptId, request.scriptName(), request.scriptText(),
                request.outlineText(), request.tone());
        return write(DramaAccess.ACTION_WRITE,
                actor -> scriptService.saveScript(actor, projectId, update));
    }

    @PostMapping("/short-drama/projects/{projectId}/scripts/generate")
    public ApiEnvelope<ShortDramaDtos.ScriptView> generateScript(
            @PathVariable long projectId,
            @RequestBody ShortDramaDtos.GenerateScriptRequest request) {
        return write(DramaAccess.ACTION_GENERATE,
                actor -> scriptService.generateScript(actor, projectId, request));
    }

    @PostMapping("/short-drama/projects/{projectId}/scripts/polish")
    public ApiEnvelope<ShortDramaDtos.ScriptView> polishScript(
            @PathVariable long projectId,
            @RequestBody(required = false) ShortDramaDtos.PolishScriptRequest request) {
        ShortDramaDtos.PolishScriptRequest body = request == null
                ? new ShortDramaDtos.PolishScriptRequest(null) : request;
        return write(DramaAccess.ACTION_GENERATE,
                actor -> scriptService.polishScript(actor, projectId, body));
    }

    // ------------------------------------------------------------------ F21:5 assets

    @PostMapping("/short-drama/projects/{projectId}/analyze-assets")
    public ApiEnvelope<ShortDramaDtos.AssetAnalysisView> analyzeAssets(
            @PathVariable long projectId,
            @RequestBody ShortDramaDtos.ScriptActionRequest request) {
        return write(DramaAccess.ACTION_GENERATE,
                actor -> assetService.analyzeAssets(actor, projectId, requireScriptId(request)));
    }

    @PostMapping("/short-drama/projects/{projectId}/photography-rules")
    public ApiEnvelope<ShortDramaDtos.RulesResultView> generatePhotographyRules(
            @PathVariable long projectId,
            @RequestBody ShortDramaDtos.ScriptActionRequest request) {
        return write(DramaAccess.ACTION_GENERATE,
                actor -> assetService.generatePhotographyRules(actor, projectId, requireScriptId(request)));
    }

    @PostMapping("/short-drama/projects/{projectId}/acting-directions")
    public ApiEnvelope<ShortDramaDtos.RulesResultView> generateActingDirections(
            @PathVariable long projectId,
            @RequestBody ShortDramaDtos.ScriptActionRequest request) {
        return write(DramaAccess.ACTION_GENERATE,
                actor -> assetService.generateActingDirections(actor, projectId, requireScriptId(request)));
    }

    // ------------------------------------------------------------------ helpers

    private long requireScriptId(ShortDramaDtos.ScriptActionRequest request) {
        if (request == null || request.scriptId() == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "scriptId 不能为空");
        }
        return request.scriptId();
    }

    private <T> ResponseEntity<ApiEnvelope<T>> read(String resource,
                                                    Function<DramaActor, T> body) {
        ExecutionPrincipal principal = DramaAccess.require(DramaAccess.ACTION_READ);
        T data = body.apply(DramaActor.from(principal));
        DeliveryPermits.Permit permit = deliveryPermits.enter(
                principal, DramaAccess.ACTION_READ, resource);
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(data));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    private <T> ApiEnvelope<T> write(String action, Function<DramaActor, T> body) {
        ExecutionPrincipal principal = DramaAccess.require(action);
        return ApiEnvelope.ok(body.apply(DramaActor.from(principal)));
    }
}
