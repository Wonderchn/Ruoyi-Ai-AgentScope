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

package org.ruoyi.aiintegration.delegation;

/**
 * 负例凭证的构造变体。
 *
 * <p>只有 {@link #NONE} 是合法凭证；其余变体由<b>测试专用</b>的签发适配器构造，
 * 用于 P0.4 的 G2 拒绝断言（Spec §7.4 的 N01/N02/N06）。这些变体只存在于最小测试应用
 * 暴露的私有端口上，生产接线属 P1。
 */
public enum DelegationVariant {

    /** 合法委托。 */
    NONE,

    /** {@code aud} 不是本服务。 */
    WRONG_AUDIENCE,

    /** {@code iss} 不是受信签发方。 */
    WRONG_ISSUER,

    /** 已过期（{@code exp} 在过去）。 */
    EXPIRED,

    /** 尚未生效（{@code nbf} 在未来）。 */
    NOT_YET_VALID,

    /** 缺少租户声明。 */
    MISSING_TENANT,

    /** 缺少成员声明。 */
    MISSING_MEMBERSHIP,

    /** 缺少策略版本声明。 */
    MISSING_POLICY_VERSION,

    /** 缺少主体声明。 */
    MISSING_SUBJECT,

    /** 缺少 jti（一次性标识）。 */
    MISSING_JTI,

    /** 缺少过期时间。 */
    MISSING_EXPIRATION,

    /** 用受信私钥签发，但 kid 不在受信集合内。 */
    UNKNOWN_KID,

    /** 用另一把不受信私钥签发（签名校验必须失败）。 */
    FOREIGN_KEY,

    /** 手工构造 {@code alg=none} 的无签名 JWS（解析器必须拒绝）。 */
    ALG_NONE
}
