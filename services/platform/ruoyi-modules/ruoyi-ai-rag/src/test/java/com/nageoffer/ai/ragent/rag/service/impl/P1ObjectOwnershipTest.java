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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.config.RagStorageProperties;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * U08（P1.3c）：对象归属。
 *
 * <p>这一组断言针对的是"key 即凭据"这个失效模式：
 * {@code openStream} / {@code deleteByUrl} / {@code getPublicUrl} 的入参只有 key，
 * 没有可用于常规 ACL 判定的资源标识；只要不做归属校验，
 * 任何拿到 key 的地方（日志、导出、前端直链）就等于拿到读/删权限。
 *
 * <p>因此这里的每条用例都在问同一个问题：
 * <b>另一个租户的 key 能不能通行？</b>答案必须是"不能"，而且必须是**拒绝**而不是"查不到"——
 * 两者在下游的处理方式不同，前者明确失败、后者会被当成空结果静默继续。
 */
@Tag("dev")
class P1ObjectOwnershipTest {

    private static final String TENANT_A = "p1t1";
    private static final String TENANT_B = "p1t2";

    private final ObjectStorageClient client = mock(ObjectStorageClient.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RagStorageProperties properties = new RagStorageProperties();

    /**
     * 桶名在**构造时**就被读进 final 字段，因此属性必须在构造之前设好。
     * 构造后再改 properties 不会生效——那样会让"拒绝"用例因为走错桶而假通过。
     */
    private DefaultFileStorageService newService() {
        properties.setKbBucket("ragent-sources");
        properties.setAssetBucket("ragent-assets");
        return new DefaultFileStorageService(client, redisson, properties);
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    private static ExecutionPrincipal principalOf(String tenantId) {
        return new ExecutionPrincipal(
                tenantId, "2101", "platform:" + tenantId + ":2101", 7, 3,
                Set.of("ai:kb:upload"), "jti-" + tenantId, "platform",
                1_700_000_000L, 1_700_000_060L);
    }

    private void asTenant(String tenantId) {
        PrincipalContext.set(principalOf(tenantId));
    }

    // ---------------------------------------------------------------- key 形状

    @Test
    @DisplayName("上传文档：key 必须带本租户前缀")
    void uploadedDocumentKeyCarriesTenantPrefix() {
        properties.setKbBucket("ragent-sources");
        properties.setAssetBucket("ragent-assets");
        asTenant(TENANT_A);
        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        String key = newService().upload("kb-1", file).getUrl();

        assertThat(key).startsWith(TENANT_A + "/");
        assertThat(key).doesNotStartWith(TENANT_B + "/");
    }

    @Test
    @DisplayName("上传资产：key 同样带本租户前缀（否则公共读桶无从判定归属）")
    void uploadedAssetKeyCarriesTenantPrefix() throws Exception {
        properties.setAssetBucket("ragent-assets");
        asTenant(TENANT_A);
        when(client.buildPublicUrl(eq("ragent-assets"), anyString())).thenAnswer(i -> "https://s3/" + i.getArgument(1));
        when(client.objectExists(eq("ragent-assets"), anyString())).thenReturn(true);

        String key = newService().uploadAsset("logo.png".getBytes(StandardCharsets.UTF_8), "logo.png", "image/png")
                .getUrl();

        assertThat(key).startsWith(TENANT_A + "/");
    }

    // ---------------------------------------------------------------- 跨租户拒绝

    @Test
    @DisplayName("openStream：拒绝另一个租户的 key，且不发生任何对象存储调用")
    void openStreamRejectsOtherTenantKey() {
        asTenant(TENANT_A);
        String other = TENANT_B + "/kb-1/doc.txt";

        assertThatThrownBy(() -> newService().openStream(other))
                .isInstanceOf(ServiceException.class);
        verify(client, never()).getObject(anyString(), anyString());
    }

    @Test
    @DisplayName("deleteByUrl：拒绝另一个租户的 key，且不发生任何删除")
    void deleteRejectsOtherTenantKey() {
        asTenant(TENANT_A);
        String other = TENANT_B + "/kb-1/doc.txt";

        assertThatThrownBy(() -> newService().deleteByUrl(other))
                .isInstanceOf(ServiceException.class);
        verify(client, never()).deleteObject(anyString(), anyString());
    }

    @Test
    @DisplayName("getPublicUrl：拒绝另一个租户的 key，不签发指向他人对象的公共链接")
    void publicUrlRejectsOtherTenantKey() {
        asTenant(TENANT_A);
        String other = TENANT_B + "/logo.png";

        assertThatThrownBy(() -> newService().getPublicUrl(other))
                .isInstanceOf(ServiceException.class);
        verify(client, never()).buildPublicUrl(anyString(), anyString());
    }

    @Test
    @DisplayName("本租户的 key 必须放行（拒绝不能靠一律拒绝来实现）")
    void ownTenantKeyIsAllowed() {
        asTenant(TENANT_A);
        String own = TENANT_A + "/kb-1/doc.txt";
        when(client.getObject(eq("ragent-sources"), eq(own)))
                .thenReturn(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

        assertThat(newService().openStream(own)).isNotNull();
        verify(client).getObject("ragent-sources", own);
    }

    // ---------------------------------------------------------------- fail-closed

    @Test
    @DisplayName("无前缀的 key 同样拒绝：不留历史 key 的永久后门")
    void keyWithoutTenantPrefixIsRejected() {
        asTenant(TENANT_A);
        String legacy = "kb-1/doc.txt";

        assertThatThrownBy(() -> newService().openStream(legacy))
                .isInstanceOf(ServiceException.class);
        verify(client, never()).getObject(anyString(), anyString());
    }

    @Test
    @DisplayName("前缀相近但不是本租户的 key 不得误放行（p1t1 不能匹配 p1t10）")
    void prefixMustMatchTheWholeTenantSegment() {
        asTenant(TENANT_A);
        String lookalike = TENANT_A + "0/kb-1/doc.txt";

        assertThatThrownBy(() -> newService().openStream(lookalike))
                .isInstanceOf(ServiceException.class);
        verify(client, never()).getObject(anyString(), anyString());
    }

    @Test
    @DisplayName("无执行主体时必须拒绝，而不是降级为不校验")
    void missingPrincipalIsRejected() {
        PrincipalContext.clear();
        assertThatThrownBy(() -> newService().openStream(TENANT_A + "/kb-1/doc.txt"))
                .isInstanceOfAny(ServiceException.class, com.nageoffer.ai.ragent.framework.exception.ClientException.class);
        verify(client, never()).getObject(anyString(), anyString());
    }

    @Test
    @DisplayName("上传时无执行主体同样拒绝，不得写出无归属对象")
    void uploadWithoutPrincipalIsRejected() {
        properties.setKbBucket("ragent-sources");
        PrincipalContext.clear();
        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> newService().upload("kb-1", file))
                .isInstanceOfAny(ServiceException.class, com.nageoffer.ai.ragent.framework.exception.ClientException.class);
        verify(client, never()).streamPut(anyString(), anyString(), any(), anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 空间删除的租户作用域

    @Test
    @DisplayName("deleteKnowledgeSpace：前缀必须是 {tenantId}/{namespace}/，不再裸 namespace 全局删")
    void deleteKnowledgeSpaceIsTenantScoped() {
        asTenant(TENANT_A);

        newService().deleteKnowledgeSpace("kb-1");

        verify(client).deleteByPrefix("ragent-sources", TENANT_A + "/kb-1/");
        verify(client, never()).deleteByPrefix(anyString(), eq("kb-1/"));
    }

    @Test
    @DisplayName("deleteKnowledgeSpaceForTenant：清理消费者路径用事件租户，形状非法即拒绝")
    void deleteKnowledgeSpaceForTenantUsesEventTenant() {
        // 事件租户合法：按 {tenantId}/{namespace}/ 删除
        newService().deleteKnowledgeSpaceForTenant("kb-1", TENANT_B);
        verify(client).deleteByPrefix("ragent-sources", TENANT_B + "/kb-1/");

        // 租户形状非法（含冒号/超长）：拒绝且零删除
        verify(client, never()).deleteByPrefix(anyString(), eq("platform:t2:1/"));
        assertThatThrownBy(() -> newService().deleteKnowledgeSpaceForTenant("kb-1", "bad:tenant"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, times(1)).deleteByPrefix(anyString(), anyString());
    }

    @Test
    @DisplayName("createKnowledgeSpace：标记对象按租户作用域，两租户同名知识库互不冲突")
    void createKnowledgeSpaceMarkerIsTenantScoped() throws Exception {
        properties.setKbBucket("ragent-sources");
        properties.setAssetBucket("ragent-assets");
        when(client.objectExists(eq("ragent-sources"), anyString())).thenReturn(false);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        asTenant(TENANT_A);

        newService().createKnowledgeSpace("kb-1");

        verify(client).streamPut(eq("ragent-sources"), eq(TENANT_A + "/kb-1/"),
                any(), eq(0L), any());
    }

    // ---------------------------------------------------------------- 私有内容不得换公共 URL

    @Test
    @DisplayName("getPublicUrl：知识库文档键（两段以上路径）即使属于本租户也不得换公共 URL")
    void publicUrlRejectedForPrivateDocumentKeys() {
        asTenant(TENANT_A);
        String privateDoc = TENANT_A + "/kb-1/doc.txt";

        assertThatThrownBy(() -> newService().getPublicUrl(privateDoc))
                .isInstanceOf(ServiceException.class);
        verify(client, never()).buildPublicUrl(anyString(), anyString());
    }

    @Test
    @DisplayName("getPublicUrl：单段资产键正常放行（现有图片解析调用方的合法路径）")
    void publicUrlAllowedForAssetKeys() {
        asTenant(TENANT_A);
        String assetKey = TENANT_A + "/logo.png";
        when(client.buildPublicUrl(eq("ragent-assets"), eq(assetKey))).thenReturn("https://s3/" + assetKey);

        assertThat(newService().getPublicUrl(assetKey)).isEqualTo("https://s3/" + assetKey);
    }

    // ---------------------------------------------------------------- 路径穿越

    @Test
    @DisplayName("namespace 含路径分隔符即拒绝：目录名只是租户内的单一路径段")
    void namespaceTraversalIsRejected() {
        asTenant(TENANT_A);

        assertThatThrownBy(() -> newService().deleteKnowledgeSpace("other/../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newService().deleteKnowledgeSpace("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newService().deleteKnowledgeSpace("a\\b"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).deleteByPrefix(anyString(), anyString());
    }
}
