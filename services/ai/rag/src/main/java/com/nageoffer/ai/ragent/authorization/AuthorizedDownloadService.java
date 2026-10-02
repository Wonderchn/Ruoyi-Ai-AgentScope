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

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Optional;

/**
 * 授权下载（P1.3c）：私有对象的唯一读取入口。
 *
 * <p><b>为什么不能直接给 openStream 一把 key。</b>对象存储的 key 不是授权凭据：
 * 任何能读到 key 的地方（日志、导出、前端直链）都等价于拿到读取权。
 * 本服务的入参是<b>资源引用</b>（{@code doc:<id>}），流程固定为：
 *
 * <ol>
 *   <li>执行主体必须存在（{@link PrincipalContext#require()}）；</li>
 *   <li>当前资源授权判定（{@link ResourceAuthorizationService#check}，动作
 *       {@code document.download}）：DENY→404、STALE→409、UNKNOWN→503，
 *       <b>未判定通过不接触对象存储</b>；</li>
 *   <li>registry 对象绑定必须存在且 ACTIVE（未登记即 404，与不存在同外显）；</li>
 *   <li>最后才经 {@link FileStorageService#openStream} 取流——它的归属前缀校验
 *       是第二道防线，不是第一道。</li>
 * </ol>
 *
 * <p>不返回 raw key、不提供 public URL：客户私有内容只能经授权流获取。
 * 默认不装配（{@code ai.integration.enabled=true} 时才有客户下载面）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AuthorizedDownloadService {

    /** 下载动作的 canonical 名（与 05 §4.2 动作表一致）。 */
    public static final String ACTION_DOCUMENT_DOWNLOAD = "document.download";

    private final ObjectProvider<ResourceAuthorizationService> authorizationService;
    private final TenantObjectReferenceRepository objectReferences;
    private final ObjectProvider<FileStorageService> fileStorage;

    public AuthorizedDownloadService(ObjectProvider<ResourceAuthorizationService> authorizationService,
                                     TenantObjectReferenceRepository objectReferences,
                                     ObjectProvider<FileStorageService> fileStorage) {
        this.authorizationService = authorizationService;
        this.objectReferences = objectReferences;
        this.fileStorage = fileStorage;
    }

    /**
     * 按文档资源引用打开授权流。
     *
     * @throws P04AiException 404=无权/不存在、409=版本过期、503=授权事实源不可用
     */
    public InputStream openDocumentStream(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST);
        }
        ExecutionPrincipal principal = PrincipalContext.require();

        ResourceAuthorizationService authorization = authorizationService.getIfAvailable();
        if (authorization == null) {
            // 客户能力已开但授权服务缺席：拒绝而不是放行（fail-closed）
            log.error("下载被拒绝：授权服务不可用, tenantId={}", principal.tenantId());
            throw new ServiceException("授权服务不可用");
        }

        String ref = "doc:" + documentId;
        Verdict verdict = authorization.check(principal, ACTION_DOCUMENT_DOWNLOAD, ref);
        switch (verdict) {
            case DENY -> throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            case STALE -> throw new com.nageoffer.ai.ragent.framework.security.StaleVersionException(
                    "policy/acl version changed");
            case UNKNOWN -> throw new ServiceException("授权事实源不可用，下载已拒绝");
            case GRANT -> {
                // 判定通过，继续
            }
        }

        Optional<TenantObjectReferenceRepository.DocumentStorage> storage =
                objectReferences.findDocumentStorage(principal.tenantId(), documentId);
        if (storage.isEmpty()) {
            // 未登记 / 跨租户 / 已删除同外显：不区分比区分更安全
            throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
        }

        FileStorageService storageService = fileStorage.getIfAvailable();
        if (storageService == null) {
            throw new ServiceException("对象存储不可用");
        }
        // openStream 内部再做一次租户前缀归属校验（防御纵深）；key 不出本方法
        return storageService.openStream(storage.get().fileUrl());
    }

    /**
     * 文档对象登记（上传成功后调用）：把 key 与资源绑定写进 registry。
     *
     * <p>无执行主体拒绝；重复登记由调用方决定幂等策略（{@code DuplicateKeyException} 上抛）。
     */
    public void registerDocumentObject(String documentId, String fileUrl, String mimeType) {
        if (documentId == null || documentId.isBlank() || fileUrl == null || fileUrl.isBlank()) {
            throw new ClientException("documentId 与 fileUrl 均不能为空");
        }
        ExecutionPrincipal principal = PrincipalContext.require();
        String objectSegment = TenantObjectReferenceRepository.objectSegmentOf(fileUrl);
        objectReferences.register(principal.tenantId(), objectSegment, "DOC", documentId,
                principal.membershipId(), principal.membershipId());
    }
}
