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

import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaDtos;
import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.llm.LlmOutputParser;
import com.nageoffer.ai.ragent.shortdrama.service.DramaActor;
import com.nageoffer.ai.ragent.shortdrama.service.DramaIdempotencyStore;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaProjectService;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaWriteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
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
 * F21:0 项目用例红/绿判据：CRUD、租户/属主限域、从创意创建与幂等。
 */
@Tag("dev")
class ShortDramaProjectServiceTest {

    private final ShortDramaRepository repository = mock(ShortDramaRepository.class);
    private final ShortDramaWriteService writeService = mock(ShortDramaWriteService.class);
    private final DramaLlmClient llmClient = mock(DramaLlmClient.class);
    private final DramaIdempotencyStore idempotency = mock(DramaIdempotencyStore.class);
    private final ShortDramaProjectService service =
            new ShortDramaProjectService(repository, writeService, llmClient, idempotency);

    private final DramaActor actor = DramaTestSupport.actor();

    @Test
    @DisplayName("列表只返回当前主体自己的项目（tenant + user 谓词由仓库参数承载）")
    void listProjectsUsesOwnerAndTenant() {
        when(repository.listProjects(42L, DramaTestSupport.TENANT))
                .thenReturn(List.of(DramaTestSupport.projectRow(1), DramaTestSupport.projectRow(2)));
        List<ShortDramaDtos.ProjectView> views = service.listProjects(actor);
        assertThat(views).hasSize(2);
        assertThat(views.get(0).projectName()).isEqualTo("项目-1");
        verify(repository).listProjects(42L, DramaTestSupport.TENANT);
    }

    @Test
    @DisplayName("详情：项目 + 最新脚本 + 只读角色/场景 + 分镜计数")
    void detailAssemblesScriptsAndAssets() {
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.listScripts(1, DramaTestSupport.TENANT))
                .thenReturn(List.of(DramaTestSupport.scriptRow(20, 1)));
        when(repository.listCharacters(1, DramaTestSupport.TENANT))
                .thenReturn(List.of(DramaTestSupport.characterRow(30, 1)));
        when(repository.listLocations(1, DramaTestSupport.TENANT))
                .thenReturn(List.of(DramaTestSupport.locationRow(40, 1)));
        when(repository.countStoryboards(1, DramaTestSupport.TENANT)).thenReturn(2L);

