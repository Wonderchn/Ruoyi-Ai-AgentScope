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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.convention.MergedTableRow;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 统一库身份列的<b>唯一</b>写入来源（E5/WP-025）。
 *
 * <p>背景：{@code ai_conversation.member_id}、{@code ai_message.member_id}、
 * {@code ai_message_feedback.member_id}、{@code ai_rag_trace_run.member_id}、
 * {@code ai_biz_change_log.member_id}、{@code ai_agent_*.member_id}、
 * {@code ai_knowledge_base.owner_member_id}、{@code ai_ingestion_pipeline.owner_member_id}
 * 在 V7 里是 {@code NOT NULL} 且无默认值；{@code tenant_id} 的 {@code DEFAULT '0'} 只是列级默认，
 * 不代表身份。AI 侧实体原先没有这些字段，platform 的填充器只填创建/更新审计列，租户行拦截器只处理
 * {@code tenant_id}，因此"改完表名"之后对合并表的写入仍会失败——或者更糟：靠默认值静默把所有租户
 * 写进同一个桶。
 *
 * <p>取值固定在一处：{@link PrincipalContext#require()} 的 {@link ExecutionPrincipal}。
 * 成员标识由 {@code ExecutionPrincipal.canonicalMembershipId} 保证为
 * {@code platform:<tenantId>:<userId>}（tenant 不含冒号、user 为十进制串；数值租户按文本参与，
 * {@code 0} 保持 {@code '0'}，不并入 {@code '000000'}）。
 *
 * <p>刻意<b>不提供</b>任何接受外部 tenant/user 参数的重载：调用方无法"顺手"把请求体里的值写进
 * 身份列，因为那正是要防止的伪造路径。缺少主体即抛 {@link ClientException}（fail-closed），
 * 不填空、不落默认租户、不写"无成员"哨兵值。
 *
 * <p>做成无状态静态 helper 而不是 Spring bean：AI 包不在 platform 组件扫描路径内
 * （{@code AiEmbeddedRagConfiguration} 逐项显式登记），静态调用点不依赖装配，
 * 也不会因为"某个上下文没注册这个 bean"而静默变成 NO-OP。
 */
public final class AiDomainWriteIdentity {

    private AiDomainWriteIdentity() {
    }

    /** 当前主体的租户标识（varchar(64)）。缺失即拒绝。 */
    public static String requireTenantId() {
        return PrincipalContext.require().tenantId();
    }

    /** 当前主体的 canonical 成员标识（varchar(160)）。缺失即拒绝。 */
    public static String requireMemberId() {
        return PrincipalContext.require().membershipId();
    }

    /**
     * 给一行统一库实体填上身份列。
     *
     * @param row 目标行；{@code null} 视为调用方 bug 而不是"跳过"
     * @throws ClientException 无主体或行为 null
     */
    public static <T extends MergedTableRow> T apply(T row) {
        if (row == null) {
            throw new ClientException("identity cannot be applied to a null row");
        }
        ExecutionPrincipal principal = PrincipalContext.require();
        row.setTenantId(principal.tenantId());
        row.setMemberId(principal.membershipId());
        return row;
    }

    /**
     * 用一条<b>已持久化</b>的行的身份回填目标行（异步/消费者路径）。
     *
     * <p>后台消费者没有 {@code PrincipalContext}，它承接的是"受理事实"：目标行必须落在与父行
     * 相同的租户与成员下。父行的身份列是此前由可信主体写入的持久事实，因此这里不是"从请求生成身份"，
     * 而是<b>以父行为准的回填</b>。父行身份缺失时拒绝——那说明父行走过未适配的写路径，
     * 继续写入只会制造无身份的行。
     *
     * @param row    目标行
     * @param tenant 父行的租户列
     * @param member 父行的 canonical 成员列
     */
    public static <T extends MergedTableRow> T applyFromPersistedFact(T row, String tenant, String member) {
        if (row == null) {
            throw new ClientException("identity cannot be applied to a null row");
        }
        if (tenant == null || tenant.isBlank()) {
            throw new ClientException("persisted parent row has no tenant identity");
        }
        if (member == null || member.isBlank()) {
            throw new ClientException("persisted parent row has no member identity");
        }
        ExecutionPrincipal current = PrincipalContext.get();
        if (current != null && !current.membershipId().equals(member)) {
            // 有主体时必须与主体一致：否则就是拿别人的历史行写新数据
            throw new ClientException("persisted parent row belongs to a different member");
        }
        row.setTenantId(tenant);
        row.setMemberId(member);
        return row;
    }
}
