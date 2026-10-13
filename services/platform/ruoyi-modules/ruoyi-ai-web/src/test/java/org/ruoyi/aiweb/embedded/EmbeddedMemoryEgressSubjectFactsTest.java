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
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F16 出站（s2-f16egress-a1）· 内嵌事实端口判据：事实源与出站 verifier 同源、逐字段一对一搬运；
 * 源缺席/epoch 缺行必须 fail-closed（抛异常），不得回落任何默认值。
 */
@Tag("dev")
class EmbeddedMemoryEgressSubjectFactsTest {

    private final PlatformIdentitySource identities = mock(PlatformIdentitySource.class);
    private final ResourceAuthorizationService acl = mock(ResourceAuthorizationService.class);
    private final EmbeddedMemoryEgressSubjectFacts facts =
            new EmbeddedMemoryEgressSubjectFacts(providerOf(identities), providerOf(acl));

    @Test
    void identityAclFactsArePassedThroughOneToOne() {
        when(identities.tenantState("T1")).thenReturn(PlatformIdentitySource.TenantState.ENABLED);
        assertThat(facts.tenantState("T1")).isEqualTo(MemoryEgressSubjectFacts.TenantState.ENABLED);

        PlatformIdentitySource.PlatformIdentity identity = new PlatformIdentitySource.PlatformIdentity(
                "T1", "42", "platform:T1:42", true, Set.of("ai:memory:read"), 7);
        when(identities.membership("T1", "42", "platform:T1:42")).thenReturn(identity);
        MemoryEgressSubjectFacts.CurrentSubject current = facts.currentSubject("T1", "42", "platform:T1:42");
        assertThat(current.tenantId()).isEqualTo(identity.tenantId());
        assertThat(current.subject()).isEqualTo(identity.subject());
        assertThat(current.membershipId()).isEqualTo(identity.membershipId());
        assertThat(current.enabled()).isEqualTo(identity.enabled());
        assertThat(current.scopes()).containsExactly("ai:memory:read");
        assertThat(current.policyVersion()).isEqualTo(identity.policyVersion());

        when(acl.currentAclVersion("T1")).thenReturn(9);
        assertThat(facts.currentAclVersion("T1")).isEqualTo(9);
    }

    @Test
    void disabledAndUnknownTenantsKeepTheirMeaning() {
        when(identities.tenantState("T-DISABLED")).thenReturn(PlatformIdentitySource.TenantState.DISABLED);
        when(identities.tenantState("T-UNKNOWN")).thenReturn(PlatformIdentitySource.TenantState.UNKNOWN);
        assertThat(facts.tenantState("T-DISABLED")).isEqualTo(MemoryEgressSubjectFacts.TenantState.DISABLED);
        assertThat(facts.tenantState("T-UNKNOWN")).isEqualTo(MemoryEgressSubjectFacts.TenantState.UNKNOWN);
    }

    @Test
    void absentMemberIsReportedAsNoFactsNotAnError() {
        when(identities.membership("T1", "42", "platform:T1:42")).thenReturn(null);
        assertThat(facts.currentSubject("T1", "42", "platform:T1:42")).isNull();
    }

    @Test
    void absentSourcesAndMissingEpochFailClosedWithoutDefaults() {
        EmbeddedMemoryEgressSubjectFacts absent = new EmbeddedMemoryEgressSubjectFacts(
                providerOf(null), providerOf(null));
        assertThatThrownBy(() -> absent.tenantState("T1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> absent.currentSubject("T1", "42", "platform:T1:42"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> absent.currentAclVersion("T1")).isInstanceOf(IllegalStateException.class);

        // epoch 缺行由授权服务抛出（ServiceException）：原样上抛，不默认 1、不吞掉
        when(acl.currentAclVersion("T1")).thenThrow(new ServiceException("acl epoch is missing for tenant T1"));
        assertThatThrownBy(() -> facts.currentAclVersion("T1")).isInstanceOf(ServiceException.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
