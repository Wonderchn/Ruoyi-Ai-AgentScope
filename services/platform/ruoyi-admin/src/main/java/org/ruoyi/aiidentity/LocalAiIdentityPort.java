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

package org.ruoyi.aiidentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ai.api.AiExecutionFacts;
import org.ruoyi.ai.api.identity.AiIdentityPort;
import org.ruoyi.aiintegration.identity.CurrentPrincipalResolver;
import org.ruoyi.aiintegration.identity.PlatformIdentitySource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link AiIdentityPort} 的本地实现（E3/C2）：AI 模块在 platform 进程内获取执行事实，
 * 取代原 {@code POST /internal/platform/v1/authorization/current} 的 HTTP 回调。
 *
 * <p>事实来源与网关完全一致，一条不减：
 * <ul>
 *   <li>登录态 → canonical 成员：{@link CurrentPrincipalResolver}（SaToken 会话 +
 *       {@code CurrentAiMembershipService} 真实表校验，不读 body/header）；</li>
 *   <li>租户/成员/scopes/policyVersion：{@link PlatformIdentitySource}（sys_tenant/
 *       sys_user/sys_user_role/sys_role/sys_role_menu/sys_menu + 策略版本行）；</li>
 *   <li>aclVersion：统一库 {@code ai_acl_epoch} 的权威行——取不到返回 empty，
 *       不默认 0/1（契约要求，缺行说明该租户从未建立 ACL epoch，AI 能力不可用）。</li>
 * </ul>
 *
 * <p>任何一步不可得即返回 empty，由调用方拒绝；绝不回落默认租户、绝不放宽空 scopes。
 *
 * @author AI-Identity
 */
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class LocalAiIdentityPort implements AiIdentityPort {

    private final CurrentPrincipalResolver principalResolver;
    private final PlatformIdentitySource identitySource;
    private final JdbcTemplate aclJdbc;

    @Override
    public Optional<AiExecutionFacts> currentFacts() {
        return principalResolver.resolveCurrentMember().flatMap(member -> {
            PlatformIdentitySource.PlatformIdentity identity = identitySource.membership(
                    member.tenantId(), member.userId(), member.membershipId());
            if (identity == null || !identity.enabled()) {
                // 租户停用/成员停用或不存在：拒绝，不回落默认租户
                return Optional.empty();
            }
            Integer aclVersion = aclJdbc.query("SELECT version FROM ai_acl_epoch WHERE tenant_id = ?",
                            (rs, rowNum) -> rs.getInt(1), member.tenantId())
                    .stream().findFirst().orElse(null);
            if (aclVersion == null || aclVersion < 1) {
                log.warn("local identity: tenant {} has no ai_acl_epoch row, facts unavailable",
                        member.tenantId());
                return Optional.empty();
            }
            return Optional.of(new AiExecutionFacts(identity.tenantId(), identity.subject(),
                    identity.membershipId(), identity.policyVersion(), aclVersion, identity.scopes()));
        });
    }
}
