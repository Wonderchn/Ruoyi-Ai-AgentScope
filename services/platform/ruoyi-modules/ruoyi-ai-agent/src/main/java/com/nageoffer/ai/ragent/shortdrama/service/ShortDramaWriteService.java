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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 短剧写面的事务边界。
 *
 * <p><b>为什么单独一层。</b>LLM 调用可能持续数十秒，不能把模型往返包在数据库事务里。
 * 编排层（项目/脚本/资产服务）先完成模型调用与校验，再把"已经确定的写入集合"交给本类；
 * 本类的每个方法都是短事务，且失败时整体回滚（例如分镜规则逐行更新到一半失败）。
 */
@Service
public class ShortDramaWriteService {

    private final ShortDramaRepository repository;
    private final ObjectMapper objectMapper;

    public ShortDramaWriteService(ShortDramaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public CreatedIds createProjectWithScript(DramaActor actor,
                                              ProjectDraft project,
                                              ScriptDraft script) {
        long projectId = repository.insertProject(
                actor.numericUserId(),
                actor.tenantId(),
                project.projectName(),
                project.description(),
                project.status(),
                project.artStyle(),
                project.aspectRatio());
        long scriptId = repository.insertScript(
                projectId,
                actor.tenantId(),
                script.scriptName(),
                script.scriptText(),
                script.outlineText(),
                script.tone(),
                script.sourceType());
        return new CreatedIds(projectId, scriptId);
    }

    @Transactional(rollbackFor = Exception.class)
    public DramaRows.ScriptRow polishScript(DramaActor actor,
                                            long projectId,
                                            DramaRows.ScriptRow observed,
                                            ScriptDraft draft) {
        int updated = repository.updateScriptPolished(
                observed.id(),
                projectId,
                actor.tenantId(),
                observed.updateTime(),
                draft.scriptName(),
                draft.scriptText(),
                draft.outlineText(),
                draft.tone());
        if (updated == 0) {
            throw new P04AiException(P04AiErrorCode.RESOURCE_VERSION_CONFLICT,
                    "脚本已被其他操作修改，本次润色未覆盖，请重试");
        }
        return repository.findScript(observed.id(), projectId, actor.tenantId())
                .orElseThrow(() -> new P04AiException(
                        P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "脚本不存在"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteProject(DramaActor actor, long projectId) {
        long userId = actor.numericUserId();
        repository.findProject(projectId, userId, actor.tenantId())
                .orElseThrow(() -> new P04AiException(
                        P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "项目不存在"));
        repository.deleteProjectChildren(projectId, actor.tenantId());
        int deleted = repository.deleteProject(projectId, userId, actor.tenantId());
        if (deleted == 0) {
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "项目不存在");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void savePhotographyRules(DramaActor actor,
                                     long projectId,
                                     long scriptId,
                                     List<DramaRows.StoryboardRow> rows,
                                     Map<Integer, JsonNode> rulesByScene) {
        for (DramaRows.StoryboardRow row : rows) {
            JsonNode rules = rulesByScene.get(row.sceneNo());
            if (rules == null) {
                throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR,
                        "模型输出缺少场次 " + row.sceneNo());
            }
            int updated = repository.updateStoryboardPhotography(
                    row.id(), projectId, scriptId, actor.tenantId(), json(rules));
            if (updated == 0) {
                throw new P04AiException(P04AiErrorCode.RESOURCE_VERSION_CONFLICT,
                        "分镜已变化，本次摄影规则未覆盖，请重试");
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveActingDirections(DramaActor actor,
                                     long projectId,
                                     long scriptId,
                                     List<DramaRows.StoryboardRow> rows,
                                     Map<Integer, JsonNode> rulesByScene) {
        for (DramaRows.StoryboardRow row : rows) {
            JsonNode rules = rulesByScene.get(row.sceneNo());
            if (rules == null) {
                throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR,
                        "模型输出缺少场次 " + row.sceneNo());
            }
            int updated = repository.updateStoryboardActing(
                    row.id(), projectId, scriptId, actor.tenantId(), json(rules));
            if (updated == 0) {
                throw new P04AiException(P04AiErrorCode.RESOURCE_VERSION_CONFLICT,
                        "分镜已变化，本次表演指导未覆盖，请重试");
            }
        }
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR, "模型输出序列化失败");
        }
    }

    public record ProjectDraft(String projectName,
                               String description,
                               String status,
                               String artStyle,
                               String aspectRatio) {
    }

    public record ScriptDraft(String scriptName,
                              String scriptText,
                              String outlineText,
                              String tone,
                              String sourceType) {
    }

    public record CreatedIds(long projectId, long scriptId) {
    }
}
