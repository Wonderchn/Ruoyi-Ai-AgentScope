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
 * 平台→AI 的服务身份核验（U06/P1.2c）。
 *
 * <p>与委托验签（{@link DelegatedPrincipalFilter}）是两条独立防线：委托凭证证明
 * "代表哪个成员"，本类证明"调用方确实是 platform 服务"。核验方式为
 * {@code X-P04-Service-Credential} 头与配置值的<b>常量时间比较</b>
 * （{@link java.security.MessageDigest#isEqual}，与 platform 侧
 * {@code ProductionAuthorizationController} 同一口径），避免逐字节短路造成时序侧信道。
 *
 * <p>失败语义（401 两段）：
 * <ul>
 *   <li>请求未携带凭证（空白）→ 401 {@code AUTH_REQUIRED}；</li>
 *   <li>配置空白（<b>空配置 = 全部拒绝</b>，绝不降级放行）或不匹配 →
 *       401 {@code DELEGATION_INVALID}。不区分「未配置」与「不匹配」的对外表现，
 *       避免给攻击者反馈配置状态。</li>
 * </ul>
 */
public class ServiceIdentityVerifier {

    /** 服务凭证头名称（与 platform 侧内部端点同名）。 */
    public static final String SERVICE_CREDENTIAL_HEADER = "X-P04-Service-Credential";

    private final String configuredCredential;

    public ServiceIdentityVerifier(ProductionDelegationProperties properties) {
        this.configuredCredential = properties.getServiceCredential();
    }

    /**
     * 核验请求呈现的服务凭证。
     *
     * @param presentedCredential 请求头 {@code X-P04-Service-Credential} 的原始值（可为 {@code null}）
     * @throws P04AiException 401 {@code AUTH_REQUIRED}（未携带）或
     *                        401 {@code DELEGATION_INVALID}（空配置/不匹配）
     */
    public void verify(String presentedCredential) {
        if (presentedCredential == null || presentedCredential.isBlank()) {
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }
        if (configuredCredential == null || configuredCredential.isBlank()
                || !constantTimeEquals(configuredCredential, presentedCredential)) {
            // 空配置与不匹配统一对外为同一码：端点整体不可用，不给探测者任何区分度
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }
    }

    /** 常量时间比较：长度与内容都不短路（MessageDigest.isEqual 的契约）。 */
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
