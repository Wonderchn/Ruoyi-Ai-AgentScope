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

/**
 * platform 层许可镜像端口：AI 运行时在登记本层 {@code ai_execution_permit} 的同时，
 * 把动作级许可同步登记到 platform 层（{@code sys_ai_execution_permit}），
 * 使平台侧撤权/屏障对 AI 运行可见。
 *
 * <p>两种形态同一语义：
 * <ul>
 *   <li><b>独立运行</b>：HTTP 回调 platform 的
 *       {@code /internal/platform/v1/authorization/permits/acquire|release}（services/ai 冻结拷贝）；</li>
 *   <li><b>内嵌运行</b>：同进程本地接口直接调用 platform 许可提供者（E3 装配，
 *       不经过任何 localhost HTTP）。</li>
 * </ul>
 *
 * <p>失败语义：platform 判定 policyVersion 陈旧 → {@link StaleVersionException}；
 * 其余不可用/拒绝 → {@link com.nageoffer.ai.ragent.framework.exception.ServiceException}
 * （不放行；调用方保持本层 permit 的 rollback 释放语义）。
 */
public interface PlatformPermitPort {

    /**
     * @param tenantId        租户
     * @param subject         用户 ID（十进制串）
     * @param membershipId    canonical 成员标识
     * @param policyVersion   平台策略版本（须与平台当前值相等）
     * @param aclVersion      AI 侧 ACL 版本
     * @param action          canonical 动作
     * @param resourceRef     资源引用
     * @param resourceRefsHash 资源引用哈希（sha-256 hex）
     * @param operationId     操作标识（幂等关联）
     */
    record AcquireRequest(String tenantId, String subject, String membershipId, int policyVersion,
                          int aclVersion, String action, String resourceRef, String resourceRefsHash,
                          String operationId) {
    }

    record AcquireResult(String permitId, int policyVersion, String operationId) {
    }

    record ReleaseRequest(String tenantId, String membershipId, String permitId, String operationId) {
    }

    /**
     * @throws StaleVersionException 平台策略版本已变化
     * @throws com.nageoffer.ai.ragent.framework.exception.ServiceException 平台许可不可用或拒绝
     */
    AcquireResult acquire(AcquireRequest request);

    /**
     * @throws com.nageoffer.ai.ragent.framework.exception.ServiceException 释放未确认
     */
    void release(ReleaseRequest request);
}