        ShortDramaDtos.ProjectDetailView detail = service.getProject(actor, 1);
        assertThat(detail.project().id()).isEqualTo(1L);
        assertThat(detail.script().id()).isEqualTo(20L);
        assertThat(detail.characters()).singleElement().extracting(ShortDramaDtos.CharacterView::id)
                .isEqualTo(30L);
        assertThat(detail.locations()).singleElement().extracting(ShortDramaDtos.LocationView::id)
                .isEqualTo(40L);
        assertThat(detail.storyboardCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("越权/不存在统一 404：不区分存在但与主体无关")
    void foreignProjectIsNotFound() {
        when(repository.findProject(9, 42L, DramaTestSupport.TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProject(actor, 9))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
    }

    @Test
    @DisplayName("手动创建默认 draft/realistic/9:16，并显式写 tenant/user")
    void createProjectDefaults() {
        when(repository.insertProject(42L, DramaTestSupport.TENANT, "新项目", "简介",
                "draft", "realistic", "9:16")).thenReturn(10L);
        when(repository.findProject(10, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(10)));
        when(repository.listScripts(10, DramaTestSupport.TENANT)).thenReturn(List.of());
        when(repository.listCharacters(10, DramaTestSupport.TENANT)).thenReturn(List.of());
        when(repository.listLocations(10, DramaTestSupport.TENANT)).thenReturn(List.of());

        ShortDramaDtos.ProjectDetailView detail = service.createProject(actor,
                new ShortDramaDtos.CreateProjectRequest("新项目", "简介", null, null, null));
        assertThat(detail.project().id()).isEqualTo(10L);
        verify(repository).insertProject(42L, DramaTestSupport.TENANT, "新项目", "简介",
                "draft", "realistic", "9:16");
    }

    @Test
    @DisplayName("编辑：先核属主/租户，再写")
    void updateProjectChecksOwnership() {
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.updateProject(1, 42L, DramaTestSupport.TENANT, "改名", "新简介", "active", "realistic"))
                .thenReturn(1);
        when(repository.listScripts(1, DramaTestSupport.TENANT)).thenReturn(List.of());
        when(repository.listCharacters(1, DramaTestSupport.TENANT)).thenReturn(List.of());
        when(repository.listLocations(1, DramaTestSupport.TENANT)).thenReturn(List.of());

        service.updateProject(actor, 1,
                new ShortDramaDtos.UpdateProjectRequest("改名", "新简介", null, "active"));
        verify(repository).updateProject(1, 42L, DramaTestSupport.TENANT, "改名", "新简介",
                "active", "realistic");
    }

    @Test
    @DisplayName("删除：越权项目 404，不触发级联")
    void deleteForeignProjectDoesNotCascade() {
        when(repository.findProject(9, 42L, DramaTestSupport.TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteProject(actor, 9))
                .isInstanceOf(P04AiException.class);
        verify(writeService, never()).deleteProject(any(), anyLong());
    }

    @Test
    @DisplayName("从创意创建：一次模型调用 + 事务内项目/脚本 + 幂等登记")
    void createFromIdeaIgnoresBlankIdempotencyAndPersists() {
        when(idempotency.completed(actor, "key-1")).thenReturn(Optional.empty());
        when(idempotency.claim(actor, "key-1")).thenReturn(true);
        when(llmClient.generateScript("我的项目", "一个创意", null))
                .thenReturn(new LlmOutputParser.ScriptEnvelope("我的项目", "简介", "第一稿",
                        "热血", "大纲", "内景 客厅 白天\n正文"));
        when(writeService.createProjectWithScript(eq(actor), any(), any()))
                .thenReturn(new ShortDramaWriteService.CreatedIds(10, 20));
        when(repository.findProject(10, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(10)));
        when(repository.listScripts(10, DramaTestSupport.TENANT))
                .thenReturn(List.of(DramaTestSupport.scriptRow(20, 10)));
        when(repository.listCharacters(10, DramaTestSupport.TENANT)).thenReturn(List.of());
        when(repository.listLocations(10, DramaTestSupport.TENANT)).thenReturn(List.of());

        ShortDramaDtos.ProjectDetailView detail = service.createFromIdea(actor,
                new ShortDramaDtos.CreateFromIdeaRequest("一个创意", "我的项目", null, null, "key-1"));

        assertThat(detail.script().id()).isEqualTo(20L);
        verify(llmClient).generateScript("我的项目", "一个创意", null);
        verify(writeService).createProjectWithScript(eq(actor), any(), any());
        verify(idempotency).complete(actor, "key-1", 10);
    }

    @Test
    @DisplayName("幂等：已完成 key 直接返回既有项目，不再调用模型")
    void createFromIdeaReusesCompletedKey() {
        when(idempotency.completed(actor, "key-1")).thenReturn(Optional.of(10L));
        when(repository.findProject(10, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(10)));
        when(repository.listScripts(10, DramaTestSupport.TENANT)).thenReturn(List.of());
        when(repository.listCharacters(10, DramaTestSupport.TENANT)).thenReturn(List.of());
        when(repository.listLocations(10, DramaTestSupport.TENANT)).thenReturn(List.of());

        ShortDramaDtos.ProjectDetailView detail = service.createFromIdea(actor,
                new ShortDramaDtos.CreateFromIdeaRequest("创意", null, null, null, "key-1"));
        assertThat(detail.project().id()).isEqualTo(10L);
        verify(llmClient, never()).generateScript(anyString(), anyString(), any());
        verify(writeService, never()).createProjectWithScript(any(), any(), any());
    }

    @Test
    @DisplayName("幂等：进行中的同一 key → 409，不并发重复生成")
    void createFromIdeaRejectsInFlightKey() {
        when(idempotency.completed(actor, "key-1")).thenReturn(Optional.empty());
        when(idempotency.claim(actor, "key-1")).thenReturn(false);
        assertThatThrownBy(() -> service.createFromIdea(actor,
                new ShortDramaDtos.CreateFromIdeaRequest("创意", null, null, null, "key-1")))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.IDEMPOTENCY_KEY_REUSED);
        verify(llmClient, never()).generateScript(anyString(), anyString(), any());
    }
}
