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

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.dto.AdmissionRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 私有 PDF 上传与文档版本摄入（U04）。
 *
 * <p>安全边界：服务端生成 docId/uploadId/objectKey；校验 MIME、%PDF 魔数、文件名与
 * 大小；不信任客户端 tenant/objectKey；先落私有对象再写元数据，失败留可核对状态。
 * 资源级授权统一用既有 KB ACL 的 {@code kb.read}（能力级权限由平台网关按角色判定；
 * 空 scope 不放行全库）。
 */
@Service
public class UploadService {

    private static final String PDF_MAGIC = "%PDF-";

    private final DocumentDao documentDao;
    private final PrivateObjectStore objectStore;
    private final RunAdmissionService admission;
    private final P2RuntimeProperties properties;
    private final P2FaultInjector faults;
    private final UploadIntentService intents;
    private final ObjectProvider<com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization;

    public UploadService(DocumentDao documentDao, PrivateObjectStore objectStore, RunAdmissionService admission,
                         P2RuntimeProperties properties, P2FaultInjector faults, UploadIntentService intents,
                         ObjectProvider<com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization) {
        this.documentDao = documentDao;
        this.objectStore = objectStore;
        this.admission = admission;
        this.properties = properties;
        this.faults = faults;
        this.intents = intents;
        this.authorization = authorization;
    }

    public record UploadResult(String uploadId, String docId, String versionId, String sha256, long sizeBytes,
                               String state) {
    }

    public UploadResult upload(ExecutionPrincipal principal, String kbId, MultipartFile file) {
        return upload(principal,kbId,file,UUID.randomUUID().toString());
    }

