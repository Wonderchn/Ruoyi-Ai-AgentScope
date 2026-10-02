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

package org.ruoyi.system.aiidentity;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;

/**
 * AI 权限写口的策略版本护栏（U04/P1.2b）。
 *
 * <p><b>事务契约：本护栏的 {@link #bump(Collection)} 与 {@link #bumpTenant(String)}
 * 必须在使用方的同一事务内调用</b>——版本递增与触发它的身份事实变更（角色-菜单、
 * 部门树、用户-角色、租户、套餐等写操作）必须同生共死：递增失败则事实变更一起回滚，
 * 事实变更失败则版本不前移。本类会主动校验当前线程存在真实事务，未开启事务的调用
 * 属于编程错误，直接抛出 {@link IllegalStateException} 而不是静默失效。
 *
 * <p>受影响租户集合由使用方显式枚举（通常是记录的 tenant_id；批量导入取出现过的
 * 租户集合），<b>不允许「全部租户」</b>。多个租户按排序后的顺序逐个递增以防死锁
 * （排序在 {@link AiPolicyRevisionService#bumpAll} 内完成）。
 *
 * @author AI-Integration
 */
@RequiredArgsConstructor
@Service
public class AiPolicyMutationGuard {

    private final AiPolicyRevisionService policyRevisionService;

    /**
     * 对受影响租户集合逐个递增策略版本（排序去重，防死锁）。
     *
     * @param tenantIds 受影响租户集合（必须显式可枚举；空集合为无操作）
     */
    public void bump(Collection<String> tenantIds) {
        requireActiveTransaction();
        policyRevisionService.bumpAll(tenantIds);
    }

    /**
     * 对单个租户递增策略版本。
     *
     * @param tenantId 受影响租户
     */
    public void bumpTenant(String tenantId) {
        requireActiveTransaction();
        policyRevisionService.bump(tenantId);
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "AiPolicyMutationGuard 必须在使用方的同一事务内调用：版本递增与身份事实变更必须同事务提交/回滚");
        }
    }

}
