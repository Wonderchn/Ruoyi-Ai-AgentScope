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

package org.ruoyi.common.mybatis.core.identity;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.domain.model.LoginUser;
import org.ruoyi.common.satoken.utils.LoginHelper;

/**
 * canonical 成员标识的平台侧规则（E5/WP-026）：{@code platform:<tenantId>:<userId>}。
 *
 * <p>规则与 AI 侧 {@code ExecutionPrincipal.canonicalMembershipId} 必须逐条一致
 * （tenant 不含冒号且 1..64、userId 为十进制串且 1..20、结果 ≤160）；
 * 两侧无法互相依赖（平台公共模块不依赖 AI runtime），因此规则在这里独立实现，
 * 并由 {@code PlatformMembershipIdParityTest} 钉住两者对同一输入的输出相等——
 * 只靠"记得同步改两处"是不够的。
 */
public final class PlatformMembershipId {

    public static final String PREFIX = "platform:";
    public static final int MAX_TENANT_ID_LENGTH = 64;
    public static final int MAX_USER_ID_LENGTH = 20;
    public static final int MAX_MEMBERSHIP_ID_LENGTH = 160;

    private PlatformMembershipId() {
    }

    /** 校验租户标识；非法即抛（不截断、不回落默认租户）。 */
    public static void requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ServiceException("tenantId is required for a merged-table write");
        }
        if (tenantId.length() > MAX_TENANT_ID_LENGTH) {
            throw new ServiceException("tenantId exceeds " + MAX_TENANT_ID_LENGTH + " characters");
        }
        if (tenantId.indexOf(':') >= 0) {
            // 冒号是 membership 编码的分隔符：允许它会让 platform:a:b:c 产生歧义解析
            throw new ServiceException("tenantId must not contain ':'");
        }
        if (!tenantId.equals(tenantId.trim())) {
            throw new ServiceException("tenantId must not have surrounding whitespace");
        }
    }

    /** 校验用户标识：十进制字符串（平台 userId 是 int8）。 */
    public static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ServiceException("userId is required for a merged-table write");
        }
        if (userId.length() > MAX_USER_ID_LENGTH) {
            throw new ServiceException("userId exceeds " + MAX_USER_ID_LENGTH + " characters");
        }
        for (int i = 0; i < userId.length(); i++) {
            if (!Character.isDigit(userId.charAt(i))) {
                throw new ServiceException("userId must be a decimal string");
            }
        }
    }

    /** 生成 canonical 成员标识；tenant/user 非法时拒绝而不是"尽力拼接"。 */
    public static String canonical(String tenantId, String userId) {
        requireTenantId(tenantId);
        requireUserId(userId);
        String value = PREFIX + tenantId + ":" + userId;
        if (value.length() > MAX_MEMBERSHIP_ID_LENGTH) {
            throw new ServiceException("membership id exceeds " + MAX_MEMBERSHIP_ID_LENGTH + " characters");
        }
        return value;
    }

    /**
     * 当前登录主体的 canonical 成员标识。
     *
     * <p><b>缺失即拒绝</b>：没有登录主体时不存在"匿名成员"，不做 {@code -1} 之类的兜底
     * ——那会把后台任务与定时写入静默归到一个人造成员名下。
     */
    public static String requireCurrentMembershipId() {
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser == null) {
            throw new ServiceException("no authenticated platform principal for a merged-table write");
        }
        if (loginUser.getUserId() == null) {
            throw new ServiceException("authenticated principal has no userId");
        }
        return canonical(loginUser.getTenantId(), String.valueOf(loginUser.getUserId()));
    }

    /** 当前登录主体的租户标识（缺失即拒绝）。 */
    public static String requireCurrentTenantId() {
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser == null) {
            throw new ServiceException("no authenticated platform principal for a merged-table write");
        }
        requireTenantId(loginUser.getTenantId());
        return loginUser.getTenantId();
    }
}
