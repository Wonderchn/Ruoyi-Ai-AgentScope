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

package com.nageoffer.ai.ragent.audit.support;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 业务变更日志读路径的限域（F18/RW-23）。
 *
 * <p>{@code ai_biz_change_log.tenant_id}/{@code member_id} 是 V7 的 NOT NULL 平台侧列
 * （写入由 {@code AiDomainWriteIdentity} 负责）。此前
 * {@code BizChangeLogServiceImpl.page/get} <b>没有任何过滤</b>：分页可按业务键筛全库、
 * 详情直接 {@code selectById}，跨租户可读。
 *
 * <p>与 {@link com.nageoffer.ai.ragent.rag.trace.RagTraceReadScope} 同一口径（各自域内独立类型，
 * 避免跨模块耦合）：tenant 必填；非 tenant-wide 时 member 必填；值只来自
 * {@link PrincipalContext#require()}，缺主体 fail-closed。
 */
public record BizChangeLogReadScope(String tenantId, String memberId, boolean tenantWide) {

    public BizChangeLogReadScope {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ClientException("change log read requires a tenant scope");
        }
        if (tenantId.indexOf(':') >= 0) {
            throw new ClientException("change log read tenant id must not contain ':'");
        }
        if (!tenantId.equals(tenantId.trim())) {
            throw new ClientException("change log read tenant id must not have surrounding whitespace");
        }
        if (!tenantWide && (memberId == null || memberId.isBlank())) {
            throw new ClientException("change log read requires a member scope when not tenant wide");
        }
    }

    /**
     * 由当前执行主体构造限域（缺主体即拒绝）。
     */
    public static BizChangeLogReadScope current(boolean tenantWide) {
        ExecutionPrincipal principal = PrincipalContext.require();
        return new BizChangeLogReadScope(principal.tenantId(), principal.membershipId(), tenantWide);
    }

    /** 运维/管理面：租户内全量。 */
    public static BizChangeLogReadScope tenantWideCurrent() {
        return current(true);
    }

    /** 用户面：仅当前成员。 */
    public static BizChangeLogReadScope memberOnlyCurrent() {
        return current(false);
    }
}
