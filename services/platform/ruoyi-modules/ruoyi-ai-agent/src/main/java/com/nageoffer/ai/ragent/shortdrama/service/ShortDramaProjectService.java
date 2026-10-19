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

package com.nageoffer.ai.ragent.shortdrama.service;

import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaDtos;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.llm.LlmOutputParser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * F21:0 项目用例（列表/详情/手动创建/编辑/删除/从创意创建）。
 *
 * <p>所有读写都以 {@link DramaActor} 的 tenant + user 双谓词限域；越权与不存在
 * 统一 404，不区分"存在但不属于你"。
 */
@Service
public class ShortDramaProjectService {

    private static final String DEFAULT_PROJECT_NAME = "短剧项目";
    private static final String DEFAULT_ART_STYLE = "realistic";
    private static final String DEFAULT_ASPECT_RATIO = "9:16";
    private static final String DEFAULT_STATUS = "draft";
    private static final String DEFAULT_SCRIPT_NAME = "剧本";
    private static final String DEFAULT_TONE = "短剧";
    private static final String SOURCE_AI = "ai";

    private final ShortDramaRepository repository;
    private final ShortDramaWriteService writeService;
    private final DramaLlmClient llmClient;
    private final DramaIdempotencyStore idempotencyStore;

    public ShortDramaProjectService(ShortDramaRepository repository,
                                    ShortDramaWriteService writeService,
                                    DramaLlmClient llmClient,
                                    DramaIdempotencyStore idempotencyStore) {
        this.repository = repository;
        this.writeService = writeService;
        this.llmClient = llmClient;
        this.idempotencyStore = idempotencyStore;
    }

    public List<ShortDramaDtos.ProjectView> listProjects(DramaActor actor) {
        return repository.listProjects(actor.numericUserId(), actor.tenantId())
                .stream()
                .map(DramaViews::project)
                .toList();
    }

    public ShortDramaDtos.ProjectDetailView getProject(DramaActor actor, long projectId) {
        DramaRows.ProjectRow project = requireProject(actor, projectId);
        List<ShortDramaDtos.ScriptView> scripts = repository
                .listScripts(projectId, actor.tenantId())
                .stream()
                .map(DramaViews::script)
                .toList();
        List<ShortDramaDtos.CharacterView> characters = repository
                .listCharacters(projectId, actor.tenantId())
                .stream()
                .map(DramaViews::character)
                .toList();
        List<ShortDramaDtos.LocationView> locations = repository
                .listLocations(projectId, actor.tenantId())
                .stream()
                .map(DramaViews::location)
                .toList();
        return new ShortDramaDtos.ProjectDetailView(
                DramaViews.project(project),
                scripts.isEmpty() ? null : scripts.get(0),
                scripts,
                characters,
                locations,
                characters.size(),
                locations.size(),
                repository.countStoryboards(projectId, actor.tenantId()));
    }

