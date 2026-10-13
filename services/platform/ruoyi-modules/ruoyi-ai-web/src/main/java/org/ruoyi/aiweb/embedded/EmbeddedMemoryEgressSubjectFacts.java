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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.agent.memory.MemoryEgressSubjectFacts;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link MemoryEgressSubjectFacts} 的内嵌实现（F16 出站，s2-f16egress-a1，2026-10-12）。
 *
 * <p><b>事实源与出站 verifier 同源（同一对 ObjectProvider）。</b>
 * {@code PlatformIdentitySource}（真实实现 {@code RuoYiPlatformIdentitySource}，与 verifier 同开关
 * {@code ai.integration.enabled=true}；后台线程无请求时直读、查询均在
 * {@code TenantHelper.ignore} 内）＋{@code ResourceAuthorizationService.currentAclVersion}
 * （{@code AiResourceAuthorizationService}，读 {@code ai_acl_epoch}，缺行即抛、绝不默认）。
 * 刻意<b>不</b>依赖 {@code PlatformFactsClient}（默认关的 HTTP 自呼，只回 enabled+pv，
 * 无 scopes/无 av，不能满足重建）。
 *
 * <p><b>失败语义。</b>身份源/授权服务缺席即事实不可拿——抛异常（调用方因此拒绝重建、
 * 退回捕获形态），不回落任何环境身份、不返回默认版本。本类只读、不缓存：
 * 每次调用都取当前事实；出站 verifier 在 authorize/beforeSend 仍会在线重判。
 */
final class EmbeddedMemoryEgressSubjectFacts implements MemoryEgressSubjectFacts {

    private final ObjectProvider<PlatformIdentitySource> identities;
    private final ObjectProvider<ResourceAuthorizationService> acl;

    EmbeddedMemoryEgressSubjectFacts(ObjectProvider<PlatformIdentitySource> identities,
                                     ObjectProvider<ResourceAuthorizationService> acl) {
        this.identities = identities;
        this.acl = acl;
    }

    @Override
    public TenantState tenantState(String tenantId) {
        return switch (identitySource().tenantState(tenantId)) {
            case ENABLED -> TenantState.ENABLED;
            case DISABLED -> TenantState.DISABLED;
            case UNKNOWN -> TenantState.UNKNOWN;
        };
    }

    @Override
    public CurrentSubject currentSubject(String tenantId, String userId, String membershipId) {
        PlatformIdentitySource.PlatformIdentity identity =
                identitySource().membership(tenantId, userId, membershipId);
        if (identity == null) {
            return null;
        }
        return new CurrentSubject(identity.tenantId(), identity.subject(), identity.membershipId(),
                identity.enabled(), identity.scopes(), identity.policyVersion());
    }

    @Override
    public int currentAclVersion(String tenantId) {
        ResourceAuthorizationService service = acl.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("resource authorization unavailable");
        }
        return service.currentAclVersion(tenantId);
    }

    /** 身份源缺席即事实不可拿：抛异常（调用方拒绝重建），不回落任何环境身份。 */
    private PlatformIdentitySource identitySource() {
        PlatformIdentitySource source = identities.getIfAvailable();
        if (source == null) {
            throw new IllegalStateException("platform identity source unavailable");
        }
        return source;
    }
}
