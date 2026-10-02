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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权域内部端点（P1.3a）：{@code /internal/ai/v1} 下的 KB 元数据与 ACL 管理。
 *
 * <p><b>默认不装配</b>（{@code ai.integration.enabled=false}）：默认配置下本控制器
 * 不存在，404 由既有边界保证，不新增任何可达面。
 *
 * <p>错误外显（05 §4.2，HTTP status == body.code）：
 * <ul>
 *   <li>跨租户 / 无权 / 不存在：一律 404（body code=404），不泄露存在性；</li>
 *   <li>缺主体 / 动作未授权：403；</li>
 *   <li>版本陈旧（pv/av）：409；</li>
 *   <li>授权事实源不可用：503，不放行。</li>
 * </ul>
 * 判定统一走 {@link ResourceAuthorizationService}（DENY/STALE/UNKNOWN 收敛为
 * 404/409/503）；响应<b>不含</b>内部对象 key / 凭据。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiResourceController {

    /** 只读动作：KB 元数据与列表。 */
    public static final String ACTION_KB_READ = "kb.read";
    /** 删除动作（tombstone）。 */
    public static final String ACTION_KB_DELETE = "kb.delete";
    /** 文档元数据只读动作。 */
    public static final String ACTION_DOC_READ = "doc.read";

    private final AiResourceAuthorizationService authorization;
    private final AiResourceWriteService writeService;

    public AiResourceController(AiResourceAuthorizationService authorization,
                                AiResourceWriteService writeService) {
        this.authorization = authorization;
        this.writeService = writeService;
    }

    // ------------------------------------------------------------ DTO（嵌套 record，无归属字段可由 body 提供）

    /** KB 创建请求：刻意没有 tenant/owner 字段——归属只来自执行主体。 */
    public record CreateKnowledgeBaseRequest(String name, String embeddingModel, String collectionName) {
    }

    /** ACL 授权/撤销请求：subjectType 取 framework 编码（member/department/role/tenant_all）。 */
    public record AclRuleRequest(String subjectType, String subjectId, String action, Long expiresAtEpochSecond) {
    }

    // ------------------------------------------------------------ KB 查询

    /** 当前主体可读的 KB 列表（空授权 = 空列表，不回落全库）。 */
    @GetMapping("/knowledge-bases")
    public ResponseEntity<ApiEnvelope<List<Map<String, Object>>>> listKnowledgeBases() {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<Map<String, Object>> data = new ArrayList<>();
        for (String ref : authorization.resolveScope(principal, ACTION_KB_READ, List.of()).authorizedRefs()) {
            String kbId = AiResourceAuthorizationService.parseResourceRef(ref).resourceId();
            writeService.findKnowledgeBase(principal.tenantId(), kbId)
                    .ifPresent(view -> data.add(kbView(view)));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    /** 单个 KB：跨租户/无权/不存在一律 404。 */
    @GetMapping("/knowledge-bases/{kbId}")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> getKnowledgeBase(@PathVariable String kbId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String ref = AiResourceAuthorizationService.resourceRef(AiResourceMapper.TYPE_KB, kbId);
        requireGrant(principal, ACTION_KB_READ, ref);
        Map<String, Object> data = writeService.findKnowledgeBase(principal.tenantId(), kbId)
                .map(this::kbView)
                .orElseThrow(() -> new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        data.put("documentRefs", authorization.childResourceRefs(principal.tenantId(), ref));
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    // ------------------------------------------------------------ KB 写入

    /** 创建 KB：归属来自主体；同一事务写元数据 + registry + owner ACL + epoch bump。 */
    @PostMapping("/knowledge-bases")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> createKnowledgeBase(
            @RequestBody CreateKnowledgeBaseRequest request) {
        PrincipalContext.require();
        String kbId = writeService.createKnowledgeBase(
                new AiResourceWriteService.KnowledgeBaseDraft(
                        request == null ? null : request.name(),
                        request == null ? null : request.embeddingModel(),
                        request == null ? null : request.collectionName()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    /** 删除 KB（tombstone）：判定层 kb.delete 通过后同事务 tombstone + epoch bump。 */
    @DeleteMapping("/knowledge-bases/{kbId}")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> deleteKnowledgeBase(@PathVariable String kbId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        requireGrant(principal, ACTION_KB_DELETE,
                AiResourceAuthorizationService.resourceRef(AiResourceMapper.TYPE_KB, kbId));
        writeService.tombstoneKnowledgeBase(kbId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        data.put("status", "TOMBSTONED");
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    // ------------------------------------------------------------ ACL 管理

    /** 新增授权：subject 同租户、操作者持 kb.acl.manage 且是 owner 或显式 grant，原子 bump。 */
    @PutMapping("/knowledge-bases/{kbId}/acl")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> grantAcl(
            @PathVariable String kbId,
            @RequestBody AclRuleRequest request) {
        PrincipalContext.require();
        writeService.grantAclRule(kbId, toGrant(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        data.put("granted", true);
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    /** 撤销授权：撤权即删行 + 原子 bump；其它主体下一次判定立刻收窄。 */
    @DeleteMapping("/knowledge-bases/{kbId}/acl")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> revokeAcl(
            @PathVariable String kbId,
            @RequestParam String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam String action) {
        PrincipalContext.require();
        writeService.revokeAclRule(kbId, new AiResourceWriteService.AclGrant(
                subjectType, subjectId, action, null));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", kbId);
        data.put("granted", false);
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    // ------------------------------------------------------------ 文档查询

    /** 单个文档：判定走 doc.read；响应不含 file_url（内部对象 key）。 */
    @GetMapping("/documents/{docId}")
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> getDocument(@PathVariable String docId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String ref = AiResourceAuthorizationService.resourceRef(AiResourceMapper.TYPE_DOCUMENT, docId);
        requireGrant(principal, ACTION_DOC_READ, ref);
        Map<String, Object> data = writeService.findDocument(principal.tenantId(), docId)
                .map(doc -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("docId", doc.docId());
                    view.put("kbId", doc.kbId());
                    view.put("docName", doc.docName());
                    view.put("status", doc.status());
                    view.put("enabled", doc.enabled());
                    view.put("chunkCount", doc.chunkCount());
                    return view;
                })
                .orElseThrow(() -> new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    // ------------------------------------------------------------ 判定收敛

    /** GRANT 放行；DENY→404、STALE→409、UNKNOWN→503（对外状态与语义一一对应）。 */
    private void requireGrant(ExecutionPrincipal principal, String action, String ref) {
        Verdict verdict = authorization.check(principal, action, ref);
        switch (verdict) {
            case GRANT -> {
                // 已授权
            }
            case DENY -> throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            case STALE -> throw new StaleVersionException(
                    "aclVersion changed; refetch current versions and retry");
            case UNKNOWN -> throw new ServiceException(
                    "resource authorization is unavailable; refusing to serve");
        }
    }

    private Map<String, Object> kbView(AiResourceWriteService.KnowledgeBaseView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kbId", view.kbId());
        data.put("name", view.name());
        data.put("embeddingModel", view.embeddingModel());
        data.put("collectionName", view.collectionName());
        data.put("ownerDeptId", view.ownerDeptId());
        return data;
    }

    private static AiResourceWriteService.AclGrant toGrant(AclRuleRequest request) {
        if (request == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
        }
        return new AiResourceWriteService.AclGrant(request.subjectType(), request.subjectId(),
                request.action(), request.expiresAtEpochSecond());
    }

    // ------------------------------------------------------------ 错误映射（HTTP status == body.code）

    @ExceptionHandler(P04AiException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleP04(P04AiException ex) {
        P04AiErrorCode code = ex.errorCode();
        return ResponseEntity.status(code.httpStatus())
                .body(ApiEnvelope.error(code.httpStatus(), ex.getMessage(), code.name()));
    }

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleClient(ClientException ex) {
        // 缺主体等主体上下文问题：403（不区分"没带"与"带了不完整"，避免给探测者反馈）
        return ResponseEntity.status(P04AiErrorCode.TENANT_CONTEXT_MISSING.httpStatus())
                .body(ApiEnvelope.error(P04AiErrorCode.TENANT_CONTEXT_MISSING.httpStatus(),
                        ex.getMessage(), P04AiErrorCode.TENANT_CONTEXT_MISSING.name()));
    }

    @ExceptionHandler(StaleVersionException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleStale(StaleVersionException ex) {
        return ResponseEntity.status(409)
                .body(ApiEnvelope.error(409, ex.getMessage(), P04AiErrorCode.POLICY_VERSION_STALE.name()));
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleService(ServiceException ex) {
        // 授权事实源不可用：503，不放行
        return ResponseEntity.status(503)
                .body(ApiEnvelope.error(503, ex.getMessage(), P04AiErrorCode.AUTHORIZATION_UNAVAILABLE.name()));
    }
}
