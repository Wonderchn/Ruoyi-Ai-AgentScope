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

package com.nageoffer.ai.ragent.rag.skillpackage;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.rag.controller.AgentSkillController;
import com.nageoffer.ai.ragent.rag.service.AgentSkillAdminService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * F11:4 技能包端点判据（方法级路径 + 4xx 映射 + GET 交付回执）。
 *
 * <p>ROUTES 不在作者范围（由协调者集成时单写），本类只钉内层方法级路径：
 * {@code POST /internal/ai/v1/agent-catalog/agent-skills/import} 与
 * {@code GET /internal/ai/v1/agent-catalog/agent-skills/{id}/export}。
 *
 * <p>错误映射必须是具名 4xx（缺参 400 / 坏包 400 / 超限 413 / 冲突 409 / 未找到 404），
 * 不得 5xx；导出在取数失败时不得铸造交付许可（零副作用）。
 */
@Tag("dev")
class AgentSkillPackageControllerTest {

    private static final String PREFIX = "/internal/ai/v1/agent-catalog";
    private static final String TENANT = "T1";

    private final AgentSkillAdminService adminService = mock(AgentSkillAdminService.class);
    private final SkillPackageService packageService = mock(SkillPackageService.class);
    private final DeliveryPermits permits = mock(DeliveryPermits.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentSkillController(adminService, packageService, permits)).build();
        PrincipalContext.set(new ExecutionPrincipal(TENANT, "2101", "platform:" + TENANT + ":2101",
                1, 1, Set.of("ai:skill:read", "ai:skill:write"), "jti", "platform", 0, Long.MAX_VALUE));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------------ 导入

    @Test
    @DisplayName("导入：multipart 路径被映射，成功返回整数 code=200 信封且不铸交付回执")
    void importIsMappedAndReturnsEnvelope() throws Exception {
        when(packageService.importPackage(any(MultipartFile.class)))
                .thenReturn(new SkillPackageImportResult("id-1", "leave_apply"));

        MockHttpServletResponse response = mvc.perform(multipart(PREFIX + "/agent-skills/import")
                        .file(new MockMultipartFile("file", "skill.zip", "application/zip", new byte[]{1})))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":200").contains("leave_apply");
        verify(permits, never()).enter(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("导入缺 file：400 MISSING_FILE（具名 4xx，不是 5xx）")
    void missingFileMapsToNamedBadRequest() throws Exception {
        when(packageService.importPackage(isNull()))
                .thenThrow(new SkillPackageException(SkillPackageError.MISSING_FILE));

        MockHttpServletResponse response = mvc.perform(multipart(PREFIX + "/agent-skills/import"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":400").contains("MISSING_FILE");
    }

    @Test
    @DisplayName("导入坏包：400 INVALID_PACKAGE（含路径穿越/坏 manifest 等服务层拒绝）")
    void invalidPackageMapsToNamedBadRequest() throws Exception {
        when(packageService.importPackage(any(MultipartFile.class)))
                .thenThrow(new SkillPackageException(SkillPackageError.INVALID_PACKAGE, "路径穿越"));

        MockHttpServletResponse response = mvc.perform(multipart(PREFIX + "/agent-skills/import")
                        .file(new MockMultipartFile("file", "skill.zip", "application/zip", new byte[]{1})))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":400").contains("INVALID_PACKAGE");
    }

    @Test
    @DisplayName("导入超限：413 PACKAGE_TOO_LARGE")
    void oversizePackageMapsToPayloadTooLarge() throws Exception {
        when(packageService.importPackage(any(MultipartFile.class)))
                .thenThrow(new SkillPackageException(SkillPackageError.PACKAGE_TOO_LARGE));

        MockHttpServletResponse response = mvc.perform(multipart(PREFIX + "/agent-skills/import")
                        .file(new MockMultipartFile("file", "skill.zip", "application/zip", new byte[]{1})))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":413").contains("PACKAGE_TOO_LARGE");
    }

    @Test
    @DisplayName("导入冲突：409 SKILL_CONFLICT（默认拒绝，不覆盖既有行）")
    void conflictMapsToNamedConflict() throws Exception {
        when(packageService.importPackage(any(MultipartFile.class)))
                .thenThrow(new SkillPackageException(SkillPackageError.SKILL_CONFLICT, "leave_apply"));

        MockHttpServletResponse response = mvc.perform(multipart(PREFIX + "/agent-skills/import")
                        .file(new MockMultipartFile("file", "skill.zip", "application/zip", new byte[]{1})))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":409").contains("SKILL_CONFLICT");
        verify(adminService, never()).create(any());
        verify(adminService, never()).update(anyString(), any());
    }

    // ------------------------------------------------------------------ 导出

    @Test
    @DisplayName("导出：{id}/export 路径被映射，返回 attachment ZIP 并铸两个交付回执头")
    void exportIsMappedAndReturnsAttachmentZip() throws Exception {
        byte[] zip = new byte[]{1, 2, 3, 4};
        when(packageService.exportPackage("s1")).thenReturn(new SkillPackageExport("leave_apply.zip", zip));
        // 成功路径不会 close 许可（由网关 ACK 释放），operation 允许为 null；
        // RevocationGuard.Operation 是 final 类，Mockito 不能替身。
        when(permits.enter(any(), eq("skill.read"), eq("agent-catalog:skill:s1:export")))
                .thenReturn(new DeliveryPermits.Permit("permit-1", "operation-1", null));

        MockHttpServletResponse response = mvc.perform(get(PREFIX + "/agent-skills/s1/export"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains("application/zip");
        assertThat(response.getContentAsByteArray()).containsExactly(zip);
        assertThat(response.getHeader("X-AI-Delivery-Permit")).isEqualTo("permit-1");
        assertThat(response.getHeader("X-AI-Delivery-Operation")).isEqualTo("operation-1");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String disposition = response.getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(ContentDisposition.parse(disposition).getFilename()).isEqualTo("leave_apply.zip");
        verify(packageService).exportPackage("s1");
    }

    @Test
    @DisplayName("导出不存在：404 SKILL_NOT_FOUND，且不铸交付许可（零副作用）")
    void exportMissingSkillIsNamedNotFoundWithoutPermit() throws Exception {
        when(packageService.exportPackage("missing"))
                .thenThrow(new SkillPackageException(SkillPackageError.SKILL_NOT_FOUND));

        MockHttpServletResponse response = mvc.perform(get(PREFIX + "/agent-skills/missing/export"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":404").contains("SKILL_NOT_FOUND");
        verify(permits, never()).enter(any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ 路径归位

    @Test
    @DisplayName("裸路径（去掉 /internal/ai/v1 前缀）必须 404，内层方法级路径才被映射")
    void barePathsAreNotMapped() throws Exception {
        assertThat(mvc.perform(get("/agent-skills/s1/export"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(multipart("/agent-skills/import"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

}
