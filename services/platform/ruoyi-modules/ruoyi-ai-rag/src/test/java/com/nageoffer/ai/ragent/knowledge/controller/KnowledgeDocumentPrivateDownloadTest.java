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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
 */
@Tag("dev")
class KnowledgeDocumentPrivateDownloadTest {

    private KnowledgeDocumentService documentService;
    private FileStorageService fileStorageService;
    private KnowledgeDocumentController controller;

    @BeforeEach
    void setUp() {
        documentService = mock(KnowledgeDocumentService.class);
        fileStorageService = mock(FileStorageService.class);
        controller = new KnowledgeDocumentController(
                documentService, fileStorageService, mock(IngestionSpecSchemaProvider.class));
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
    void fileShouldWriteNothingWhenDocumentNotVisibleToTenant() {
        when(documentService.get("doc-2")).thenThrow(new ClientException("文档不存在"));

        MockHttpServletResponse response = new MockHttpServletResponse();

        ClientException ex = assertThrows(ClientException.class, () -> controller.file("doc-2", response));
        assertEquals("文档不存在", ex.getErrorMessage(), "跨租户与不存在必须同外显，不泄露存在性");
        assertEquals(0, response.getContentAsByteArray().length, "被拒绝的取流不得留下任何字节");
        verify(fileStorageService, never()).openStream(any());
    }

    @Test
    void updateShouldForwardExpectedVersionFromBody() {
        KnowledgeDocumentUpdateRequest request = new KnowledgeDocumentUpdateRequest();
        request.setDocName("新名字");
        request.setExpectedVersion(1_700_000_000_000L);

        Result<Void> result = controller.update("doc-1", request);

        assertEquals("0", result.getCode(), "本族成功码是字符串 \"0\"（不是 /api/ai/v1 的整数 200）");
        verify(documentService).update(eq("doc-1"), any(KnowledgeDocumentUpdateRequest.class));
    }

    @Test
    void enableShouldForwardExpectedVersionFromQuery() {
        Result<Void> result = controller.enable("doc-1", false, 1_700_000_000_000L);

        assertEquals("0", result.getCode());
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

        Result<KnowledgeDocumentVO> result = controller.get("doc-1");

        assertEquals("0", result.getCode());
        assertEquals(1_700_000_000_000L, result.getData().getVersion());
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
