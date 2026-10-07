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

package com.nageoffer.ai.ragent.framework.security;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;

import java.util.List;

/**
 * platform 主体/组织事实端口：AI 侧对「资源 owner 候选集合」逐候选询问平台事实
 * （成员归属、部门子树、主体是否存活）。
 *
 * <p>两种形态同一语义：
 * <ul>
 *   <li><b>独立运行</b>：HTTP 回调 platform 的
 *       {@code POST /internal/platform/v1/authorization/subjects/match}（services/ai 冻结拷贝）；</li>
 *   <li><b>内嵌运行</b>：同进程本地接口直接读取 platform 身份源（E3 装配，
 *       不经过任何 localhost HTTP）。</li>
 * </ul>
 *
 * <p>失败语义（两侧一致，调用方按此收敛判定）：
 * <ul>
 *   <li>policyVersion 与平台当前值不相等 → {@link StaleVersionException}（STALE，409 语义）；</li>
 *   <li>事实源不可用 / 响应不可信 / 候选形状非法 → {@link com.nageoffer.ai.ragent.framework.exception.ServiceException}
 *       （UNKNOWN，503 语义，不放行）。</li>
 * </ul>
 */
public interface PlatformFactsPort {

    /**
     * 单个候选：owner 成员 / owner 部门 / 主体引用 / 数据权限 / 仅校验主体存在，五种形态互斥。
     *
     * @param ownerMemberId owner 成员（canonical membershipId）
     * @param ownerDeptId   owner 部门 ID（十进制串）
     * @param subjectRefs   主体引用集合（如 {@code member:platform:T1:2101}、{@code role:9101}）
     * @param dataScope     数据权限候选（owner 事实 + 平台数据范围判定）
     * @param validateOnly  仅校验主体引用是否为当前租户的有效主体
     */
    record Candidate(String ownerMemberId, String ownerDeptId, List<String> subjectRefs,
                     boolean dataScope, boolean validateOnly) {

        /** 主体引用候选（SubjectMatchPort 的 ACL 主体存活判定）。 */
        public static Candidate subjectRefs(List<String> refs) {
            return new Candidate(null, null, refs, false, false);
        }

        /** 数据权限候选（owner 成员/部门二选一或并空）。 */
        public static Candidate dataScope(String ownerMemberId, String ownerDeptId) {
            return new Candidate(ownerMemberId, ownerDeptId, null, true, false);
        }

        /** 仅校验主体引用是否存在（ACL 授权对象在线核实）。 */
        public static Candidate validateOnly(String ref) {
            return new Candidate(null, null, List.of(ref), false, true);
        }
    }

    /**
     * @param policyVersion    platform 当前策略版本（与请求 pv 精确相等）
     * @param matches          与候选顺序一一对应的布尔匹配结果
     * @param principalDeptId  主体当前部门 ID；主体无部门时为 null
     */
    record MatchOutcome(int policyVersion, List<Boolean> matches, String principalDeptId) {
    }

    /**
     * 逐候选询问平台事实。
     *
     * @throws StaleVersionException platform 策略版本与请求不一致（409 语义）
     * @throws com.nageoffer.ai.ragent.framework.exception.ServiceException 事实源不可用或响应不可信（503 语义）
     */
    MatchOutcome match(ExecutionPrincipal principal, List<Candidate> candidates, String action);
}
