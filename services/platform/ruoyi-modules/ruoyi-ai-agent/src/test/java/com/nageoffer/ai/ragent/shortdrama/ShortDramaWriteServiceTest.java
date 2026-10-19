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

package com.nageoffer.ai.ragent.shortdrama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import com.nageoffer.ai.ragent.shortdrama.service.DramaActor;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaWriteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F21 写面事务用例：润色 CAS 冲突 409、级联删除先核属主、分镜规则逐行写入的冲突传播。
 */
@Tag("dev")
class ShortDramaWriteServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ShortDramaRepository repository = mock(ShortDramaRepository.class);
    private final ShortDramaWriteService service = new ShortDramaWriteService(repository, mapper);
    private final DramaActor actor = DramaTestSupport.actor();

    @Test
    @DisplayName("润色 CAS：条件更新 0 行 → 409，且不回读/不覆盖")
    void polishConflictWhenCasMisses() {
        DramaRows.ScriptRow observed = DramaTestSupport.scriptRow(20, 1);
        when(repository.updateScriptPolished(anyLong(), anyLong(), anyString(), any(),
                anyString(), anyString(), anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.polishScript(actor, 1, observed,
                new ShortDramaWriteService.ScriptDraft("润色稿", "润色正文", "润色大纲", "短剧", "manual")))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_VERSION_CONFLICT);
        verify(repository, never()).findScript(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("润色 CAS：条件更新 1 行 → 回读并返回更新后的行")
    void polishReturnsUpdatedRow() {
        DramaRows.ScriptRow observed = DramaTestSupport.scriptRow(20, 1);
        DramaRows.ScriptRow updated = new DramaRows.ScriptRow(20, 1, "润色稿",
                "润色正文", "润色大纲", "短剧", "manual",
                DramaTestSupport.time(), DramaTestSupport.time(), DramaTestSupport.TENANT);
        when(repository.updateScriptPolished(anyLong(), anyLong(), anyString(), any(),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(repository.findScript(20L, 1L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(updated));

        DramaRows.ScriptRow result = service.polishScript(actor, 1, observed,
                new ShortDramaWriteService.ScriptDraft("润色稿", "润色正文", "润色大纲", "短剧", "manual"));
        assertThat(result.scriptName()).isEqualTo("润色稿");
        verify(repository).findScript(20L, 1L, DramaTestSupport.TENANT);
    }

    @Test
    @DisplayName("删除：越权项目 404，不进入级联删除")
    void deleteRejectsForeignProjectBeforeCascade() {
        when(repository.findProject(9L, 42L, DramaTestSupport.TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteProject(actor, 9))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        verify(repository, never()).deleteProjectChildren(anyLong(), anyString());
        verify(repository, never()).deleteProject(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("删除：属主校验通过后先删子表再删项目")
    void deleteCascadesAfterOwnership() {
        when(repository.findProject(1L, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.deleteProject(1L, 42L, DramaTestSupport.TENANT)).thenReturn(1);

        service.deleteProject(actor, 1);
        verify(repository).deleteProjectChildren(1L, DramaTestSupport.TENANT);
        verify(repository).deleteProject(1L, 42L, DramaTestSupport.TENANT);
    }

    @Test
    @DisplayName("摄影规则：第二行条件更新 0 行 → 409（事务回滚，不留半套规则）")
    void photographyConflictPropagates() {
        DramaRows.StoryboardRow row1 = DramaTestSupport.storyboardRow(50, 1, 20, 1);
        DramaRows.StoryboardRow row2 = DramaTestSupport.storyboardRow(51, 1, 20, 2);
        JsonNode rules1 = mapper.createObjectNode().put("shotType", "中景");
        JsonNode rules2 = mapper.createObjectNode().put("shotType", "远景");
        when(repository.updateStoryboardPhotography(50L, 1L, 20L, DramaTestSupport.TENANT,
                "{\"shotType\":\"中景\"}")).thenReturn(1);
        when(repository.updateStoryboardPhotography(51L, 1L, 20L, DramaTestSupport.TENANT,
                "{\"shotType\":\"远景\"}")).thenReturn(0);

        Map<Integer, JsonNode> rules = new LinkedHashMap<>();
        rules.put(1, rules1);
        rules.put(2, rules2);

        assertThatThrownBy(() -> service.savePhotographyRules(actor, 1, 20,
                List.of(row1, row2), rules))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_VERSION_CONFLICT);
        verify(repository).updateStoryboardPhotography(50L, 1L, 20L,
                DramaTestSupport.TENANT, "{\"shotType\":\"中景\"}");
        verify(repository).updateStoryboardPhotography(51L, 1L, 20L,
                DramaTestSupport.TENANT, "{\"shotType\":\"远景\"}");
    }

    @Test
    @DisplayName("表演指导：逐行写入，JSON 原样落列")
    void actingWritesEachScene() {
        DramaRows.StoryboardRow row = DramaTestSupport.storyboardRow(50, 1, 20, 1);
        JsonNode notes = mapper.createObjectNode().put("emotion", "克制");
        when(repository.updateStoryboardActing(50L, 1L, 20L, DramaTestSupport.TENANT,
                "{\"emotion\":\"克制\"}")).thenReturn(1);

        service.saveActingDirections(actor, 1, 20, List.of(row), Map.of(1, notes));
        verify(repository).updateStoryboardActing(50L, 1L, 20L,
                DramaTestSupport.TENANT, "{\"emotion\":\"克制\"}");
    }
}
