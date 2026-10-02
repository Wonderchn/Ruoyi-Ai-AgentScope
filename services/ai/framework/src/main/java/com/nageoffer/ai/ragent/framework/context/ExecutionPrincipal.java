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

package com.nageoffer.ai.ragent.framework.context;

import com.nageoffer.ai.ragent.framework.exception.ClientException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 生产执行主体（P1 的不可伪造身份对象）。
 *
 * <p>冻结口径（05 §4.1）：
 * <ul>
 *   <li>{@code tenantId}：字符串 1..64；platform 的 tenant 组成部分<b>不允许冒号</b>，
 *       出现冒号说明来源格式不满足契约，必须拒绝而不是截断或回落到默认租户；</li>
 *   <li>{@code userId}：platform 当前 userId 的十进制字符串 1..20；</li>
 *   <li>{@code membershipId}：canonical {@code platform:<tenantId>:<userId>}，
 *       解析后必须再按 tenant/user 查真实当前成员，<b>同名不合并</b>；</li>
 *   <li>{@code policyVersion} / {@code aclVersion}：均 ≥ 1，缺任一即拒绝；
 *       不允许"没有版本就当有效"。</li>
 * </ul>
 *
 * <p>本记录只能由验签组件或在线判定组件构造。业务 body / header 里的
 * {@code tenantId}、{@code userId}、{@code membershipId} <b>不参与</b>主体选择。
 */
public record ExecutionPrincipal(
        String tenantId,
        String userId,
        String membershipId,
        int policyVersion,
        int aclVersion,
        Set<String> scopes,
        String jti,
        String issuer,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond) {

    /** tenantId 上限，与 05 §4.1 一致。 */
    public static final int MAX_TENANT_ID_LENGTH = 64;

    /** userId 上限：platform user_id 是 int8，十进制最长 20 位。 */
    public static final int MAX_USER_ID_LENGTH = 20;

    /** membershipId 列宽（varchar(160)），与 05 §4.1 一致。 */
    public static final int MAX_MEMBERSHIP_ID_LENGTH = 160;

    /** canonical membership 前缀。 */
    private static final String MEMBERSHIP_PREFIX = "platform:";

    public ExecutionPrincipal {
        requireTenantId(tenantId);
        requireUserId(userId);
        requireMembershipId(membershipId, tenantId, userId);
        if (policyVersion < 1) {
            throw new ClientException("policyVersion must be >= 1");
        }
        if (aclVersion < 1) {
            throw new ClientException("aclVersion must be >= 1");
        }
        scopes = scopes == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(scopes));
        if (jti == null || jti.isBlank()) {
            throw new ClientException("jti is required");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new ClientException("issuer is required");
        }
    }

    /**
     * 校验 tenantId 的字符与长度契约。
     *
     * @throws ClientException tenantId 为空、超长或含冒号
     */
    public static void requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ClientException("tenantId is required");
        }
        if (tenantId.length() > MAX_TENANT_ID_LENGTH) {
            throw new ClientException("tenantId exceeds " + MAX_TENANT_ID_LENGTH + " characters");
        }
        if (tenantId.indexOf(':') >= 0) {
            // 冒号是 membership 编码的分隔符：允许它会让 platform:a:b:c 产生歧义解析
            throw new ClientException("tenantId must not contain ':'");
        }
        if (!tenantId.equals(tenantId.trim())) {
            throw new ClientException("tenantId must not have surrounding whitespace");
        }
    }

    /** 校验 userId 为十进制字符串且长度不超过列宽。 */
    public static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ClientException("userId is required");
        }
        if (userId.length() > MAX_USER_ID_LENGTH) {
            throw new ClientException("userId exceeds " + MAX_USER_ID_LENGTH + " characters");
        }
        for (int i = 0; i < userId.length(); i++) {
            if (!Character.isDigit(userId.charAt(i))) {
                throw new ClientException("userId must be a decimal string");
            }
        }
    }

    /**
     * 校验 canonical membership 引用：必须是 {@code platform:<tenantId>:<userId>}
     * 且与本次主体的 tenant/user 精确一致。
     *
     * <p>刻意不做"尽力解析"：解析不出来就拒绝，避免 {@code platform:T1:2101:extra}
     * 这类输入被悄悄截断成合法 member。
     */
    public static void requireMembershipId(String membershipId, String tenantId, String userId) {
        if (membershipId == null || membershipId.isBlank()) {
            throw new ClientException("membershipId is required");
        }
        if (membershipId.length() > MAX_MEMBERSHIP_ID_LENGTH) {
            throw new ClientException("membershipId exceeds " + MAX_MEMBERSHIP_ID_LENGTH + " characters");
        }
        String expected = canonicalMembershipId(tenantId, userId);
        if (!expected.equals(membershipId)) {
            throw new ClientException("membershipId must be the canonical " + expected);
        }
    }

    /** 生成 canonical membershipId；tenant/user 非法时同样拒绝。 */
    public static String canonicalMembershipId(String tenantId, String userId) {
        requireTenantId(tenantId);
        requireUserId(userId);
        return MEMBERSHIP_PREFIX + tenantId + ":" + userId;
    }

    /** 判断是否具备某个 scope（大小写敏感，不做前缀放行）。 */
    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    /** 判断是否具备全部 scope。空集合不构成"全部具备"。 */
    public boolean hasAllScopes(Collection<String> required) {
        if (required == null || required.isEmpty()) {
            return false;
        }
        return scopes.containsAll(required);
    }

    /** 该主体所属的 tenant 级 epoch 定位（用于日志与证据，不含客户内容）。 */
    public String tenantScope() {
        return tenantId + "/" + membershipId;
    }
}
