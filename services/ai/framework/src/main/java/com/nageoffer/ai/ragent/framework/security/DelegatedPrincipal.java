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

import java.util.Set;

/**
 * 经验签成立的委托主体。
 *
 * <p>口径：{@code principalId = membershipId}（Spec §7.2）。不参与判定的字段（如用户名）
 * 不在其中——同名用户不得被合并。
 *
 * @param issuer        签发方（必须等于受信 issuer）
 * @param tenantId      租户
 * @param subject       主体（人）
 * @param membershipId  成员身份；即 principalId
 * @param policyVersion platform 策略版本
 * @param scopes        功能 scope
 * @param jti           一次性标识；已用于防重放
 */
public record DelegatedPrincipal(String issuer, String tenantId, String subject, String membershipId,
                                 int policyVersion, Set<String> scopes, String jti) {
}
