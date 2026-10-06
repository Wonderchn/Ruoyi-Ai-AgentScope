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

package com.nageoffer.ai.ragent.runtime.config;

/**
 * run 级配置绑定端口（C1.2）。
 *
 * <p><b>命名说明。</b>T0 裁决里的写法是 {@code RunConfigBinding.requireBoundRevision(tenantId, runId)}；
 * 本包把它拆成**两个类型**：这个端口（接口）与 {@link RunConfigBinding}（返回的事实）。
 * 理由：把方法写在数据记录上会让"事实"同时承担"取事实的方式"，而事实一旦能自己查库，
 * 就无法在测试里替换成固定值；拆开后 {@code ChatGateway} 侧只要拿到一个 {@code RunConfigBinding}，
 * 不依赖任何数据库。若 T0 要求严格同名，改名为 {@code RunConfigBindingAuthority} 即可，语义不变。
 *
 * <p><b>谁调用它。</b>传输层把 run 身份传进来之后（T3 在 {@code ChatGateway} 侧的工作），
 * 需要"这次调用用哪份配置"的提供方（{@code RealChatGateway} 等）从这里取。
 * 端口刻意**不接受**"当前主体"作为可选回退：一旦允许回退到 {@code PrincipalContext}
 * 取当前版本，就会在 run 与主体不一致时静默用错版本。
 *
 * <p><b>fail-closed（C1.2 第 3 行）。</b>以下情形一律抛 {@link ConfigAuthorityUnavailable}，
 * **不得**用"当前最新版本"顶替，也不得回退 YAML：
 * <ul>
 *   <li>run 不存在；</li>
 *   <li>run 未绑定版本（{@code config_revision_id IS NULL}，即受理于权威建立之前）；</li>
 *   <li>绑定版本在库中不存在（原版本缺失 → 明确拒绝，而不复用当前版本）；</li>
 *   <li>绑定版本已被撤权（{@code state='REVOKED'}）—— 撤权**立即生效**，不受 run 固定版本保护（C1.3）；</li>
 *   <li>run 上记录的事实与绑定版本行不一致（篡改/不一致信号 → 拒绝而不是挑一个信）。</li>
 * </ul>
 */
public interface RunConfigBindingPort {

    /**
     * 解析某个 run 受理时绑定的配置版本事实。
     *
     * @param tenantId 租户（必填；不接受 null/空白）
     * @param runId    run 标识（必填）
     * @throws ConfigAuthorityUnavailable 无法确定唯一且自洽的绑定版本
     */
    RunConfigBinding requireBoundRevision(String tenantId, String runId);
}