    public ShortDramaDtos.ProjectDetailView createProject(DramaActor actor,
                                                          ShortDramaDtos.CreateProjectRequest request) {
        if (request == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String projectName = DramaInput.requireText(
                request.projectName(), "projectName", DramaInput.MAX_PROJECT_NAME);
        String description = DramaInput.optionalText(
                request.description(), "description", DramaInput.MAX_DESCRIPTION);
        String artStyle = DramaInput.artStyleOr(request.artStyle(), DEFAULT_ART_STYLE);
        String aspectRatio = DramaInput.aspectRatioOr(request.aspectRatio(), DEFAULT_ASPECT_RATIO);
        String status = DramaInput.statusOr(request.status(), DEFAULT_STATUS);

        long projectId = repository.insertProject(actor.numericUserId(), actor.tenantId(),
                projectName, description, status, artStyle, aspectRatio);
        return getProject(actor, projectId);
    }

    public ShortDramaDtos.ProjectDetailView updateProject(DramaActor actor,
                                                          long projectId,
                                                          ShortDramaDtos.UpdateProjectRequest request) {
        if (request == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        DramaRows.ProjectRow existing = requireProject(actor, projectId);
        String projectName = request.projectName() == null
                ? existing.projectName()
                : DramaInput.requireText(request.projectName(), "projectName",
                        DramaInput.MAX_PROJECT_NAME);
        String description = request.description() == null
                ? existing.description()
                : DramaInput.optionalText(request.description(), "description",
                        DramaInput.MAX_DESCRIPTION);
        String status = DramaInput.statusOr(request.status(), existing.status());
        String artStyle = DramaInput.artStyleOr(request.artStyle(), existing.artStyle());

        int updated = repository.updateProject(projectId, actor.numericUserId(), actor.tenantId(),
                projectName, description, status, artStyle);
        if (updated == 0) {
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "项目不存在");
        }
        return getProject(actor, projectId);
    }

    public void deleteProject(DramaActor actor, long projectId) {
        // 编排层先做一次属主/租户存在性检查：写服务仍会在事务内复核（防并发删除），
        // 但这里提前拒绝可以让越权请求在进入写事务之前就得到统一的 404。
        requireProject(actor, projectId);
        writeService.deleteProject(actor, projectId);
    }

    /**
     * 从创意创建：一次模型调用 → 校验 → 项目 + 脚本短事务落库。
     *
     * <p>幂等只在进程内（见 {@link DramaIdempotencyStore} 的边界说明）；失败/中断会释放
     * 登记，允许用户重试，不会留下"永远处理中"的 key。
     */
    public ShortDramaDtos.ProjectDetailView createFromIdea(DramaActor actor,
                                                           ShortDramaDtos.CreateFromIdeaRequest request) {
        if (request == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String idea = DramaInput.requireText(request.idea(), "idea", DramaInput.MAX_IDEA);
        String requestedName = DramaInput.optionalText(request.projectName(), "projectName",
                DramaInput.MAX_PROJECT_NAME);
        String key = DramaInput.idempotencyKey(request.idempotencyKey());

        if (key != null) {
            Optional<Long> completed = idempotencyStore.completed(actor, key);
            if (completed.isPresent()) {
                return getProject(actor, completed.get());
            }
            if (!idempotencyStore.claim(actor, key)) {
                throw new P04AiException(P04AiErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "相同幂等键的请求正在处理中");
            }
        }

        try {
            String promptName = DramaInput.firstNonBlank(requestedName, DEFAULT_PROJECT_NAME);
            LlmOutputParser.ScriptEnvelope envelope =
                    llmClient.generateScript(promptName, idea, null);

            String projectName = DramaInput.firstNonBlank(
                    requestedName, envelope.projectName(), DEFAULT_PROJECT_NAME);
            String artStyle = DramaInput.artStyleOr(request.artStyle(), DEFAULT_ART_STYLE);
            String aspectRatio = DramaInput.aspectRatioOr(request.aspectRatio(), DEFAULT_ASPECT_RATIO);
            ShortDramaWriteService.ProjectDraft projectDraft =
                    new ShortDramaWriteService.ProjectDraft(projectName,
                            DramaInput.firstNonBlank(envelope.description()),
                            DEFAULT_STATUS, artStyle, aspectRatio);
            ShortDramaWriteService.ScriptDraft scriptDraft =
                    new ShortDramaWriteService.ScriptDraft(
                            DramaInput.firstNonBlank(envelope.scriptName(), DEFAULT_SCRIPT_NAME),
                            envelope.scriptText(),
                            envelope.outlineText(),
                            DramaInput.firstNonBlank(envelope.tone(), DEFAULT_TONE),
                            SOURCE_AI);

            ShortDramaWriteService.CreatedIds created =
                    writeService.createProjectWithScript(actor, projectDraft, scriptDraft);
            if (key != null) {
                idempotencyStore.complete(actor, key, created.projectId());
            }
            return getProject(actor, created.projectId());
        } catch (RuntimeException e) {
            if (key != null) {
                idempotencyStore.release(actor, key);
            }
            throw e;
        }
    }

    private DramaRows.ProjectRow requireProject(DramaActor actor, long projectId) {
        return repository.findProject(projectId, actor.numericUserId(), actor.tenantId())
                .orElseThrow(() -> new P04AiException(
                        P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "项目不存在"));
    }
}
