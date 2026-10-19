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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaController;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaDtos;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaAssetService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaProjectService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaScriptService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

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
 * F21 控制器红/绿判据：主体缺失/缺 scope 一律拒绝；成功 GET 带回执头；
 * 路径参数优先于请求体 scriptId。
 */
@Tag("dev")
class ShortDramaControllerTest {

    private final ShortDramaProjectService projectService = mock(ShortDramaProjectService.class);
    private final ShortDramaScriptService scriptService = mock(ShortDramaScriptService.class);
    private final ShortDramaAssetService assetService = mock(ShortDramaAssetService.class);
    private final ShortDramaStreamService streamService = mock(ShortDramaStreamService.class);
    private final DeliveryPermits deliveryPermits = mock(DeliveryPermits.class);
    private final ShortDramaController controller = new ShortDramaController(
            projectService, scriptService, assetService, streamService,
            deliveryPermits, new ObjectMapper());

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("缺主体：401，且不触达服务层")
    void missingPrincipalIsRejected() {
        PrincipalContext.clear();
        assertThatThrownBy(() -> controller.listProjects())
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.AUTH_REQUIRED);
        verify(projectService, never()).listProjects(any());
    }

    @Test
    @DisplayName("缺 scope：403，且不触达服务层")
    void missingScopeIsRejected() {
        PrincipalContext.set(DramaTestSupport.principal("drama.write"));
        assertThatThrownBy(() -> controller.listProjects())
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.FORBIDDEN);
        verify(projectService, never()).listProjects(any());
    }

    @Test
    @DisplayName("GET 成功：整数 code 信封 + 两个交付回执头")
    void listProjectsCarriesDeliveryPermit() {
        PrincipalContext.set(DramaTestSupport.principal("drama.read"));
        when(projectService.listProjects(any()))
                .thenReturn(List.of(new ShortDramaDtos.ProjectView(1L, "项目", "简介",
                        "draft", "realistic", "9:16", null, null)));
        when(deliveryPermits.enter(any(), eq("drama.read"), eq("drama:projects")))
                .thenReturn(DramaTestSupport.permit());

        ResponseEntity<ApiEnvelope<List<ShortDramaDtos.ProjectView>>> response =
                controller.listProjects();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Permit"))
                .isEqualTo("permit-1");
        assertThat(response.getHeaders().getFirst("X-AI-Delivery-Operation"))
                .isEqualTo("operation-1");
    }

    @Test
    @DisplayName("写面缺 write scope：403")
    void createProjectRequiresWriteScope() {
        PrincipalContext.set(DramaTestSupport.principal("drama.generate"));
        assertThatThrownBy(() -> controller.createProject(
                new ShortDramaDtos.CreateProjectRequest("项目", null, null, null, null)))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.FORBIDDEN);
        verify(projectService, never()).createProject(any(), any());
    }

    @Test
    @DisplayName("创建项目：主体 tenant/user 显式传给服务层")
    void createProjectPassesActor() {
        PrincipalContext.set(DramaTestSupport.principal("drama.write"));
        ShortDramaDtos.ProjectDetailView detail = new ShortDramaDtos.ProjectDetailView(
                new ShortDramaDtos.ProjectView(1L, "项目", "简介", "draft",
                        "realistic", "9:16", null, null),
                null, List.of(), List.of(), List.of(), 0, 0, 0);
        when(projectService.createProject(any(), any())).thenReturn(detail);

        ApiEnvelope<ShortDramaDtos.ProjectDetailView> envelope = controller.createProject(
                new ShortDramaDtos.CreateProjectRequest("项目", "简介", null, null, null));
        assertThat(envelope.code()).isEqualTo(200);
        assertThat(envelope.data().project().id()).isEqualTo(1L);
        verify(projectService).createProject(
                eq(DramaTestSupport.actor()),
                eq(new ShortDramaDtos.CreateProjectRequest("项目", "简介", null, null, null)));
    }

    @Test
    @DisplayName("流式创建缺 generate scope：403，不启动流")
    void streamRequiresGenerateScope() {
        PrincipalContext.set(DramaTestSupport.principal("drama.read", "drama.write"));
        assertThatThrownBy(() -> controller.createFromIdeaStream(
                new ShortDramaDtos.CreateFromIdeaRequest("创意", null, null, null, null)))
                .isInstanceOf(P04AiException.class);
        verify(streamService, never()).streamCreateFromIdea(any(), any(), any());
    }

    @Test
    @DisplayName("资产动作：scriptId 为空 → 400，不触达服务层")
    void scriptActionRequiresScriptId() {
        PrincipalContext.set(DramaTestSupport.principal("drama.generate"));
        assertThatThrownBy(() -> controller.analyzeAssets(1, null))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.BAD_REQUEST);
        verify(assetService, never()).analyzeAssets(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("PUT 脚本：路径 scriptId 覆盖请求体，防止改到别的脚本")
    void updateScriptUsesPathScriptId() {
        PrincipalContext.set(DramaTestSupport.principal("drama.write"));
        when(scriptService.saveScript(any(), eq(1L), any()))
                .thenReturn(new ShortDramaDtos.ScriptView(20L, 1L, "剧本", "正文",
                        null, null, "manual", null, null));

        controller.updateScript(1, 20,
                new ShortDramaDtos.SaveScriptRequest(999L, "剧本", "正文", null, null));
        verify(scriptService).saveScript(eq(DramaTestSupport.actor()), eq(1L),
                eq(new ShortDramaDtos.SaveScriptRequest(20L, "剧本", "正文", null, null)));
    }
}
