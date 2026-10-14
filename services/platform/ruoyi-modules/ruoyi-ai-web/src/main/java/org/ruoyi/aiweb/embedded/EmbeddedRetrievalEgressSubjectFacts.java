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
import com.nageoffer.ai.ragent.runtime.scope.RetrievalEgressSubjectFacts;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link RetrievalEgressSubjectFacts}（rag 检索/意图链出站身份重建事实端口，BD-1）的内嵌实现。
 *
 * <p><b>纯委托。</b>rag 模块依赖面看不到 agent 模块的 {@code MemoryEgressSubjectFacts}，
 * 故 BD-1 在 rag 侧立了同形端口；本类把它桥到<b>同一</b> facts bean（同一对在线事实源：
 * {@code PlatformIdentitySource} ＋ {@code ResourceAuthorizationService.currentAclVersion}），
 * 不引入第二事实源、不缓存。委托目标缺席＝事实不可拿：抛异常（调用方拒绝重建、
 * 退回捕获形态），不回落任何环境身份、不返回默认版本。
 */
final class EmbeddedRetrievalEgressSubjectFacts implements RetrievalEgressSubjectFacts {

    private final ObjectProvider<MemoryEgressSubjectFacts> delegate;

    EmbeddedRetrievalEgressSubjectFacts(ObjectProvider<MemoryEgressSubjectFacts> delegate) {
        this.delegate = delegate;
    }

    @Override
    public TenantState tenantState(String tenantId) {
        return switch (delegate().tenantState(tenantId)) {
            case ENABLED -> TenantState.ENABLED;
            case DISABLED -> TenantState.DISABLED;
            case UNKNOWN -> TenantState.UNKNOWN;
        };
    }

    @Override
    public CurrentSubject currentSubject(String tenantId, String userId, String membershipId) {
        MemoryEgressSubjectFacts.CurrentSubject subject = delegate().currentSubject(tenantId, userId, membershipId);
        if (subject == null) {
            return null;
        }
        return new CurrentSubject(subject.tenantId(), subject.subject(), subject.membershipId(),
                subject.enabled(), subject.scopes(), subject.policyVersion());
    }

    @Override
    public int currentAclVersion(String tenantId) {
        return delegate().currentAclVersion(tenantId);
    }

    /** 事实 bean 缺席即事实不可拿：抛异常（调用方拒绝重建），不回落任何环境身份。 */
    private MemoryEgressSubjectFacts delegate() {
        MemoryEgressSubjectFacts facts = delegate.getIfAvailable();
        if (facts == null) {
            throw new IllegalStateException("memory egress subject facts unavailable");
        }
        return facts;
    }
}
