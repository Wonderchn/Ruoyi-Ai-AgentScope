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

package com.nageoffer.ai.ragent.framework.convention;

/**
 * 统一库里带租户/成员身份行的行对象（E5/WP-025）。
 *
 * <p>V7 给合并表与 AI 表加了平台侧身份列，其中 {@code member_id}（或
 * {@code owner_member_id}）是 {@code NOT NULL} 且**没有数据库默认值**，
 * {@code tenant_id} 虽有 {@code DEFAULT '0'} 但默认值不是身份。实现本接口把
 * "这一行必须有身份"从约定变成**编译期可见**的约束：没有实现它的实体不会被
 * {@link com.nageoffer.ai.ragent.authorization.AiDomainWriteIdentity} 填充，
 * 于是"漏了一个写路径"会体现为插入失败，而不是静默写进默认租户。
 *
 * <p>取值只能来自可信执行主体（见 {@code AiDomainWriteIdentity}），
 * <b>不得</b>来自请求体、后台默认值或用户可影响的环境。
 */
public interface MergedTableRow {

    /** 写入平台租户列（{@code tenant_id}，varchar(64)，数值旧租户按文本）。 */
    void setTenantId(String tenantId);

    /** 写入 canonical 成员列（{@code member_id} / {@code owner_member_id}，varchar(160)）。 */
    void setMemberId(String memberId);
}
