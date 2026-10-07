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

import com.nageoffer.ai.ragent.framework.cache.AuthorizedCacheKey;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import org.ruoyi.ai.api.runtime.DocumentFileReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * P1.3c 护栏：授权下载与授权缓存键。
 *
 * <p>两条纪律各钉一组断言：
 * <ul>
 *   <li><b>下载</b>：授权判定在对象存储之前——DENY/UNKNOWN/未登记时
 *       {@code DocumentFileReader} 与对象存储必须<b>零交互</b>
 *       （"拒绝"与"查了但没命中"必须可区分）；</li>
 *   <li><b>缓存键</b>：形状固定且字段缺失/含冒号一律拒绝；键含 pv/av，
 *       撤权 bump 后旧键自然失配——但命中不豁免授权是调用方职责，
 *       类注释已写明，这里钉住形状契约。</li>
 * </ul>
 */
@Tag("dev")
class P1ObjectAndCacheIsolationTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";

    @AfterEach
    void clear() {
        PrincipalContext.clear();
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT, "2101", MEMBER, 7, 3,
                Set.of("ai:document:download"), "jti-t1-1", "platform",
                1_700_000_000L, 1_700_000_060L);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    // ---------------------------------------------------------------- 下载

    @Test
    @DisplayName("无执行主体：拒绝且不触碰授权服务与对象存储")
    void downloadWithoutPrincipalIsRejected() {
        ResourceAuthorizationService authz = mock(ResourceAuthorizationService.class);
        TenantObjectReferenceRepository refs = mock(TenantObjectReferenceRepository.class);
        DocumentFileReader storage = mock(DocumentFileReader.class);
        AuthorizedDownloadService service = new AuthorizedDownloadService(
                providerOf(authz), refs, providerOf(storage));

        assertThatThrownBy(() -> service.openDocumentStream("doc-1"))
                .isInstanceOf(ClientException.class);
        verify(authz, never()).check(any(), anyString(), anyString());
        verify(storage, never()).openStream(anyString());
    }

    @Test
    @DisplayName("DENY（跨租户/无权/不存在）：404 语义且零对象存储交互")
    void downloadDeniedNeverTouchesObjectStorage() {
        ResourceAuthorizationService authz = mock(ResourceAuthorizationService.class);
        when(authz.check(any(), anyString(), anyString())).thenReturn(Verdict.DENY);
        TenantObjectReferenceRepository refs = mock(TenantObjectReferenceRepository.class);
        DocumentFileReader storage = mock(DocumentFileReader.class);
        PrincipalContext.set(principal());

        assertThatThrownBy(() -> new AuthorizedDownloadService(providerOf(authz), refs, providerOf(storage))
                .openDocumentStream("doc-1"))
                .isInstanceOf(P04AiException.class);
        verify(storage, never()).openStream(anyString());
    }

    @Test
    @DisplayName("UNKNOWN：503 语义（不放行），同样零对象存储交互")
    void downloadUnknownIsRefused() {
        ResourceAuthorizationService authz = mock(ResourceAuthorizationService.class);
        when(authz.check(any(), anyString(), anyString())).thenReturn(Verdict.UNKNOWN);
        TenantObjectReferenceRepository refs = mock(TenantObjectReferenceRepository.class);
        DocumentFileReader storage = mock(DocumentFileReader.class);
        PrincipalContext.set(principal());

        assertThatThrownBy(() -> new AuthorizedDownloadService(providerOf(authz), refs, providerOf(storage))
                .openDocumentStream("doc-1"))
                .isInstanceOf(ServiceException.class);
        verify(storage, never()).openStream(anyString());
    }

    @Test
    @DisplayName("GRANT 但对象未登记：404 同外显，不接触对象存储")
    void downloadUnregisteredObjectIsNotFound() {
        ResourceAuthorizationService authz = mock(ResourceAuthorizationService.class);
        when(authz.check(any(), anyString(), anyString())).thenReturn(Verdict.GRANT);
        TenantObjectReferenceRepository refs = mock(TenantObjectReferenceRepository.class);
        when(refs.findDocumentStorage(TENANT, "doc-1")).thenReturn(Optional.empty());
        DocumentFileReader storage = mock(DocumentFileReader.class);
        PrincipalContext.set(principal());

        assertThatThrownBy(() -> new AuthorizedDownloadService(providerOf(authz), refs, providerOf(storage))
                .openDocumentStream("doc-1"))
                .isInstanceOf(P04AiException.class);
        verify(storage, never()).openStream(anyString());
    }

    @Test
    @DisplayName("GRANT 且已登记：openStream 收到绑定 key（key 不出服务）")
    void downloadHappyPathUsesBoundKey() {
        ResourceAuthorizationService authz = mock(ResourceAuthorizationService.class);
        when(authz.check(any(), anyString(), anyString())).thenReturn(Verdict.GRANT);
        TenantObjectReferenceRepository refs = mock(TenantObjectReferenceRepository.class);
        String boundKey = TENANT + "/kb-1/abc.txt";
        when(refs.findDocumentStorage(TENANT, "doc-1"))
                .thenReturn(Optional.of(new TenantObjectReferenceRepository.DocumentStorage(boundKey, "text/plain")));
        DocumentFileReader storage = mock(DocumentFileReader.class);
        when(storage.openStream(boundKey))
                .thenReturn(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));
        PrincipalContext.set(principal());

        AuthorizedDownloadService service = new AuthorizedDownloadService(providerOf(authz), refs, providerOf(storage));
        var guard = mock(com.nageoffer.ai.ragent.framework.security.RevocationGuard.class);
        when(guard.enter(any(), anyString(), anyString())).thenReturn(
                new com.nageoffer.ai.ragent.framework.security.RevocationGuard.Operation(guard, "permit", "op"));
        service.setRevocations(guard);
        service.setHighRiskEnabled(true);
        try (var stream = service.openDocumentStream("doc-1")) {
            assertThat(stream).isNotNull();
            verify(guard, never()).release(anyString(), anyString());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        verify(guard).release("permit", "op");
        verify(storage).openStream(boundKey);
    }

    @Test
    @DisplayName("授权服务缺席：拒绝而不是放行（fail-closed）")
    void downloadFailsClosedWithoutAuthorizationService() {
        TenantObjectReferenceRepository refs = mock(TenantObjectReferenceRepository.class);
        PrincipalContext.set(principal());

        assertThatThrownBy(() -> new AuthorizedDownloadService(
                emptyProvider(), refs, providerOf(mock(DocumentFileReader.class)))
                .openDocumentStream("doc-1"))
                .isInstanceOf(ServiceException.class);
    }

    // ---------------------------------------------------------------- 缓存键

    @Test
    @DisplayName("缓存键形状固定：p1:{tenant}:{member}:{action}:{ref}:pv{pv}:av{av}:{version}")
    void cacheKeyShape() {
        ExecutionPrincipal principal = principal();
        String key = AuthorizedCacheKey.of(principal, "document.download", "doc:42", "v9");
        assertThat(key).isEqualTo("p1:T1:platform:T1:2101:document.download:doc:42:pv7:av3:v9");
        assertThat(key).contains("pv" + principal.policyVersion()).contains("av" + principal.aclVersion());
    }

    @Test
    @DisplayName("缓存键拒绝矩阵：空字段/冒号/pv或av<1 一律拒绝，不尽力拼接")
    void cacheKeyRejectsMalformedFields() {
        assertThatThrownBy(() -> AuthorizedCacheKey.of((ExecutionPrincipal) null, "a.b", "doc:1", "v1"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> AuthorizedCacheKey.of(TENANT, MEMBER, "", "doc:1", 1, 1, "v1"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> AuthorizedCacheKey.of(TENANT, MEMBER, "bad:action", "doc:1", 1, 1, "v1"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> AuthorizedCacheKey.of(TENANT, MEMBER, "a.b", "doc:1", 0, 1, "v1"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> AuthorizedCacheKey.of(TENANT, MEMBER, "a.b", "doc:1", 1, 0, "v1"))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> AuthorizedCacheKey.of(TENANT, MEMBER, "a.b", "doc:1", 1, 1, "v:1"))
                .isInstanceOf(ClientException.class);
        // 租户形状与主体契约同源：含冒号/超长拒绝
        assertThatThrownBy(() -> AuthorizedCacheKey.of("bad:tenant", MEMBER, "a.b", "doc:1", 1, 1, "v1"))
                .isInstanceOf(ClientException.class);
    }

    @Test
    @DisplayName("撤权即失配：av 变化产生不同键（旧键不会命中新授权）")
    void cacheKeyChangesWhenAclVersionBumps() {
        String before = AuthorizedCacheKey.of(TENANT, MEMBER, "document.download", "doc:42", 7, 3, "v1");
        String after = AuthorizedCacheKey.of(TENANT, MEMBER, "document.download", "doc:42", 7, 4, "v1");
        assertThat(before).isNotEqualTo(after);
    }
}
