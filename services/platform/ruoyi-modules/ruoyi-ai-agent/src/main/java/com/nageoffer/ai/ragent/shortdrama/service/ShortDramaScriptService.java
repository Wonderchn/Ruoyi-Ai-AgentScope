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

/**
 * F21:1 脚本用例（保存/生成/润色）。
 *
 * <p><b>生成不是保存。</b>旧实现里的 {@code /script} 是 saveScript，创意生成发生在
 * create-from-idea 服务链；本切片把二者拆成显式动作：
 * <ul>
 *   <li>{@code POST .../scripts} —— 手动保存/修改（{@code source_type=manual}）；</li>
 *   <li>{@code POST .../scripts/generate} —— 独立 LLM 生成，插入新脚本行
 *       （{@code source_type=ai}），不覆盖旧稿；</li>
 *   <li>{@code POST .../scripts/polish} —— 对目标脚本润色，成功后以读取时的
 *       {@code update_time} 做条件更新；模型失败/坏输出不写库，旧稿保留。</li>
 * </ul>
 * V9 脚本表没有版本列，本切片不假称已有：生成用"新增行"保留历史，
 * 润色用行级乐观并发守卫，冲突返回 409。
 */
@Service
public class ShortDramaScriptService {

    private static final String DEFAULT_SCRIPT_NAME = "剧本";
    private static final String DEFAULT_TONE = "短剧";
    private static final String SOURCE_MANUAL = "manual";
    private static final String SOURCE_AI = "ai";

    private final ShortDramaRepository repository;
    private final ShortDramaWriteService writeService;
    private final DramaLlmClient llmClient;

    public ShortDramaScriptService(ShortDramaRepository repository,
                                   ShortDramaWriteService writeService,
                                   DramaLlmClient llmClient) {
        this.repository = repository;
        this.writeService = writeService;
        this.llmClient = llmClient;
    }

    public List<ShortDramaDtos.ScriptView> listScripts(DramaActor actor, long projectId) {
        requireProject(actor, projectId);
        return repository.listScripts(projectId, actor.tenantId())
                .stream()
                .map(DramaViews::script)
                .toList();
    }

    public ShortDramaDtos.ScriptView saveScript(DramaActor actor,
                                                long projectId,
                                                ShortDramaDtos.SaveScriptRequest request) {
        if (request == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        requireProject(actor, projectId);

        if (request.scriptId() == null) {
            String scriptName = DramaInput.firstNonBlank(
                    DramaInput.optionalText(request.scriptName(), "scriptName",
                            DramaInput.MAX_SCRIPT_NAME),
                    DEFAULT_SCRIPT_NAME);
            String scriptText = DramaInput.optionalText(
                    request.scriptText(), "scriptText", DramaInput.MAX_SCRIPT_TEXT);
            String outlineText = DramaInput.optionalText(
                    request.outlineText(), "outlineText", DramaInput.MAX_OUTLINE_TEXT);
            requireScriptBody(scriptText, outlineText);
            String tone = DramaInput.optionalText(request.tone(), "tone", DramaInput.MAX_TONE);

            long scriptId = repository.insertScript(projectId, actor.tenantId(), scriptName,
                    scriptText, outlineText, tone, SOURCE_MANUAL);
            return DramaViews.script(requireScript(actor, projectId, scriptId));
        }

        DramaRows.ScriptRow existing = requireScript(actor, projectId, request.scriptId());
        String scriptName = request.scriptName() == null
                ? existing.scriptName()
                : DramaInput.requireText(request.scriptName(), "scriptName",
                        DramaInput.MAX_SCRIPT_NAME);
        String scriptText = request.scriptText() == null
                ? existing.scriptText()
                : DramaInput.optionalText(request.scriptText(), "scriptText",
                        DramaInput.MAX_SCRIPT_TEXT);
        String outlineText = request.outlineText() == null
                ? existing.outlineText()
                : DramaInput.optionalText(request.outlineText(), "outlineText",
                        DramaInput.MAX_OUTLINE_TEXT);
        String tone = request.tone() == null
                ? existing.tone()
                : DramaInput.optionalText(request.tone(), "tone", DramaInput.MAX_TONE);
        requireScriptBody(scriptText, outlineText);

        int updated = repository.updateScriptManual(existing.id(), projectId, actor.tenantId(),
                scriptName, scriptText, outlineText, tone);
        if (updated == 0) {
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "脚本不存在");
        }
        return DramaViews.script(requireScript(actor, projectId, existing.id()));
    }

