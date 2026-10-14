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

package com.nageoffer.ai.ragent.runtime.scope;

import java.util.Set;

/**
 * 检索/意图链「出站身份重建」的只读在线事实端口（BD-1，2026-10-14）。
 *
 * <p><b>为什么在 rag 侧另立同形端口。</b>意图分类链（{@code intent_classify_executor_*}）
 * 任务体内含 LLM 出站，池线程上无同步起源、出站门可见拒绝；与后台记忆抽取
 * （agent 模块 {@code MemoryEgressSubjectFacts}，s2-f16egress-a1）同构的重建需要一对
 * 在线事实源，而 rag 模块依赖面只见 runtime/infra、看不到 agent 模块端口——故按同形状
 * 在本模块落端口，内嵌装配<b>纯委托</b>到同一 facts bean（同一对在线事实源：
 * {@code PlatformIdentitySource} ＋ {@code ResourceAuthorizationService.currentAclVersion}），
 * 不引入第二事实源。
 *
 * <p><b>口径（与 agent 模块端口逐条一致）。</b>本端口只回答事实，不做放行决定、不缓存：
 * <ul>
 *   <li>租户/成员/版本任一不可用、查无、抛错，消费方必须<b>拒绝重建</b>（退回捕获形态、
 *       出站照旧 fail-closed），不得回落捕获 scopes、环境身份或默认版本；</li>
 *   <li>重建与出站瞬间的最终判定仍归出站 verifier 在线重判（本端口不是授权权威）。</li>
 * </ul>
 */
public interface RetrievalEgressSubjectFacts {

    /** 租户状态；在线读取，不可用必须显式表达（抛异常或 {@link TenantState#UNKNOWN}），不得默认 ENABLED。 */
    TenantState tenantState(String tenantId);

    /**
     * 当前成员事实。
     *
     * @return 匹配的成员事实；成员不存在、与 (tenantId,userId,membershipId) 不匹配、
     *         或租户无策略版本行时返回 {@code null}（调用方拒绝重建）
     */
    CurrentSubject currentSubject(String tenantId, String userId, String membershipId);

    /**
     * 当前 ACL 版本；租户无 epoch 行或读取失败必须抛异常（不默认 1）。
     */
    int currentAclVersion(String tenantId);

    /** 租户状态（与出站 verifier 同一语义）。 */
    enum TenantState {
        /** 租户不存在。 */
        UNKNOWN,
        /** 租户存在但已停用（含过期）。 */
        DISABLED,
        /** 租户可用。 */
        ENABLED
    }

    /**
     * 当前成员事实快照（在线一次读取；重建序列逐项校验，出站 verifier 仍会重判）。
     *
     * @param tenantId      事实所属租户（与定位键回等校验）
     * @param subject       主体（人）的十进制 user_id（与定位键回等校验）
     * @param membershipId  canonical 成员身份 {@code platform:<tenantId>:<userId>}（与定位键回等校验）
     * @param enabled       成员是否启用（租户启用 ∧ 用户启用 ∧ 套餐启用）
     * @param scopes        该成员当前持有的平台权限串集合（如 {@code ai:kb:retrieve}）；不是动作名集合
     * @param policyVersion 当前策略版本，≥ 1（无版本行不构成事实）
     */
    record CurrentSubject(String tenantId, String subject, String membershipId,
                          boolean enabled, Set<String> scopes, int policyVersion) {
    }
}
