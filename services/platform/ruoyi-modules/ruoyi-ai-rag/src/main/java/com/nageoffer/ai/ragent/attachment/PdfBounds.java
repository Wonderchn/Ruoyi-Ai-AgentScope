package com.nageoffer.ai.ragent.attachment;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * F04：聊天附件 PDF 的唯一边界解析入口。
 *
 * <p>旧实现走 Tika 通用 facade，只有 16000 字符上限：页数、解析时间与内嵌提取成本都没有边界。
 * 本类把 PDF 解析收敛为可证明的四个边界：</p>
 * <ol>
 *   <li>页数 ≤ {@link #MAX_PAGES}；</li>
 *   <li>提取文本在越过上限时立即中止并拒绝（越界即失败，不静默截断）；</li>
 *   <li>解析共享同一时间预算，按文本块与页边界检查 {@link #MAX_MILLIS}；</li>
 *   <li>内嵌文件（名称树或 FileAttachment 注释）与加密文档一律拒绝、不接受密码。</li>
 * </ol>
 *
 * <p>只做文本抽取：不提取图片、内嵌文件或注释；失败与其他解析失败同形（由调用方映射为 BAD_REQUEST）。</p>
 */
final class PdfBounds {

    static final int MAX_PAGES = 50;
    static final long MAX_MILLIS = 5000L;
    /** 中止上限在 16000 之上留出页/段落分隔符余量：精确的“不超过 16000”仍由调用点统一判定。 */
    private static final int ABORT_MARGIN = 1024;

    private PdfBounds() {
    }

    static String extract(byte[] bytes) {
        return extract(bytes, System.nanoTime() + MAX_MILLIS * 1_000_000L);
    }

    /** 包可见的显式截止时间入口：生产固定 5s 预算；测试用它证明时间边界真实生效。 */
    static String extract(byte[] bytes, long deadlineNanos) {
        if (System.nanoTime() > deadlineNanos) {
            throw new IllegalArgumentException("Attachment document parsing exceeded the time limit");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("Encrypted attachment PDFs are not supported");
            }
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > MAX_PAGES) {
                throw new IllegalArgumentException("Attachment PDF exceeds the " + MAX_PAGES + "-page limit");
            }
            rejectEmbeddedFiles(document);
            BoundedTextStripper stripper = new BoundedTextStripper(deadlineNanos);
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            return stripper.getText(document);
        } catch (PdfLimitException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Attachment PDF could not be parsed", ex);
        }
    }

    private static void rejectEmbeddedFiles(PDDocument document) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDDocumentNameDictionary names = catalog.getNames();
        if (names != null && hasNames(names.getEmbeddedFiles())) {
            throw new IllegalArgumentException("Attachment PDFs with embedded files are not supported");
        }
        for (PDPage page : document.getPages()) {
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationFileAttachment) {
                    throw new IllegalArgumentException("Attachment PDFs with embedded files are not supported");
                }
            }
        }
    }

    private static boolean hasNames(PDNameTreeNode<PDComplexFileSpecification> node) throws IOException {
        if (node == null) {
            return false;
        }
        Map<String, PDComplexFileSpecification> names = node.getNames();
        if (names != null && !names.isEmpty()) {
            return true;
        }
        List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
        if (kids != null) {
            for (PDNameTreeNode<PDComplexFileSpecification> kid : kids) {
                if (hasNames(kid)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 文本剥离器：越过硬上限或超时立即中止（异常路径，不产出被截断的文本）。 */
    private static final class BoundedTextStripper extends PDFTextStripper {

        private final long deadlineNanos;
        private int extracted;

        BoundedTextStripper(long deadlineNanos) throws IOException {
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            checkDeadline();
            extracted += text.length();
            if (extracted > ChatAttachmentPolicy.MAX_TEXT + ABORT_MARGIN) {
                throw new PdfLimitException("Attachment document exceeds the 16000-character text limit");
            }
            super.writeString(text, textPositions);
        }

        @Override
        protected void writePageStart() throws IOException {
            checkDeadline();
            super.writePageStart();
        }

        @Override
        protected void writePageEnd() throws IOException {
            checkDeadline();
            super.writePageEnd();
        }

        private void checkDeadline() {
            if (System.nanoTime() > deadlineNanos) {
                throw new PdfLimitException("Attachment document parsing exceeded the time limit");
            }
        }
    }

    /** 边界中止信号；由 {@link #extract(byte[], long)} 统一转成 IllegalArgumentException。 */
    private static final class PdfLimitException extends RuntimeException {
        PdfLimitException(String message) {
            super(message);
        }
    }
}