    public ShortDramaDtos.ScriptView generateScript(DramaActor actor,
                                                    long projectId,
                                                    ShortDramaDtos.GenerateScriptRequest request) {
        if (request == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        DramaRows.ProjectRow project = requireProject(actor, projectId);
        String idea = DramaInput.firstNonBlank(
                DramaInput.optionalText(request.idea(), "idea", DramaInput.MAX_IDEA),
                project.description());
        if (idea == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                    "idea 不能为空（项目也没有可用描述）");
        }
        String projectName = DramaInput.firstNonBlank(request.projectName(), project.projectName());
        String tone = DramaInput.optionalText(request.tone(), "tone", DramaInput.MAX_TONE);

        LlmOutputParser.ScriptEnvelope envelope = llmClient.generateScript(projectName, idea, tone);
        String scriptName = DramaInput.firstNonBlank(envelope.scriptName(), DEFAULT_SCRIPT_NAME);
        String scriptTone = DramaInput.firstNonBlank(envelope.tone(), tone, DEFAULT_TONE);

        long scriptId = repository.insertScript(projectId, actor.tenantId(), scriptName,
                envelope.scriptText(), envelope.outlineText(), scriptTone, SOURCE_AI);
        return DramaViews.script(requireScript(actor, projectId, scriptId));
    }

    public ShortDramaDtos.ScriptView polishScript(DramaActor actor,
                                                  long projectId,
                                                  ShortDramaDtos.PolishScriptRequest request) {
        DramaRows.ProjectRow project = requireProject(actor, projectId);
        Long requestedId = request == null ? null : request.scriptId();
        DramaRows.ScriptRow script = requestedId == null
                ? repository.findLatestScript(projectId, actor.tenantId())
                        .orElseThrow(() -> notFound("剧本不存在"))
                : repository.findScript(requestedId, projectId, actor.tenantId())
                        .orElseThrow(() -> notFound("剧本不存在"));

        String input = DramaInput.firstNonBlank(
                script.scriptText(), script.outlineText(), project.description());
        if (input == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                    "脚本没有可润色的正文或大纲");
        }

        LlmOutputParser.ScriptEnvelope envelope =
                llmClient.polishScript(project.projectName(), input, script.tone());
        ShortDramaWriteService.ScriptDraft draft = new ShortDramaWriteService.ScriptDraft(
                DramaInput.firstNonBlank(envelope.scriptName(), script.scriptName(),
                        DEFAULT_SCRIPT_NAME),
                DramaInput.firstNonBlank(envelope.scriptText(), script.scriptText()),
                DramaInput.firstNonBlank(envelope.outlineText(), script.outlineText()),
                DramaInput.firstNonBlank(envelope.tone(), script.tone(), DEFAULT_TONE),
                DramaInput.firstNonBlank(script.sourceType(), SOURCE_MANUAL));

        DramaRows.ScriptRow updated =
                writeService.polishScript(actor, projectId, script, draft);
        return DramaViews.script(updated);
    }

    private DramaRows.ProjectRow requireProject(DramaActor actor, long projectId) {
        return repository.findProject(projectId, actor.numericUserId(), actor.tenantId())
                .orElseThrow(() -> notFound("项目不存在"));
    }

    private DramaRows.ScriptRow requireScript(DramaActor actor, long projectId, long scriptId) {
        return repository.findScript(scriptId, projectId, actor.tenantId())
                .orElseThrow(() -> notFound("剧本不存在"));
    }

    private static void requireScriptBody(String scriptText, String outlineText) {
        if ((scriptText == null || scriptText.isBlank())
                && (outlineText == null || outlineText.isBlank())) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                    "scriptText 与 outlineText 至少需要一个");
        }
    }

    private static P04AiException notFound(String message) {
        return new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, message);
    }
}
