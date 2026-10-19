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
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaDtos;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.llm.LlmOutputParser;
import com.nageoffer.ai.ragent.shortdrama.service.DramaActor;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaAssetService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaWriteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
 * F21:5 红/绿判据：资产分析只读限域、摄影/表演规则的持久化与失败不覆盖。
 */
@Tag("dev")
class ShortDramaAssetServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ShortDramaRepository repository = mock(ShortDramaRepository.class);
    private final ShortDramaWriteService writeService = mock(ShortDramaWriteService.class);
    private final DramaLlmClient llmClient = mock(DramaLlmClient.class);
    private final ShortDramaAssetService service =
            new ShortDramaAssetService(repository, writeService, llmClient);

    private final DramaActor actor = DramaTestSupport.actor();

    private void projectAndScript() {
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.findScript(20, 1, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.scriptRow(20, 1)));
    }

    @Test
    @DisplayName("资产分析：只读取当前项目角色/场景，输出带来源引用与输入指纹")
    void analyzeAssetsReturnsSourceReferences() {
        projectAndScript();
        when(repository.listCharacters(1, DramaTestSupport.TENANT))
                .thenReturn(List.of(DramaTestSupport.characterRow(30, 1)));
        when(repository.listLocations(1, DramaTestSupport.TENANT))
                .thenReturn(List.of(DramaTestSupport.locationRow(40, 1)));
        when(llmClient.analyzeAssets(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new LlmOutputParser.Analysis(
                        List.of(new LlmOutputParser.CharacterAnalysis(30, "主角", "短发")),
                        List.of(new LlmOutputParser.LocationAnalysis(40, "主场", "中央")),
                        "服装连续", "冷色"));

        ShortDramaDtos.AssetAnalysisView view = service.analyzeAssets(actor, 1, 20);
        assertThat(view.scriptId()).isEqualTo(20L);
        assertThat(view.scriptUpdateTime()).isNotBlank();
        assertThat(view.inputHash()).hasSize(64);
        assertThat(view.characters()).singleElement()
                .satisfies(c -> {
                    assertThat(c.id()).isEqualTo(30L);
                    assertThat(c.name()).isEqualTo("角色-30");
                });
        assertThat(view.locations()).singleElement()
                .satisfies(l -> assertThat(l.name()).isEqualTo("场景-40"));
    }

    @Test
    @DisplayName("资产分析：跨项目脚本 → 404，不调用模型")
    void analyzeRejectsForeignScript() {
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.findScript(99, 1, DramaTestSupport.TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.analyzeAssets(actor, 1, 99))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        verify(llmClient, never()).analyzeAssets(anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("摄影规则：没有分镜行 → 空结果，不调用模型、不写库")
    void photographyWithoutStoryboardsSkipsModel() {
        projectAndScript();
        when(repository.listStoryboards(1, 20, DramaTestSupport.TENANT)).thenReturn(List.of());
        ShortDramaDtos.RulesResultView view = service.generatePhotographyRules(actor, 1, 20);
        assertThat(view.rules()).isEmpty();
        verify(llmClient, never()).photographyRules(anyString(), anyString(), any(), any(), any());
        verify(writeService, never()).savePhotographyRules(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("摄影规则：校验通过后逐行持久化")
    void photographyPersistsValidatedRules() {
        projectAndScript();
        DramaRows.StoryboardRow row = DramaTestSupport.storyboardRow(50, 1, 20, 1);
        when(repository.listStoryboards(1, 20, DramaTestSupport.TENANT)).thenReturn(List.of(row));
        JsonNode rules = mapper.createObjectNode().put("shotType", "中景");
        when(llmClient.photographyRules(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Map.of(1, rules));

        ShortDramaDtos.RulesResultView view = service.generatePhotographyRules(actor, 1, 20);
        assertThat(view.rules()).singleElement().satisfies(r -> {
            assertThat(r.sceneNo()).isEqualTo(1);
            assertThat(r.rules().get("shotType").asText()).isEqualTo("中景");
        });
        verify(writeService).savePhotographyRules(eq(actor), eq(1L), eq(20L),
                eq(List.of(row)), eq(Map.of(1, rules)));
    }

    @Test
    @DisplayName("摄影规则：模型异常不写库（旧有效值保留）")
    void photographyFailureDoesNotOverwrite() {
        projectAndScript();
        when(repository.listStoryboards(1, 20, DramaTestSupport.TENANT))
                .thenReturn(List.of(DramaTestSupport.storyboardRow(50, 1, 20, 1)));
        when(llmClient.photographyRules(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new P04AiException(P04AiErrorCode.INTERNAL_ERROR, "模型调用失败"));

        assertThatThrownBy(() -> service.generatePhotographyRules(actor, 1, 20))
                .isInstanceOf(P04AiException.class);
        verify(writeService, never()).savePhotographyRules(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("表演指导：校验通过后逐行持久化")
    void actingPersistsValidatedRules() {
        projectAndScript();
        DramaRows.StoryboardRow row = DramaTestSupport.storyboardRow(50, 1, 20, 1);
        when(repository.listStoryboards(1, 20, DramaTestSupport.TENANT)).thenReturn(List.of(row));
        JsonNode notes = mapper.createObjectNode().put("emotion", "克制");
        when(llmClient.actingDirections(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Map.of(1, notes));

        ShortDramaDtos.RulesResultView view = service.generateActingDirections(actor, 1, 20);
        assertThat(view.kind()).isEqualTo("acting");
        assertThat(view.rules()).singleElement()
                .satisfies(r -> assertThat(r.rules().get("emotion").asText()).isEqualTo("克制"));
        verify(writeService).saveActingDirections(eq(actor), eq(1L), eq(20L),
                eq(List.of(row)), eq(Map.of(1, notes)));
    }
}
