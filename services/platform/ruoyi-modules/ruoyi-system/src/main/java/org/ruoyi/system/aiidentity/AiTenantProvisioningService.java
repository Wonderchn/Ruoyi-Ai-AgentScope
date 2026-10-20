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

/**
 * 新租户的 AI 运行前置初始化（必须在租户创建事务内调用）。
 *
 * <p>为什么需要这一步：AI 网关对每个租户要求两件事同时成立——
 * <ol>
 *   <li>{@code ai_acl_epoch} 有该租户的一行（没有行时身份事实不可得，
 *       网关 fail-closed 成 503 {@code AUTHORIZATION_UNAVAILABLE}）；</li>
 *   <li>该租户至少有一个 {@code PUBLISHED} 的
 *       {@code ai_runtime_config_revision}（没有发布版本时受理直接拒绝，
 *       知识问答与 Agent 任务全部不可用）。</li>
 * </ol>
 * 旧 provisioning 只建了 {@code sys_ai_policy_revision}，上述两项靠部署夹具手工补，
 * 于是“管理端能建出租户、租户管理员也能登录，但所有 AI 功能 503”。本服务把这两项
 * 纳入与租户创建同一个事务：要么租户完整可用，要么整体回滚。
 *
 * <p>运行配置的来源是默认租户（{@code 000000}）最新 PUBLISHED 版本：新租户继承
 * 部署默认模型；此后该租户可在“AI → 模型与提供方”自行发布新版本覆盖。
 * 若默认租户自身还没有发布版本，则跳过（不伪造配置），由平台管理员首次发布。
 */
public interface AiTenantProvisioningService {

    /**
     * 初始化新租户的 ACL epoch 与默认运行配置。
     *
     * @param tenantId       新租户编号
     * @param operatorUserId 该租户管理员用户 id（写入运行配置的 operator_id）
     */
    void initialize(String tenantId, Long operatorUserId);
}
