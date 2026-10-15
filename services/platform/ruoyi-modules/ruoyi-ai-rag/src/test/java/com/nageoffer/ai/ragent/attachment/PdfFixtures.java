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

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F04：测试侧 PDF 构造器。只用于生成正/负例文档；与产品实现共用 classpath 上的 PDFBox（Tika 3.2.3 → 3.0.5）。
 */
final class PdfFixtures {

    private PdfFixtures() {
    }

    /** 每页文本为 {@code page N marker} 的 PDF。 */
    static byte[] pdf(int pages, boolean embeddedFiles, boolean encrypted) throws IOException {
        List<String> texts = new ArrayList<>();
        for (int i = 1; i <= pages; i++) {
            texts.add("page " + i + " marker");
        }
        return pdfWithPageTexts(texts, embeddedFiles, encrypted);
    }

    /** 逐页写入给定文本的 PDF；可选内嵌文件或空口令加密。 */
    static byte[] pdfWithPageTexts(List<String> pageTexts, boolean embeddedFiles, boolean encrypted) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    for (int offset = 0; offset < text.length(); offset += 90) {
                        content.showText(text.substring(offset, Math.min(text.length(), offset + 90)));
                        content.newLineAtOffset(0, -14);
                    }
                    content.endText();
                }
            }
            if (embeddedFiles) {
                PDEmbeddedFile file = new PDEmbeddedFile(document,
                        new ByteArrayInputStream("embedded payload".getBytes(StandardCharsets.UTF_8)));
                file.setSubtype("text/plain");
                PDComplexFileSpecification spec = new PDComplexFileSpecification();
                spec.setFile("hidden.txt");
                spec.setEmbeddedFile(file);
                PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
                tree.setNames(Map.of("hidden.txt", spec));
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
                names.setEmbeddedFiles(tree);
                document.getDocumentCatalog().setNames(names);
            }
            if (encrypted) {
                AccessPermission permission = new AccessPermission();
                permission.setCanPrint(true);
                document.protect(new StandardProtectionPolicy("owner-secret", "", permission));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
