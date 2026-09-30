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

package org.ruoyi.aiintegration.p04;

import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <b>测试专用</b>的合成身份事实（Spec §7.3）。
 *
 * <p>刻意不接真实 platform 身份表：P0.4 只验证身份/受理契约，真实身份接线属 P1。
 * U1 与 U2 的 username 相同（{@code same-name}），用于证明同名不会被合并、
 * 归属判定只认 {@code tid/sub/mid}。
 */
@Component
public class SyntheticPlatformIdentitySource implements PlatformIdentitySource {

    /** 同名用户：不得据此合并身份。 */
    public static final String SAME_NAME = "same-name";

    public static final String T1 = "T1";
    public static final String T2 = "T2";
    public static final String T_DISABLED = "T-DISABLED";

    public static final String SUB_U1 = "sub-u1";
    public static final String MID_U1_T1 = "M1";
    public static final String MID_U1_T2 = "M1T2";
    public static final String SUB_U2 = "sub-u2";
    public static final String MID_U2_T2 = "M2";
    public static final String SUB_U0 = "sub-u0";
    public static final String MID_U0_T1 = "M0";
    public static final String SUB_UD = "sub-ud";
    public static final String MID_UD_T1 = "MD";

    private final Map<String, TenantState> tenants = new LinkedHashMap<>();
    private final Map<String, PlatformIdentity> memberships = new LinkedHashMap<>();

    public SyntheticPlatformIdentitySource() {
        tenants.put(T1, TenantState.ENABLED);
        tenants.put(T2, TenantState.ENABLED);
        tenants.put(T_DISABLED, TenantState.DISABLED);

        put(T1, SUB_U1, MID_U1_T1, true, 1);
        put(T2, SUB_U1, MID_U1_T2, true, 1);
        put(T2, SUB_U2, MID_U2_T2, true, 1);
        put(T1, SUB_U0, MID_U0_T1, true, 1);
        put(T1, SUB_UD, MID_UD_T1, false, 1);
    }

    private void put(String tenantId, String subject, String membershipId, boolean enabled, int policyVersion) {
        memberships.put(key(tenantId, subject, membershipId),
                new PlatformIdentity(tenantId, subject, membershipId, enabled,
                        Set.of("rag.chat.submit"), policyVersion));
    }

    private static String key(String tenantId, String subject, String membershipId) {
        return tenantId + "|" + subject + "|" + membershipId;
    }

    @Override
    public TenantState tenantState(String tenantId) {
        return tenants.getOrDefault(tenantId, TenantState.UNKNOWN);
    }

    @Override
    public PlatformIdentity membership(String tenantId, String subject, String membershipId) {
        return memberships.get(key(tenantId, subject, membershipId));
    }

    /** 测试控制面：覆盖某成员的功能 scope（N04「缺 submit 功能权限」场景）。 */
    public void setScopes(String tenantId, String subject, String membershipId, Set<String> scopes) {
        String k = key(tenantId, subject, membershipId);
        PlatformIdentity current = memberships.get(k);
        if (current == null) {
            throw new IllegalArgumentException("unknown synthetic membership");
        }
        memberships.put(k, new PlatformIdentity(current.tenantId(), current.subject(), current.membershipId(),
                current.enabled(), Set.copyOf(scopes), current.policyVersion()));
    }

    /** 测试控制面：调整某成员的策略版本（N07「旧 pv」与撤权场景）。 */
    public void setPolicyVersion(String tenantId, String subject, String membershipId, int policyVersion) {
        String k = key(tenantId, subject, membershipId);
        PlatformIdentity current = memberships.get(k);
        if (current == null) {
            throw new IllegalArgumentException("unknown synthetic membership");
        }
        memberships.put(k, new PlatformIdentity(current.tenantId(), current.subject(), current.membershipId(),
                current.enabled(), current.scopes(), policyVersion));
    }

    /** 测试控制面：启用/停用某成员。 */
    public void setEnabled(String tenantId, String subject, String membershipId, boolean enabled) {
        String k = key(tenantId, subject, membershipId);
        PlatformIdentity current = memberships.get(k);
        if (current == null) {
            throw new IllegalArgumentException("unknown synthetic membership");
        }
        memberships.put(k, new PlatformIdentity(current.tenantId(), current.subject(), current.membershipId(),
                enabled, current.scopes(), current.policyVersion()));
    }

    /** 测试控制面：启用/停用租户。 */
    public void setTenantState(String tenantId, TenantState state) {
        tenants.put(tenantId, state);
    }
}
