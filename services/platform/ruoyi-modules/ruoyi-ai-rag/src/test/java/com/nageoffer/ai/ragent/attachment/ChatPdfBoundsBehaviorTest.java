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

package com.nageoffer.ai.ragent.attachment;

import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F04：聊天附件 PDF 解析必须是有界的（页数 / 提取文本量 / 解析时间 / 内嵌文件），单点实现为 PdfBounds。
 *
 * <p>红基线（旧树）：{@code ChatAttachmentService.text} 只做 {@code %PDF-} 前缀 + Tika 字符上限，
 * 51 页短文本 PDF（总文本远小于 16000 仍被接受）、带内嵌文件的 PDF、空口令加密 PDF 都会通过
 * ⇒ 负例断言失败（行为红，不是编译红）。</p>
 *
 * <p>修正后语义：所有 PDF 解析都经过 PdfBounds——页数 ≤50、提取字符 ≤16000（越界即拒绝，不静默截断）、
 * 解析时间有界、内嵌文件与加密文档拒绝；正例（小文档、恰好 50 页）保持可提取。
 * 负例外显与既有的“解析失败”同形（BAD_REQUEST），不引入新的错误面。</p>
 *
 * <p>红阶段通过反射驱动旧实现的私有 {@code text(String,byte[])}：不改产品代码即可执行断言。</p>
 */
@Tag("dev")
class ChatPdfBoundsBehaviorTest {

    private static final int PAGE_BOUND = 50;

    private static String extract(String mime, byte[] bytes) {
        try {
            Method text = ChatAttachmentService.class.getDeclaredMethod("text", String.class, byte[].class);
            text.setAccessible(true);
            return (String) text.invoke(null, mime, bytes);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void assertRefused(byte[] pdf) {
        assertThatThrownBy(() -> extract("application/pdf", pdf))
                .as("bounded PDF parsing must refuse this document")
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode()).isEqualTo(RunErrorCode.BAD_REQUEST));
    }

    @Test
    @DisplayName("负例：超过 50 页的短文本 PDF 必须拒绝（旧实现没有页数上限）")
    void pdfBeyondPageBoundIsRefused() throws Exception {
        assertRefused(PdfFixtures.pdf(PAGE_BOUND + 1, false, false));
    }

    @Test
    @DisplayName("边界正例：恰好 50 页的短文本 PDF 必须仍可提取（不是「多页一律拒绝」）")
    void pdfAtPageBoundIsAccepted() throws Exception {
        String text = extract("application/pdf", PdfFixtures.pdf(PAGE_BOUND, false, false));
        assertThat(text).contains("page 1 marker").contains("page 50 marker");
    }

    @Test
    @DisplayName("负例：带内嵌文件的 PDF 必须拒绝（内嵌提取成本不可控）")
    void pdfWithEmbeddedFilesIsRefused() throws Exception {
        assertRefused(PdfFixtures.pdfWithPageTexts(List.of("page one marker"), true, false));
    }

    @Test
    @DisplayName("负例：空口令加密 PDF 必须拒绝（加密内容不做进一步解析）")
    void encryptedPdfIsRefused() throws Exception {
        assertRefused(PdfFixtures.pdfWithPageTexts(List.of("page one marker"), false, true));
    }

    @Test
    @DisplayName("正例：2 页小 PDF 保持可提取")
    void smallPdfStillExtractsItsText() throws Exception {
        String text = extract("application/pdf",
                PdfFixtures.pdfWithPageTexts(List.of("page one marker", "page two marker"), false, false));
        assertThat(text).contains("page one marker").contains("page two marker");
    }

    @Test
    @DisplayName("负例：总提取文本超过 16000 字符必须拒绝（不静默截断）")
    void oversizedTextIsRefused() throws Exception {
        assertRefused(PdfFixtures.pdfWithPageTexts(List.of("A".repeat(20000)), false, false));
    }
}
