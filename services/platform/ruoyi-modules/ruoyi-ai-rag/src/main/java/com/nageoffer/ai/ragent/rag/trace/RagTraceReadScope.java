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

package com.nageoffer.ai.ragent.rag.trace;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * RAG Trace 读路径的限域（F18/RW-23）。
 *
 * <p>{@code ai_rag_trace_run.tenant_id}/{@code member_id} 是 V7 的 NOT NULL 平台侧列
 * （{@code V7__unified_ai_domain.sql:1821/1822/1964/1965}）。此前
 * {@code RagTraceQueryServiceImpl} 读路径<b>一次都没过滤</b>这两列，等于把租户边界交给
 * "调用方自觉" —— 任何能到达端点的人都可列出/读取所有租户的链路。
 *
 * <p>本记录把限域变成<b>显式入参</b>：调用方必须给出 tenant（必填），
 * 并按可见范围决定是否再收窄到 member：
 * <ul>
 *   <li>{@link #tenantWideCurrent()}：运维/管理面（租户内全量，跨租户仍不可见）；</li>
 *   <li>{@link #memberOnlyCurrent()}：用户面（只看自己成员的链路）。</li>
 * </ul>
 *
 * <p>取值与校验都只有一处：{@link PrincipalContext#require()}。缺主体即抛
 * {@link ClientException}（fail-closed）—— 不允许"没有主体就查全库"。
 */
public record RagTraceReadScope(String tenantId, String memberId, boolean tenantWide) {

    /**
     * 校验限域形状：tenant 必填且不含冒号（冒号是 canonical membership 的分隔符，
     * 与 {@link ExecutionPrincipal#requireTenantId} 同一口径）；非 tenant-wide 时 member 必填。
     */
    public RagTraceReadScope {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ClientException("trace read requires a tenant scope");
        }
        if (tenantId.indexOf(':') >= 0) {
            throw new ClientException("trace read tenant id must not contain ':'");
        }
        if (!tenantId.equals(tenantId.trim())) {
            throw new ClientException("trace read tenant id must not have surrounding whitespace");
        }
        if (!tenantWide && (memberId == null || memberId.isBlank())) {
            throw new ClientException("trace read requires a member scope when not tenant wide");
        }
    }

    /**
     * 由当前执行主体构造限域。
     *
     * @param tenantWide {@code true} 表示租户内全量（运维面）；{@code false} 表示仅当前成员
     * @throws ClientException 无执行主体（不降级、不默认全库）
     */
    public static RagTraceReadScope current(boolean tenantWide) {
        ExecutionPrincipal principal = PrincipalContext.require();
        return new RagTraceReadScope(principal.tenantId(), principal.membershipId(), tenantWide);
    }

    /** 运维/管理面：租户内全量。 */
    public static RagTraceReadScope tenantWideCurrent() {
        return current(true);
    }

    /** 用户面：仅当前成员。 */
    public static RagTraceReadScope memberOnlyCurrent() {
        return current(false);
    }
}