    public UploadResult upload(ExecutionPrincipal principal, String kbId, MultipartFile file, String key) {
        return upload(principal,kbId,file,key,null);
    }
    public UploadResult upload(ExecutionPrincipal principal, String kbId, MultipartFile file, String key,String targetDocId) {
        if (kbId == null || kbId.isBlank() || !kbId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "kbId is required and must be a plain identifier");
        }
        requireKbRead(principal, kbId);
        if(targetDocId!=null) {
            if(!targetDocId.matches("[A-Za-z0-9_-]{1,64}")) throw new RunApiException(RunErrorCode.BAD_REQUEST);
            var target=documentDao.findDocument(principal.tenantId(),targetDocId).orElseThrow(()->new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
            if(target.tombstoned() || !target.kbId().equals(kbId) || authorization.getObject().check(
                    com.nageoffer.ai.ragent.runtime.RunAccessService.scoped(principal,java.util.Set.of("document.read")),"document.read","doc:"+targetDocId)!=ResourceAuthorizationService.Verdict.GRANT)
                throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        if (file == null || file.isEmpty()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "file is required");
        }
        long maxBytes = properties.getUpload().getMaxBytes();
        if (file.getSize() > maxBytes) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "file exceeds the configured upload limit");
        }
        String mime = file.getContentType();
        if (mime == null || !mime.split(";",2)[0].trim().equalsIgnoreCase(properties.getUpload().getAllowedMime())) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "only application/pdf uploads are accepted");
        }
        String filename = sanitizeFilename(file.getOriginalFilename());
        try (InputStream input = file.getInputStream()) {
            byte[] magic = input.readNBytes(PDF_MAGIC.length());
            if (!PDF_MAGIC.equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new RunApiException(RunErrorCode.BAD_REQUEST, "file content is not a PDF");
            }
        } catch (IOException e) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "upload stream unavailable");
        }

        String sha;
        try (InputStream in=file.getInputStream()) {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[65536]; long readBytes=0; int n;
            while((n=in.read(buffer))!=-1) {readBytes+=n; if(readBytes>maxBytes){throw new RunApiException(RunErrorCode.BAD_REQUEST);} digest.update(buffer,0,n);}
            sha=java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException | IOException e) {throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE);}
        String requestHash=com.nageoffer.ai.ragent.runtime.CanonicalJson.sha256(kbId+"\n"+(targetDocId==null?"":targetDocId)+"\n"+filename+"\n"+file.getSize()+"\n"+sha);
        var intent=intents.begin(principal,key,requestHash,sha,file.getSize(),targetDocId);
        String docId=intent.docId(), uploadId=intent.uploadId(), versionId=intent.versionId(), objectKey=intent.objectKey();
        if("STORED".equals(intent.state())) {return new UploadResult(uploadId,docId,versionId,sha,intent.size(),"STORED");}
        PrivateObjectStore.StoredObject stored;
        try (InputStream input = file.getInputStream()) {
            stored = objectStore.putStream(objectKey, input, maxBytes);
        } catch (IOException e) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "upload stream unavailable");
        }
        if (stored.sizeBytes() > maxBytes) {
            objectStore.delete(objectKey);
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "file exceeds the configured upload limit");
        }
        if(!sha.equals(stored.sha256()) || file.getSize()!=stored.sizeBytes()) {
            objectStore.delete(objectKey);
            throw new RunApiException(RunErrorCode.BAD_REQUEST,"upload content changed");
        }
        requireKbRead(principal,kbId);
        // A25：对象已落盘、元数据未写；原 intent 可恢复并保留同一服务器键。
        faults.checkpoint(P2FaultInjector.UPLOAD_AFTER_OBJECT_STORE);
        intents.complete(principal,key,requestHash,() -> {
            if(targetDocId==null) documentDao.insertDocument(principal.tenantId(), docId, kbId, filename, principal.membershipId());
            documentDao.insertUpload(principal.tenantId(), uploadId, docId, kbId, principal.membershipId(),
                    filename, "application/pdf", stored.sizeBytes(), stored.sha256(), objectKey);
            documentDao.insertVersion(principal.tenantId(), versionId, docId, uploadId, null);
        });
        // A25：原子元数据提交后响应丢失；原 key 重试复用同一 intent。
        faults.checkpoint(P2FaultInjector.UPLOAD_AFTER_DB);
        return new UploadResult(uploadId, docId, versionId, stored.sha256(), stored.sizeBytes(), "STORED");
    }

    /** 幂等创建文档版本摄入 run（正式 document.ingest 受理）。 */
    public Map<String, Object> createIngestion(ExecutionPrincipal principal, String docId, String idempotencyKey,
                                               String uploadId) {
        DocumentDao.DocumentRow document = documentDao.findDocument(principal.tenantId(), docId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (document.tombstoned()) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        requireKbRead(principal, document.kbId());
        DocumentDao.UploadRow upload = documentDao.findUpload(principal.tenantId(), uploadId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (!docId.equals(upload.docId()) || !"STORED".equals(upload.state())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        DocumentDao.VersionRow version = documentDao.findVersionByUpload(principal.tenantId(), docId, uploadId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.INTERNAL_ERROR, "document version missing"));
        if ("TOMBSTONED".equals(version.state())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("docId", docId);
        input.put("uploadId", uploadId);
        input.put("versionId", version.versionId());
        input.put("kbId", document.kbId());
        AdmissionRequest request = new AdmissionRequest(1, "document.ingest", null, null,
                com.nageoffer.ai.ragent.runtime.CanonicalJson.strictMapper().valueToTree(input),
                List.of(new AdmissionRequest.ResourceRef("knowledge_base", document.kbId()),
                        new AdmissionRequest.ResourceRef("document", docId)),
                null, null, null);
        RunAdmissionService.AdmissionResult result = admission.admit(principal, idempotencyKey, request);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", result.runId());
        view.put("status", result.status());
        view.put("docId", docId);
        view.put("uploadId", uploadId);
        view.put("versionId", version.versionId());
        view.put("replayed", result.replayed());
        return view;
    }

    public Map<String, Object> documentView(ExecutionPrincipal principal, String docId) {
        DocumentDao.DocumentRow document = documentDao.findDocument(principal.tenantId(), docId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        requireKbRead(principal, document.kbId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("docId", document.docId());
        view.put("kbId", document.kbId());
        view.put("name", document.name());
        view.put("publishedVersionId", document.publishedVersionId());
        view.put("tombstonedAt", document.tombstonedAt());
        view.put("createdAt", document.createdAt());
        List<Map<String, Object>> versions = new ArrayList<>();
        if (document.publishedVersionId() != null) {
            documentDao.findVersion(principal.tenantId(), document.publishedVersionId()).ifPresent(version ->
                    versions.add(versionView(version)));
        }
        view.put("versions", versions);
        return view;
    }

    public List<Map<String, Object>> listDocuments(ExecutionPrincipal principal, String kbId) {
        requireKbRead(principal, kbId);
        List<Map<String, Object>> views = new ArrayList<>();
        for (DocumentDao.DocumentRow document : documentDao.listDocuments(principal.tenantId(), kbId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("docId", document.docId());
            view.put("kbId", document.kbId());
            view.put("name", document.name());
            view.put("publishedVersionId", document.publishedVersionId());
            view.put("tombstoned", document.tombstoned());
            view.put("createdAt", document.createdAt());
            views.add(view);
        }
        return views;
    }

    private Map<String, Object> versionView(DocumentDao.VersionRow version) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("versionId", version.versionId());
        view.put("state", version.state());
        view.put("chunkCount", version.chunkCount());
        view.put("embeddingModel", version.embeddingModel());
        view.put("embeddingDimension", version.embeddingDimension());
        view.put("publishedAt", version.publishedAt());
        return view;
    }

    public record PrivateDocument(byte[] bytes, String mimeType, String filename) {
    }

    public PrivateDocument content(ExecutionPrincipal principal, String docId, String versionId) {
        DocumentDao.DocumentRow document = documentDao.findDocument(principal.tenantId(), docId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (document.tombstoned()) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        requireKbRead(principal, document.kbId());
        String targetVersion = versionId == null || versionId.isBlank() ? document.publishedVersionId() : versionId;
        if(targetVersion==null || !targetVersion.equals(document.publishedVersionId())
                || authorization.getObject().check(com.nageoffer.ai.ragent.runtime.RunAccessService.scoped(principal,java.util.Set.of("document.read")),"document.read","doc:"+docId)!=ResourceAuthorizationService.Verdict.GRANT)
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        DocumentDao.VersionRow version = documentDao.findVersion(principal.tenantId(), targetVersion)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        if (!docId.equals(version.docId()) || !"PUBLISHED".equals(version.state())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
        DocumentDao.UploadRow upload = documentDao.findUpload(principal.tenantId(), version.uploadId())
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        byte[] bytes = objectStore.get(upload.objectKey());
        return new PrivateDocument(bytes, upload.mimeType(), upload.filename());
    }

    /** 删除：先 tombstone 阻断检索，再异步清理（P2 首期同步清理分块，对象保留审计）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public Map<String, Object> tombstone(ExecutionPrincipal principal, String docId) {
        DocumentDao.DocumentRow document = documentDao.findDocument(principal.tenantId(), docId)
                .orElseThrow(() -> new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        requireKbRead(principal, document.kbId());
        if(authorization.getObject().check(principal,"kb.delete","kb:"+document.kbId())!=ResourceAuthorizationService.Verdict.GRANT)
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        documentDao.tombstoneDocument(principal.tenantId(), docId);
        documentDao.tombstoneVersions(principal.tenantId(), docId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("docId", docId);
        view.put("status", "TOMBSTONED");
        return view;
    }

    private void requireKbRead(ExecutionPrincipal principal, String kbId) {
        var service = authorization.getIfAvailable();
        if (service == null) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
        }
        ResourceAuthorizationService.Verdict verdict;
        try {
            for(String action:principal.scopes()) service.requireFunction(principal,action,"kb:"+kbId);
            verdict = service.check(com.nageoffer.ai.ragent.runtime.RunAccessService.scoped(principal,java.util.Set.of("kb.read")), "kb.read", "kb:" + kbId);
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE, "kb authorization unavailable");
        }
        if (verdict != ResourceAuthorizationService.Verdict.GRANT) {
            // 不存在与无权同形，不泄露存在性
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "filename is required");
        }
        if(original.contains("/") || original.contains("\\")) throw new RunApiException(RunErrorCode.BAD_REQUEST, "filename is not acceptable");
        String name = original;
        name = name.strip();
        if (name.isEmpty() || name.contains("..") || name.length() > 200) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "filename is not acceptable");
        }
        return name;
    }

    /** 供 executor 读取 run.input 中的文档引用。 */
    public static JsonNode inputOf(String inputJson, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return com.nageoffer.ai.ragent.runtime.CanonicalJson.inputOf(inputJson, mapper);
    }
}
