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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RW-04：私有文档取流（受控层）与控制层契约连线。
 *
 * <p>三条判据：
 * <ol>
 *   <li>取流必须是<b>逐字节一致</b>——不能走文本编解码（否则二进制 PDF 会被改写）；</li>
 *   <li>跨租户/不存在时由服务层拒绝，控制层不得写出任何字节（半截文件比报错更坏）；</li>
 *   <li>{@code expectedVersion} 必须真的被绑定并转交服务层，而不是只写在 body 类型里。</li>
 * </ol>
 *
 * <p>每个用例重建 mock：本类有 {@code verify(never())} 判据，共享 mock 会把
 * 上一条用例 {@code when(...)} 自身产生的调用记录带进来，把负例验成假失败
 * （或更糟：把本该失败的负例放过去）。
 *
 * <p><b>RW-04-R8 变更</b>：控制层信封由 platform {@code Result}（字符串 {@code code}）
 * 改为 {@link ApiEnvelope}（整数 {@code code}）；{@code .../file} 另加交付回执头
 * （字节分支要求）。原先钉住"本族成功码是字符串 {@code "0"}"的三条断言已改为钉住
 * <b>整数 200</b>——判据方向被翻转，<b>没有被削弱</b>：仍逐条断言返回类型与
 * {@code code} 值，并另加"字符串 code 不得再出现"的反向断言。
 */
@Tag("dev")
class KnowledgeDocumentPrivateDownloadTest {

    private KnowledgeDocumentService documentService;
    private FileStorageService fileStorageService;
    private KnowledgeDocumentController controller;
    private DeliveryPermits permits;

    @BeforeEach
    void setUp() {
        documentService = mock(KnowledgeDocumentService.class);
        fileStorageService = mock(FileStorageService.class);
        permits = mock(DeliveryPermits.class);
        when(permits.enter(any(), anyString(), anyString()))
                .thenAnswer(invocation -> new DeliveryPermits.Permit(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(), null));
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101",
                7, 3, Set.of("ai:document:read"), "jti", "platform", 0, Long.MAX_VALUE));
        controller = new KnowledgeDocumentController(
                documentService, fileStorageService, mock(IngestionSpecSchemaProvider.class), permits);
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void fileShouldDeliverBytesVerbatimIncludingNonUtf8Bytes() throws Exception {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        KnowledgeDocumentVO document = document("季度报告.pdf");
        when(documentService.get("doc-1")).thenReturn(document);
        when(fileStorageService.openStream("kb/document.pdf")).thenReturn(new ByteArrayInputStream(binary));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.file("doc-1", response);

        byte[] delivered = response.getContentAsByteArray();
        assertEquals(256, delivered.length, "取流长度与源对象不一致");
        assertArrayEquals(binary, delivered, "取流必须逐字节一致（含 0x00-0xFF 全部取值）");
        assertEquals("application/pdf", response.getContentType());
        assertEquals("季度报告.pdf",
                ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename());
    }

    @Test
    @DisplayName("裸字节取流必须带两个交付回执头（网关字节分支对 200 响应强制要求）")
    void fileShouldCarryDeliveryReceiptHeaders() throws Exception {
        KnowledgeDocumentVO document = document("季度报告.pdf");
        when(documentService.get("doc-1")).thenReturn(document);
        when(fileStorageService.openStream("kb/document.pdf"))
                .thenReturn(new ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.file("doc-1", response);

        // LocalAiGatewayClient.forwardBytes 对 200 响应要求这两个头匹配 [0-9a-f-]{36}
        assertUuidHeader(response, "X-AI-Delivery-Permit");
        assertUuidHeader(response, "X-AI-Delivery-Operation");
    }

    /** 缺头时要给出可读的断言失败，而不是在 {@code null.matches} 上抛 NPE。 */
    private static void assertUuidHeader(MockHttpServletResponse response, String name) {
        String value = response.getHeader(name);
        assertNotNull(value,
                name + " 缺失 ⇒ 网关字节分支 503（delivery receipt missing）："
                        + "裸字节面走 forwardBytes，200 响应必须同时带 permit/operation 两个 UUID 形状的头");
        assertTrue(value.matches("[0-9a-f-]{36}"),
                name + " 必须是 UUID 形状（forwardBytes 校验 [0-9a-f-]{36}），实际=" + value);
    }

    @Test
    void fileShouldWriteNothingWhenDocumentNotVisibleToTenant() {
        when(documentService.get("doc-2")).thenThrow(new ClientException("文档不存在"));

        MockHttpServletResponse response = new MockHttpServletResponse();

        ClientException ex = assertThrows(ClientException.class, () -> controller.file("doc-2", response));
        assertEquals("文档不存在", ex.getErrorMessage(), "跨租户与不存在必须同外显，不泄露存在性");
        assertEquals(0, response.getContentAsByteArray().length, "被拒绝的取流不得留下任何字节");
        assertNull(response.getHeader("X-AI-Delivery-Permit"),
                "被拒绝的取流不得铸出 ACTIVE 许可（否则屏障排不空）");
        verify(fileStorageService, never()).openStream(any());
        verify(permits, never()).enter(any(), anyString(), anyString());
    }

    @Test
    void updateShouldForwardExpectedVersionFromBody() {
        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("新名字");
        request.setExpectedVersion(1_700_000_000_000L);

        ApiEnvelope<Void> envelope = controller.update("doc-1", request);

        assertEquals(ApiEnvelope.class, envelope.getClass(),
                "公开面 /api/ai/v1 的信封必须是 ApiEnvelope：Result 的 code 是字符串 \"0\"，"
                        + "而 LocalAiGatewayClient.requireSingleJsonObject 要求整数 code ⇒ 经网关必然 503");
        assertEquals(200, envelope.code(),
                "本族成功码是整数 200（不是字符串 \"0\"）：字符串 code 经网关必然 503（missing envelope code）");
        verify(documentService).update(eq("doc-1"), any(KnowledgeDocumentUpdateRequest.class));
    }

    @Test
    void enableShouldForwardExpectedVersionFromQuery() {
        ApiEnvelope<Void> envelope = controller.enable("doc-1", false, 1_700_000_000_000L);

        assertEquals(ApiEnvelope.class, envelope.getClass());
        assertEquals(200, envelope.code());
        verify(documentService).enable("doc-1", false, 1_700_000_000_000L);
    }

    @Test
    void enableWithoutExpectedVersionStaysCompatible() {
        controller.enable("doc-1", true, null);

        verify(documentService).enable("doc-1", true, null);
    }

    @Test
    void documentViewShouldCarryVersionForSubsequentEdits() {
        KnowledgeDocumentVO document = document("季度报告.pdf");
        document.setVersion(1_700_000_000_000L);
        when(documentService.get("doc-1")).thenReturn(document);

        ResponseEntity<ApiEnvelope<KnowledgeDocumentVO>> response = controller.get("doc-1");

        assertEquals(200, response.getStatusCode().value());
        ApiEnvelope<KnowledgeDocumentVO> envelope = response.getBody();
        assertEquals(200, envelope.code());
        assertEquals(1_700_000_000_000L, envelope.data().getVersion());
    }

    private static KnowledgeDocumentVO document(String filename) {
        KnowledgeDocumentVO document = new KnowledgeDocumentVO();
        document.setId("doc-1");
        document.setKbId("kb-1");
        document.setDocName(filename);
        document.setFileType("pdf");
        document.setFileUrl("kb/document.pdf");
        return document;
    }
}
