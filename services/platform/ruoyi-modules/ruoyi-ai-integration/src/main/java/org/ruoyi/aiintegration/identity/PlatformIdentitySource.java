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

package org.ruoyi.aiintegration.identity;

import java.util.Set;

/**
 * platform 侧身份事实的抽象（D2=A：platform 决定租户/成员/功能/权益与 policyVersion）。
 *
 * <p>实测事实（DB3 之后）：本实验<b>不</b>接入真实 platform 身份表——P0.4 的合成身份由
 * 测试替身提供（Spec §7.1/§8.1 的 C4）。生产实现（复用 platform 的身份与权限服务）
 * 属 P1，不在本单元内。
 */
public interface PlatformIdentitySource {

    /** 租户状态。 */
    enum TenantState {
        /** 租户不存在。 */
        UNKNOWN,
        /** 租户存在但已停用。 */
        DISABLED,
        /** 租户可用。 */
        ENABLED
    }

    /**
     * 成员身份事实。
     *
     * @param tenantId      租户
     * @param subject       主体（人）
     * @param membershipId  成员身份（= P0.4 的 principalId）
     * @param enabled       成员是否启用
     * @param scopes        已授予的功能 scope
     * @param policyVersion 当前策略版本
     */
    record PlatformIdentity(String tenantId, String subject, String membershipId, boolean enabled,
                            Set<String> scopes, int policyVersion) {
    }

    TenantState tenantState(String tenantId);

    /**
     * @return 匹配的成员身份；不存在或 tid/sub/mid 不匹配时返回 {@code null}
     */
    PlatformIdentity membership(String tenantId, String subject, String membershipId);
}
