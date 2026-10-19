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

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaDtos;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.llm.LlmOutputParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F21:5 资产分析 / 摄影规则 / 表演指导。
 *
 * <p><b>只读输入。</b>analyze-assets 只读取当前项目的脚本、角色、场景，不写
 * {@code ai_short_drama_character/location}（F21:3/4 的 CRUD 不在本切片）；
 * 模型输出中的 ID 必须命中当前项目已存在的资产集合，否则整份输出拒绝。
 *
 * <p><b>持久化目标。</b>摄影规则与表演指导写入 V9 已有的
 * {@code ai_short_drama_storyboard.photography_rules/acting_notes} 列（F21:2 只做
 * 分镜生成/CRUD/视频，不做这两列的重生成）。没有分镜行时返回空结果，不臆造目标行。
 * 模型失败或校验失败时不做任何更新，旧有效值保持不变。
 */
@Service
public class ShortDramaAssetService {

    private static final String KIND_PHOTOGRAPHY = "photography";
    private static final String KIND_ACTING = "acting";

    private final ShortDramaRepository repository;
    private final ShortDramaWriteService writeService;
    private final DramaLlmClient llmClient;

    public ShortDramaAssetService(ShortDramaRepository repository,
                                  ShortDramaWriteService writeService,
                                  DramaLlmClient llmClient) {
        this.repository = repository;
        this.writeService = writeService;
        this.llmClient = llmClient;
    }

    public ShortDramaDtos.AssetAnalysisView analyzeAssets(DramaActor actor,
                                                          long projectId,
                                                          long scriptId) {
        DramaRows.ProjectRow project = requireProject(actor, projectId);
        DramaRows.ScriptRow script = requireScript(actor, projectId, scriptId);
        List<DramaRows.CharacterRow> characters =
                repository.listCharacters(projectId, actor.tenantId());
        List<DramaRows.LocationRow> locations =
                repository.listLocations(projectId, actor.tenantId());

        LlmOutputParser.Analysis analysis = llmClient.analyzeAssets(
                project.projectName(), script.scriptText(), script.outlineText(),
                characters, locations);

        Map<Long, DramaRows.CharacterRow> characterById = characters.stream()
                .collect(Collectors.toMap(DramaRows.CharacterRow::id, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<Long, DramaRows.LocationRow> locationById = locations.stream()
                .collect(Collectors.toMap(DramaRows.LocationRow::id, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));

        List<ShortDramaDtos.CharacterAnalysisView> characterViews = new ArrayList<>();
        for (LlmOutputParser.CharacterAnalysis item : analysis.characters()) {
            DramaRows.CharacterRow row = characterById.get(item.id());
            characterViews.add(new ShortDramaDtos.CharacterAnalysisView(
                    item.id(), row == null ? null : row.name(),
                    item.analysis(), item.visualNotes()));
        }
        List<ShortDramaDtos.LocationAnalysisView> locationViews = new ArrayList<>();
        for (LlmOutputParser.LocationAnalysis item : analysis.locations()) {
            DramaRows.LocationRow row = locationById.get(item.id());
            locationViews.add(new ShortDramaDtos.LocationAnalysisView(
                    item.id(), row == null ? null : row.name(),
                    item.analysis(), item.stagingNotes()));
        }

        return new ShortDramaDtos.AssetAnalysisView(
                projectId, scriptId, DramaInput.isoTime(script.updateTime()),
                inputHash(script), characterViews, locationViews,
                analysis.continuityNotes(), analysis.styleNotes());
    }

    public ShortDramaDtos.RulesResultView generatePhotographyRules(DramaActor actor,
                                                                   long projectId,
                                                                   long scriptId) {
        DramaRows.ProjectRow project = requireProject(actor, projectId);
        DramaRows.ScriptRow script = requireScript(actor, projectId, scriptId);
        List<DramaRows.StoryboardRow> storyboards =
                repository.listStoryboards(projectId, scriptId, actor.tenantId());
        if (storyboards.isEmpty()) {
            return emptyRules(projectId, scriptId, script, KIND_PHOTOGRAPHY);
        }
        List<DramaRows.CharacterRow> characters =
                repository.listCharacters(projectId, actor.tenantId());
        List<DramaRows.LocationRow> locations =
                repository.listLocations(projectId, actor.tenantId());
        Map<Integer, JsonNode> rules = llmClient.photographyRules(
                project.projectName(), script.scriptText(), storyboards, characters, locations);
        writeService.savePhotographyRules(actor, projectId, scriptId, storyboards, rules);
        return new ShortDramaDtos.RulesResultView(projectId, scriptId,
                DramaInput.isoTime(script.updateTime()), inputHash(script),
                KIND_PHOTOGRAPHY, sceneViews(rules));
    }

    public ShortDramaDtos.RulesResultView generateActingDirections(DramaActor actor,
                                                                   long projectId,
                                                                   long scriptId) {
        DramaRows.ProjectRow project = requireProject(actor, projectId);
        DramaRows.ScriptRow script = requireScript(actor, projectId, scriptId);
        List<DramaRows.StoryboardRow> storyboards =
                repository.listStoryboards(projectId, scriptId, actor.tenantId());
        if (storyboards.isEmpty()) {
            return emptyRules(projectId, scriptId, script, KIND_ACTING);
        }
        List<DramaRows.CharacterRow> characters =
                repository.listCharacters(projectId, actor.tenantId());
        List<DramaRows.LocationRow> locations =
                repository.listLocations(projectId, actor.tenantId());
        Map<Integer, JsonNode> rules = llmClient.actingDirections(
                project.projectName(), script.scriptText(), storyboards, characters, locations);
        writeService.saveActingDirections(actor, projectId, scriptId, storyboards, rules);
        return new ShortDramaDtos.RulesResultView(projectId, scriptId,
                DramaInput.isoTime(script.updateTime()), inputHash(script),
                KIND_ACTING, sceneViews(rules));
    }

    private static ShortDramaDtos.RulesResultView emptyRules(long projectId,
                                                             long scriptId,
                                                             DramaRows.ScriptRow script,
                                                             String kind) {
        return new ShortDramaDtos.RulesResultView(projectId, scriptId,
                DramaInput.isoTime(script.updateTime()), inputHash(script), kind, List.of());
    }

    private static List<ShortDramaDtos.SceneRuleView> sceneViews(Map<Integer, JsonNode> rules) {
        return rules.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ShortDramaDtos.SceneRuleView(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String inputHash(DramaRows.ScriptRow script) {
        return DramaInput.sha256(script.id() + "|"
                + (script.updateTime() == null ? "" : script.updateTime().toInstant())
                + "|" + (script.scriptText() == null ? "" : script.scriptText()));
    }

    private DramaRows.ProjectRow requireProject(DramaActor actor, long projectId) {
        return repository.findProject(projectId, actor.numericUserId(), actor.tenantId())
                .orElseThrow(() -> notFound("项目不存在"));
    }

    private DramaRows.ScriptRow requireScript(DramaActor actor, long projectId, long scriptId) {
        return repository.findScript(scriptId, projectId, actor.tenantId())
                .orElseThrow(() -> notFound("剧本不存在"));
    }

    private static P04AiException notFound(String message) {
        return new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, message);
    }
}
