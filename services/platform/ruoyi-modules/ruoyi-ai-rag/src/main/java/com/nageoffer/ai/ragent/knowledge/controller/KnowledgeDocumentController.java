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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.IngestionSpecSchemaVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecSchemaProvider;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档管理控制器
 * 提供文档的上传、分块、删除、查询、启用/禁用等功能
 *
 * <p><b>路径面（RW-04-R1）</b>：类级前缀 {@code /internal/ai/v1} 是内层前缀，直接访问被
 * {@code AiInternalAccessBoundaryFilter} 关成 404；公开面经网关白名单为
 * {@code /api/ai/v1/knowledge-base/docs/**}。注意与 P2 文档面
 * （{@code /api/ai/v1/documents/**}，{@code UploadController}）是<b>两条不同的面</b>：
 * 后者是"上传意图→版本→发布指针"的 AI 资源面，本控制器是 ragent 管理面。
 *
 * <p>前置条件：必须由内嵌装配显式登记（platform 扫描根 {@code org.ruoyi} 不含本模块）。
 * 授权不在本层：不加 {@code @SaCheckPermission}，能力级权限由网关判定。
 *
 * <p><b>信封必须是整数 {@code code}（RW-04-R8）。</b>本控制器的 11 条 JSON 方法此前返回
 * {@code framework.convention.Result}，其 {@code code} 是<b>字符串</b>（成功 {@code "0"}）；
 * 而本地传输 {@code LocalAiGatewayClient.forward()} 对任何非空 body 调
 * {@code requireSingleJsonObject(...)}，其中要求 {@code code} 是<b>整数</b>且等于 HTTP 状态
 * （{@code LocalAiGatewayClient.java:458-473}）。platform 侧没有 {@code ResponseBodyAdvice}
 * 兜底 ⇒ 字符串 code 的信封经网关<b>必然 503</b>（{@code missing envelope code}）。
 * 因此统一返回 {@link ApiEnvelope}（{@code int code}，成功 200，HTTP 200），与同包的
 * {@code KnowledgeChunkController} 同形。信封形状由
 * {@code LocalWhitelistEnvelopeShapeTest} 钉住。
 *
 * <p><b>GET 还要回执头。</b>网关对 {@code GET} 一律走<b>字节分支</b>
 * {@code forwardBytes()}（{@code AiGatewayController:366-367} 的 {@code method.equals("GET")}）：
 * 200 响应<b>同时</b>要求 ① JSON 体是整数 code；②
 * {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 是 UUID 形状
 * （{@code LocalAiGatewayClient.java:163-170}），缺任一即 {@code delivery receipt missing}。
 * 所以 6 条 JSON 的 GET 返回 {@code ResponseEntity<ApiEnvelope<…>>} 并在体内铸许可；
 * 第 12 条 {@code .../file} 是<b>裸字节</b>（不吃 JSON 信封），但同样走字节分支，
 * 因此也要铸许可并写两个头。<b>五条写操作走 JSON 分支，不铸回执</b>——JSON 分支不会
 * 释放许可，在那里铸造等于制造永久 ACTIVE 的许可泄漏。
 *
 * <p><b>本控制器自 S2-F05-A1 起已放行（RW-04-R8 改造 + F05 闭包闭合）。</b>RW-04-R8 的信封改造
 * 只消除了"一经登记路由就必然 503"这一项；S2-F05-A1 把 {@code FullAdmin} 的构造闭包补齐、
 * 并在 {@code AiGatewayController.ROUTES} 逐条登记了本面 12 条（公开前缀
 * {@code /api/ai/v1/knowledge-base/docs/**}）。"放行 = 必须整数信封"因此由
 * {@code LocalWhitelistEnvelopeShapeTest#knowledgeAdminFacesAreReleasedAndCounted}
 * 逐条钉住（该判据此前钉的是"仍未放行"）；段数遮蔽由同类的字面量顺序判据钉住
 * （{@code docs/search} 与 {@code docs/ingestion-spec-schema} 必须排在 {@code docs/{docId}} 之前）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
@Validated
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;
    private final FileStorageService fileStorageService;
    private final IngestionSpecSchemaProvider ingestionSpecSchemaProvider;

    /**
     * 交付许可铸造器（GET 走网关字节分支，必须带回执头；见类注释）。
     */
    private final DeliveryPermits deliveryPermits;

    private static final Map<String, String> CONTENT_TYPE_MAP = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("md", "text/markdown"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv;charset=utf-8"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("svg", "image/svg+xml")
    );

    /**
     * 查询摄取配置的表单 schema：描述的正是上传与更新接口里的 {@code ingestionSpec} 字段。
     *
     * <p>GET ⇒ 字节分支 ⇒ 带回执头（见类注释）。
     */
    @GetMapping("/knowledge-base/docs/ingestion-spec-schema")
    public ResponseEntity<ApiEnvelope<IngestionSpecSchemaVO>> getIngestionSpecSchema() {
        ExecutionPrincipal principal = PrincipalContext.require();
        IngestionSpecSchemaVO schema = ingestionSpecSchemaProvider.describe();
        return permitJson(principal, "config.read", "ingestion-spec", schema);
    }

    /**
     * 上传文档：入库记录 + 文件落盘，返回文档ID
     */
    @PostMapping(value = "/knowledge-base/{kb-id}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<KnowledgeDocumentVO> upload(@PathVariable("kb-id") String kbId,
                                                   @RequestPart(value = "file", required = false) MultipartFile file,
                                                   @ModelAttribute KnowledgeDocumentUploadRequest requestParam) {
        return ApiEnvelope.ok(documentService.upload(kbId, requestParam, file));
    }

    /**
     * 开始分块：抽取文本 -> 分块 -> 嵌入并写入向量库
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunk")
    public ApiEnvelope<Void> startChunk(@PathVariable(value = "doc-id") String docId) {
        documentService.startChunk(docId);
        return ApiEnvelope.ok(null);
    }

    /**
     * 删除文档：逻辑删除。可选同时删除向量库中该文档的所有 chunk
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}")
    public ApiEnvelope<Void> delete(@PathVariable(value = "doc-id") String docId) {
        documentService.delete(docId);
        return ApiEnvelope.ok(null);
    }

    /**
     * 查询文档详情
     *
     * <p>GET ⇒ 字节分支 ⇒ 带回执头（见类注释）。
     */
    @GetMapping("/knowledge-base/docs/{docId}")
    public ResponseEntity<ApiEnvelope<KnowledgeDocumentVO>> get(@PathVariable String docId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        KnowledgeDocumentVO document = documentService.get(docId);
        return permitJson(principal, "document.read", "doc:" + docId, document);
    }

    /**
     * 更新文档信息
     *
     * <p>body 可选携带 {@code expectedVersion}（取自详情/列表行的 {@code version}）：
     * 版本不符时<b>不写入任何字段</b>，由服务层抛业务异常（与本族同一模板）。
     * 缺省该字段按无并发校验处理。
     */
    @PutMapping("/knowledge-base/docs/{docId}")
    public ApiEnvelope<Void> update(@PathVariable String docId,
                                    @RequestBody KnowledgeDocumentUpdateRequest requestParam) {
        documentService.update(docId, requestParam);
        return ApiEnvelope.ok(null);
    }

    /**
     * 分页查询文档列表（支持状态/关键字过滤）
     *
     * <p>GET ⇒ 字节分支 ⇒ 带回执头（见类注释）。
     */
    @GetMapping("/knowledge-base/{kb-id}/docs")
    public ResponseEntity<ApiEnvelope<IPage<KnowledgeDocumentVO>>> page(
            @PathVariable(value = "kb-id") String kbId,
            KnowledgeDocumentPageRequest requestParam) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<KnowledgeDocumentVO> documents = documentService.page(kbId, requestParam);
        return permitJson(principal, "document.list", "kb:" + kbId, documents);
    }

    /**
     * 搜索文档（全局检索建议）
     *
     * <p>GET ⇒ 字节分支 ⇒ 带回执头（见类注释）。
     */
    @GetMapping("/knowledge-base/docs/search")
    public ResponseEntity<ApiEnvelope<List<KnowledgeDocumentSearchVO>>> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", defaultValue = "8") int limit) {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<KnowledgeDocumentSearchVO> hits = documentService.search(keyword, limit);
        return permitJson(principal, "document.list", "document:search", hits);
    }

    /**
     * 启用/禁用文档
     *
     * <p>{@code expectedVersion} 可选（query 参数，取值同详情行的 {@code version}）：
     * 带值时版本不符在任何写入（含向量重建）之前拒绝。
     */
    @PatchMapping("/knowledge-base/docs/{docId}/enable")
    public ApiEnvelope<Void> enable(@PathVariable String docId,
                                    @RequestParam("value") boolean enabled,
                                    @RequestParam(value = "expectedVersion", required = false) Long expectedVersion) {
        documentService.enable(docId, enabled, expectedVersion);
        return ApiEnvelope.ok(null);
    }

    /**
     * 查询文档分块日志列表
     *
     * <p>GET ⇒ 字节分支 ⇒ 带回执头（见类注释）。
     */
    @GetMapping("/knowledge-base/docs/{docId}/chunk-logs")
    public ResponseEntity<ApiEnvelope<IPage<KnowledgeDocumentChunkLogVO>>> getChunkLogs(
            @PathVariable String docId, Page<KnowledgeDocumentChunkLogVO> page) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<KnowledgeDocumentChunkLogVO> logs = documentService.getChunkLogs(docId, page);
        return permitJson(principal, "document.read", "doc:" + docId, logs);
    }

    /**
     * 预览 markdown 文档内容
     *
     * <p>GET ⇒ 字节分支 ⇒ 带回执头（见类注释）。
     */
    @GetMapping("/knowledge-base/docs/{docId}/preview")
    public ResponseEntity<ApiEnvelope<String>> preview(@PathVariable String docId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String markdown = documentService.preview(docId);
        return permitJson(principal, "document.read", "doc:" + docId, markdown);
    }

    /**
     * 获取文档源文件（用于 PDF/图片等浏览器原生支持的格式直接渲染）
     *
     * <p>裸字节面：不吃 JSON 信封，但网关的字节分支对 200 响应<b>同样</b>要求两个
     * {@code X-AI-Delivery-*} 头（{@code LocalAiGatewayClient.java:163-170}），
     * 否则 {@code delivery receipt missing}。因此这里也要铸许可。
     */
    @GetMapping("/knowledge-base/docs/{docId}/file")
    public void file(@PathVariable String docId, HttpServletResponse response) throws Exception {
        ExecutionPrincipal principal = PrincipalContext.require();
        var doc = documentService.get(docId);
        String fileType = doc.getFileType() != null ? doc.getFileType().toLowerCase() : "";
        String contentType = CONTENT_TYPE_MAP.getOrDefault(fileType, "application/octet-stream");
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "document.download", "doc:" + docId);
        try {
            response.setContentType(contentType);
            response.setHeader("Content-Disposition", ContentDisposition.inline()
                    .filename(doc.getDocName(), StandardCharsets.UTF_8)
                    .build()
                    .toString());
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-AI-Delivery-Permit", permit.permitId());
            response.setHeader("X-AI-Delivery-Operation", permit.operationId());
            try (InputStream in = fileStorageService.openStream(doc.getFileUrl())) {
                StreamUtils.copy(in, response.getOutputStream());
            }
        } catch (IOException | RuntimeException e) {
            // 取流失败不留 ACTIVE 许可（否则屏障永远排不空）
            permit.close();
            throw e;
        }
    }

    /**
     * GET 的统一出口：取数 → 铸许可 → 带两个回执头返回整数信封。
     *
     * <p>顺序不能反：先取数据再铸许可，数据侧失败时不会留下需要回收的 ACTIVE 许可。
     * 取值失败已在方法体内发生，不会走到 {@code permits.enter}。
     */
    private <T> ResponseEntity<ApiEnvelope<T>> permitJson(ExecutionPrincipal principal, String action,
                                                         String resourceRef, T data) {
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, action, resourceRef);
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(data));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }
}
