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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.web.RunApiResponses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 私有上传/文档接口（内部前缀；网关专用流式上传与普通 JSON 转发分别接入）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@ConditionalOnProperty(name = "p04.enabled", havingValue = "false", matchIfMissing = true)
public class UploadController {

    private final UploadService uploadService;
    private final com.nageoffer.ai.ragent.runtime.web.DeliveryPermits permits;

    public UploadController(UploadService uploadService,
                            com.nageoffer.ai.ragent.runtime.web.DeliveryPermits permits) {
        this.uploadService = uploadService;
        this.permits = permits;
    }

    public record IngestionRequest(String uploadId) {
    }

    @PostMapping(value = "/documents/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file,
                                    @RequestPart("kbId") String kbId,
                                    @RequestPart(value="docId",required=false) String targetDocId,
                                    @RequestHeader(value="Idempotency-Key",required=false) String key,
                                    jakarta.servlet.http.HttpServletRequest rawRequest) {
        String requestId = requestId();
        try {
            validateParts(rawRequest);
            UploadService.UploadResult result = uploadService.upload(principal(), kbId, file,
                    key==null ? UUID.randomUUID().toString() : key,targetDocId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("uploadId", result.uploadId());
            data.put("docId", result.docId());
            data.put("versionId", result.versionId());
            data.put("sha256", result.sha256());
            data.put("sizeBytes", result.sizeBytes());
            data.put("state", result.state());
            return RunApiResponses.ok(HttpStatus.CREATED, data, requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        }
    }

    @PostMapping("/documents/{docId}/ingestions")
    public ResponseEntity<?> createIngestion(@PathVariable String docId,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @RequestBody(required = false) IngestionRequest request) {
        String requestId = requestId();
        try {
            if (request == null || request.uploadId() == null || request.uploadId().isBlank()) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "uploadId is required");
            }
            Map<String, Object> view = uploadService.createIngestion(principal(), docId, key, request.uploadId());
            return RunApiResponses.accepted(view, requestId);
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        }
    }

    @GetMapping("/documents/{docId}/meta")
    public ResponseEntity<?> document(@PathVariable String docId) {
        String requestId = requestId();
        com.nageoffer.ai.ragent.runtime.web.DeliveryPermits.Permit permit = null;
        try {
            ExecutionPrincipal principal = principal();
            Map<String, Object> view = uploadService.documentView(principal, docId);
            permit = permits.enter(principal, "document.read", "doc:" + docId);
            return RunApiResponses.okWithHeaders(HttpStatus.OK, view, requestId, Map.of(
                    "X-AI-Delivery-Permit", permit.permitId(),
                    "X-AI-Delivery-Operation", permit.operationId()));
        } catch (RunApiException e) {
            if (permit != null) {
                permit.close();
            }
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        }
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<?> documents(@PathVariable String kbId) {
        String requestId = requestId();
        com.nageoffer.ai.ragent.runtime.web.DeliveryPermits.Permit permit = null;
        try {
            ExecutionPrincipal principal = principal();
            java.util.List<Map<String, Object>> views = uploadService.listDocuments(principal, kbId);
            permit = permits.enter(principal, "document.list", "kb:" + kbId);
            return RunApiResponses.okWithHeaders(HttpStatus.OK, views, requestId, Map.of(
                    "X-AI-Delivery-Permit", permit.permitId(),
                    "X-AI-Delivery-Operation", permit.operationId()));
        } catch (RunApiException e) {
            if (permit != null) {
                permit.close();
            }
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        }
    }

    @GetMapping("/documents/{docId}/source")
    public ResponseEntity<byte[]> content(@PathVariable String docId,
                                          @RequestParam(value = "versionId", required = false) String versionId) {
        ExecutionPrincipal principal = principal();
        UploadService.PrivateDocument document = uploadService.content(principal, docId, versionId);
        var permit = permits.enter(principal, "document.download", "doc:" + docId);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-AI-Delivery-Permit", permit.permitId())
                .header("X-AI-Delivery-Operation", permit.operationId())
                .contentType(MediaType.APPLICATION_PDF)
                .body(document.bytes());
    }

    @PostMapping("/documents/{docId}/tombstone")
    public ResponseEntity<?> tombstone(@PathVariable String docId) {
        String requestId = requestId();
        try {
            var principal=principal();
            try(var permit=permits.enter(principal,"kb.delete","doc:"+docId)) {
                return RunApiResponses.ok(HttpStatus.OK, uploadService.tombstone(principal, docId), requestId);
            }
        } catch (RunApiException e) {
            return RunApiResponses.fail(e.errorCode(), e.getMessage(), requestId);
        }
    }

    private void validateParts(jakarta.servlet.http.HttpServletRequest request) {
        try {
            var names=new java.util.HashSet<String>();
            for(var part:request.getParts()) {
                if(!java.util.Set.of("file","kbId","docId").contains(part.getName()) || !names.add(part.getName()))
                    throw new RunApiException(RunErrorCode.BAD_REQUEST,"unknown or duplicate multipart part");
            }
        } catch(java.io.IOException | jakarta.servlet.ServletException e) {throw new RunApiException(RunErrorCode.BAD_REQUEST,"invalid multipart input");}
    }

    private ExecutionPrincipal principal() {
        if (!PrincipalContext.hasPrincipal()) {
            throw new RunApiException(RunErrorCode.AUTH_REQUIRED);
        }
        return PrincipalContext.require();
    }

    private String requestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "");
    }
}
