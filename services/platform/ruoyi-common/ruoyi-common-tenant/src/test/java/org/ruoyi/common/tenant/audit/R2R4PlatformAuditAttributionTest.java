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

package org.ruoyi.common.tenant.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.constant.TenantConstants;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-2-R4 验收（裁决 §7.1 执行范围第 1、2 条）：审计行归属判定。
 *
 * <p>判据只针对"一条审计行该记在谁名下"这一条规则，不依赖数据库、不依赖 Spring。
 * 关键性质：
 * <ul>
 *   <li>归属未核验 ⇒ 一律记保留值，<b>不</b>落真实租户 {@code 000000}；</li>
 *   <li>保留值本身<b>不是</b>可声明的租户——把它当声明值传进来也只会得到保留值；</li>
 *   <li>{@code verifiedByStatus} 只把"服务端核验过"的三种状态算作已核验。</li>
 * </ul>
 */
@Tag("dev")
class R2R4PlatformAuditAttributionTest {

    private static final String RESERVED = TenantConstants.PLATFORM_AUDIT_TENANT_ID;

    @Test
    @DisplayName("归属已核验 ⇒ 记真实租户；未核验 ⇒ 记保留值（不落 000000）")
    void verifiedTenantWinsUnverifiedFallsBackToTheReservedMarker() {
        assertThat(PlatformAuditAttribution.resolve("154726", true)).isEqualTo("154726");
        assertThat(PlatformAuditAttribution.resolve("000000", true))
                .as("真实 000000 租户是合法归属，不得被改写成保留值")
                .isEqualTo("000000");

        for (String declared : new String[]{"154726", "000000", null, "", "   "}) {
            assertThat(PlatformAuditAttribution.resolve(declared, false))
                    .as("未核验的声明值 [%s] 不得成为可信归属", declared)
                    .isEqualTo(RESERVED);
        }
    }

    @Test
    @DisplayName("保留值不是租户：把它当声明值传进来只会得到保留值")
    void theReservedMarkerIsNeverATenant() {
        assertThat(PlatformAuditAttribution.resolve(RESERVED, true)).isEqualTo(RESERVED);
        assertThat(PlatformAuditAttribution.resolve(RESERVED, false)).isEqualTo(RESERVED);
        assertThat(PlatformAuditAttribution.isPlatformAudit(RESERVED)).isTrue();
        assertThat(PlatformAuditAttribution.isPlatformAudit("000000")).isFalse();
        assertThat(PlatformAuditAttribution.isPlatformAudit(null)).isFalse();
    }

    @Test
    @DisplayName("已核验但租户号为空 ⇒ 仍记保留值，并给出原因")
    void verifiedButBlankStillFallsBackWithAReason() {
        PlatformAuditAttribution.Attribution blank = PlatformAuditAttribution.resolveWithReason("  ", true);
        assertThat(blank.tenantId()).isEqualTo(RESERVED);
        assertThat(blank.unattributed()).isTrue();
        assertThat(blank.reason()).isNotBlank();
    }

    @Test
    @DisplayName("无归属一律带原因；有归属时无原因")
    void reasonsAreRecordedOnlyForUnattributedRows() {
        PlatformAuditAttribution.Attribution unattributed =
                PlatformAuditAttribution.resolveWithReason("154726", false);
        assertThat(unattributed.unattributed()).isTrue();
        assertThat(unattributed.reason()).isEqualTo("租户归属未经服务端核验");

        PlatformAuditAttribution.Attribution attributed =
                PlatformAuditAttribution.resolveWithReason("154726", true);
        assertThat(attributed.unattributed()).isFalse();
        assertThat(attributed.reason()).isNull();
    }

    @Test
    @DisplayName("只有服务端核验过的登录审计状态算已核验")
    void onlyServerVerifiedLoginStatusesCount() {
        assertThat(PlatformAuditAttribution.verifiedByStatus(Constants.LOGIN_SUCCESS)).isTrue();
        assertThat(PlatformAuditAttribution.verifiedByStatus(Constants.LOGOUT)).isTrue();
        assertThat(PlatformAuditAttribution.verifiedByStatus(Constants.REGISTER)).isTrue();

        for (String unverified : new String[]{Constants.LOGIN_FAIL, "9", "", null}) {
            assertThat(PlatformAuditAttribution.verifiedByStatus(unverified))
                    .as("状态 [%s] 发生在核验之前，不得算已核验", unverified)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("保留值长度适配既有 varchar(20) 列，且不等于默认租户")
    void theReservedMarkerFitsTheExistingColumnAndIsNotTheDefaultTenant() {
        assertThat(RESERVED.length()).isLessThanOrEqualTo(20);
        assertThat(RESERVED).isNotEqualTo(TenantConstants.DEFAULT_TENANT_ID);
        assertThat(RESERVED).isEqualTo("__platform_audit__");
    }
}
