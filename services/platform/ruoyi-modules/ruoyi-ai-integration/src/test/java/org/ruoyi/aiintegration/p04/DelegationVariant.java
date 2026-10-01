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

package org.ruoyi.aiintegration.p04;

/**
 * <b>测试专用</b>的负例凭证变体（阻断修复 Spec §2.3）。
 *
 * <p>原本位于主源集；移入测试源集是刻意的：否则 P1 开启委托功能时，会连同
 * "任意身份 + 负例凭证"的铸造能力一起开启。只有 {@link #NONE} 是合法委托，
 * 且合法路径由主源集 {@code DelegationIssuer} 产出，不在此处自造。
 */
public enum DelegationVariant {

    /** 合法委托（走主源集签发器）。 */
    NONE,

    /** {@code aud} 不是本服务。 */
    WRONG_AUDIENCE,

    /** {@code iss} 不是受信签发方。 */
    WRONG_ISSUER,

    /** 已过期。 */
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

    /** 缺少 jti。 */
    MISSING_JTI,

    /** 缺少过期时间。 */
    MISSING_EXPIRATION,

    /** 缺少签发时间。 */
    MISSING_ISSUED_AT,

    /** TTL 超过冻结上限（60 秒）。 */
    TTL_OVER_CEILING,

    /** 用受信私钥签发，但 kid 不在受信集合内。 */
    UNKNOWN_KID,

    /** 用另一把不受信私钥签发（签名校验必须失败）。 */
    FOREIGN_KEY,

    /** 手工构造 {@code alg=none} 的无签名 JWS。 */
    ALG_NONE
}
