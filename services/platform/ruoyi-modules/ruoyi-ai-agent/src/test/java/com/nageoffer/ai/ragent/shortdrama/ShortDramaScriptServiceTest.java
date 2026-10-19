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
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import com.nageoffer.ai.ragent.shortdrama.dao.ShortDramaRepository;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.llm.LlmOutputParser;
import com.nageoffer.ai.ragent.shortdrama.service.DramaActor;
import com.nageoffer.ai.ragent.shortdrama.service.ShortDramaScriptService;
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
 * F21:1 脚本红/绿判据：手动保存、独立生成、润色成功/失败/并发冲突。
 */
@Tag("dev")
class ShortDramaScriptServiceTest {

    private final ShortDramaRepository repository = mock(ShortDramaRepository.class);
    private final ShortDramaWriteService writeService = mock(ShortDramaWriteService.class);
    private final DramaLlmClient llmClient = mock(DramaLlmClient.class);
    private final ShortDramaScriptService service =
            new ShortDramaScriptService(repository, writeService, llmClient);

    private final DramaActor actor = DramaTestSupport.actor();

    @Test
    @DisplayName("手动保存：显式 manual 来源，插入后回读")
    void saveScriptInsertsManual() {
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.insertScript(1, DramaTestSupport.TENANT, "剧本", "正文", null, "短剧", "manual"))
                .thenReturn(20L);
        when(repository.findScript(20, 1, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.scriptRow(20, 1)));

