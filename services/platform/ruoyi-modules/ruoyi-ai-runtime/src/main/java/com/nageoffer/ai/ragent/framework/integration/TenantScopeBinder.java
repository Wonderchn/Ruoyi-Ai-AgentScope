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

package com.nageoffer.ai.ragent.framework.integration;

/**
 * 租户作用域绑定端口（ENV-BATCH-5b 修，2026-10-11；真 broker 首验实证）。
 *
 * <p><b>为什么需要。</b>AI 侧异步线程（MQ 消费者等）没有登录主体，而平台侧
 * MyBatis 租户行拦截器（{@code tenant.enable=true}）对业务表（如 {@code ai_message}）
 * fail-closed——无主体且无动态租户时在**任何数据库操作之前**拒绝
 * （首跑实测错误：{@code tenant.context.missing}）。异步线程必须先把事件携带的租户
 * 绑定到当前线程，再进入落库链。
 *
 * <p><b>分层。</b>本接口留在 AI 侧命名空间，由平台侧（内嵌装配，ruoyi 模块）实现——
 * AI 侧模块刻意不依赖 {@code org.ruoyi.common.*}（既有分层：平台设施经端口桥接，
 * 同 {@link SaasCapabilityBoundary} 的处理方式）。平台侧实现等价于
 * {@code TenantHelper.dynamic(tenantId, handle)}；独立应用没有租户拦截器时本端口缺席，
 * 调用方按直接执行处理。
 */
public interface TenantScopeBinder {

    /**
     * 在 {@code tenantId} 的租户作用域内执行 {@code handle}（进入时绑定、退出时清理；
     * 与调用线程的既有作用域互不污染）。
     */
    void runWithTenant(String tenantId, Runnable handle);
}