        ShortDramaDtos.ScriptView view = service.saveScript(actor, 1,
                new ShortDramaDtos.SaveScriptRequest(null, "剧本", "正文", null, "短剧"));
        assertThat(view.id()).isEqualTo(20L);
        verify(repository).insertScript(1, DramaTestSupport.TENANT, "剧本", "正文", null, "短剧", "manual");
    }

    @Test
    @DisplayName("跨项目：项目不属于当前主体 → 404，不插入")
    void saveScriptRejectsForeignProject() {
        when(repository.findProject(9, 42L, DramaTestSupport.TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveScript(actor, 9,
                new ShortDramaDtos.SaveScriptRequest(null, "剧本", "正文", null, null)))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        verify(repository, never()).insertScript(anyLong(), anyString(), anyString(), anyString(),
                any(), any(), anyString());
    }

    @Test
    @DisplayName("修改脚本：脚本不在该项目/租户 → 404")
    void updateScriptRejectsForeignScript() {
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.findScript(99, 1, DramaTestSupport.TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveScript(actor, 1,
                new ShortDramaDtos.SaveScriptRequest(99L, "改名", "正文", null, null)))
                .isInstanceOf(P04AiException.class);
        verify(repository, never()).updateScriptManual(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("独立生成：插入新脚本行（source_type=ai），不覆盖旧稿")
    void generateScriptInsertsAiRow() {
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(llmClient.generateScript("项目-1", "创意", "热血"))
                .thenReturn(new LlmOutputParser.ScriptEnvelope("项目-1", "简介", "新稿",
                        "热血", "新大纲", "内景 客厅 白天\n新正文"));
        when(repository.insertScript(1, DramaTestSupport.TENANT, "新稿", "内景 客厅 白天\n新正文",
                "新大纲", "热血", "ai")).thenReturn(21L);
        when(repository.findScript(21, 1, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(new DramaRows.ScriptRow(21, 1, "新稿",
                        "内景 客厅 白天\n新正文", "新大纲", "热血", "ai",
                        DramaTestSupport.time(), DramaTestSupport.time(), DramaTestSupport.TENANT)));

        ShortDramaDtos.ScriptView view = service.generateScript(actor, 1,
                new ShortDramaDtos.GenerateScriptRequest("创意", null, "热血"));
        assertThat(view.sourceType()).isEqualTo("ai");
        verify(repository).insertScript(1, DramaTestSupport.TENANT, "新稿",
                "内景 客厅 白天\n新正文", "新大纲", "热血", "ai");
    }

    @Test
    @DisplayName("润色成功：模型输出合并后走条件更新")
    void polishScriptUpdatesAfterSuccess() {
        DramaRows.ScriptRow observed = DramaTestSupport.scriptRow(20, 1);
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.findScript(20, 1, DramaTestSupport.TENANT)).thenReturn(Optional.of(observed));
        when(llmClient.polishScript("项目-1", observed.scriptText(), "短剧"))
                .thenReturn(new LlmOutputParser.ScriptEnvelope("项目-1", "简介", "润色稿",
                        "短剧", "润色大纲", "内景 客厅 白天\n润色正文"));
        DramaRows.ScriptRow updated = new DramaRows.ScriptRow(20, 1, "润色稿",
                "内景 客厅 白天\n润色正文", "润色大纲", "短剧", "manual",
                DramaTestSupport.time(), DramaTestSupport.time(), DramaTestSupport.TENANT);
        when(writeService.polishScript(eq(actor), eq(1L), eq(observed), any()))
                .thenReturn(updated);

        ShortDramaDtos.ScriptView view = service.polishScript(actor, 1,
                new ShortDramaDtos.PolishScriptRequest(20L));
        assertThat(view.scriptName()).isEqualTo("润色稿");
        verify(writeService).polishScript(eq(actor), eq(1L), eq(observed), any());
    }

    @Test
    @DisplayName("润色失败：模型异常不写库，旧稿保留")
    void polishScriptFailureKeepsOld() {
        DramaRows.ScriptRow observed = DramaTestSupport.scriptRow(20, 1);
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.findScript(20, 1, DramaTestSupport.TENANT)).thenReturn(Optional.of(observed));
        when(llmClient.polishScript(anyString(), anyString(), any()))
                .thenThrow(new P04AiException(P04AiErrorCode.INTERNAL_ERROR, "模型调用失败"));

        assertThatThrownBy(() -> service.polishScript(actor, 1,
                new ShortDramaDtos.PolishScriptRequest(20L)))
                .isInstanceOf(P04AiException.class);
        verify(writeService, never()).polishScript(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("润色坏输出：解析异常不写库")
    void polishScriptBadOutputKeepsOld() {
        DramaRows.ScriptRow observed = DramaTestSupport.scriptRow(20, 1);
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.findScript(20, 1, DramaTestSupport.TENANT)).thenReturn(Optional.of(observed));
        when(llmClient.polishScript(anyString(), anyString(), any()))
                .thenThrow(new P04AiException(P04AiErrorCode.INTERNAL_ERROR, "模型输出格式不合法"));

        assertThatThrownBy(() -> service.polishScript(actor, 1,
                new ShortDramaDtos.PolishScriptRequest(20L)))
                .isInstanceOf(P04AiException.class);
        verify(writeService, never()).polishScript(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("润色并发冲突：写服务 409 透传，不重试覆盖")
    void polishScriptConflictPropagates() {
        DramaRows.ScriptRow observed = DramaTestSupport.scriptRow(20, 1);
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.findScript(20, 1, DramaTestSupport.TENANT)).thenReturn(Optional.of(observed));
        when(llmClient.polishScript(anyString(), anyString(), any()))
                .thenReturn(new LlmOutputParser.ScriptEnvelope("项目-1", null, "润色稿",
                        null, null, "润色正文"));
        when(writeService.polishScript(eq(actor), eq(1L), eq(observed), any()))
                .thenThrow(new P04AiException(P04AiErrorCode.RESOURCE_VERSION_CONFLICT, "脚本已变化"));

        assertThatThrownBy(() -> service.polishScript(actor, 1,
                new ShortDramaDtos.PolishScriptRequest(20L)))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_VERSION_CONFLICT);
    }

    @Test
    @DisplayName("润色无脚本：404，不调用模型")
    void polishWithoutScriptIsNotFound() {
        when(repository.findProject(1, 42L, DramaTestSupport.TENANT))
                .thenReturn(Optional.of(DramaTestSupport.projectRow(1)));
        when(repository.findLatestScript(1, DramaTestSupport.TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.polishScript(actor, 1,
                new ShortDramaDtos.PolishScriptRequest(null)))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        verify(llmClient, never()).polishScript(anyString(), anyString(), any());
    }
}
