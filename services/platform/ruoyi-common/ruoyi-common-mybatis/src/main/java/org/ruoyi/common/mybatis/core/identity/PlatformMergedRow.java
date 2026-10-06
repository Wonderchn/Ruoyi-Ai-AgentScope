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

/**
 * 统一库里带平台身份列的实体（E5/WP-026）。
 *
 * <p>合并表（V7 按 AI 形状建表、V9 追加平台侧独有列）里有<b>没有旧列来源</b>的
 * {@code NOT NULL} 身份列：{@code ai_conversation.member_id}、{@code ai_message.member_id}、
 * {@code ai_knowledge_base.owner_member_id} 等。平台侧实体原本没有这些字段，写路径也没有来源，
 * 落到统一库上就是插入失败——或者更糟，靠列默认值把不同租户写进同一个桶。
 *
 * <p>实现本接口把"这一行必须有平台身份"变成编译期可见的约束，由
 * {@code InjectionMetaObjectHandler} 在插入时从<b>已登录的平台主体</b>
 * （{@code LoginHelper} 的 {@code LoginUser}）填充：不接受请求体取值，
 * 也不使用 {@code InjectionMetaObjectHandler} 的"无用户填 -1"兜底——身份没有匿名默认值。
 *
 * <p>命名与 AI 侧的 {@code com.nageoffer.ai.ragent.framework.convention.MergedTableRow}
 * 有意区分：两边各自从自己上下文的可信主体取值，但 canonical 格式必须完全一致
 * （见 {@link PlatformMembershipId}）。
 */
public interface PlatformMergedRow {

    /** 写入平台租户列（{@code tenant_id}，varchar(64)）。 */
    void setTenantId(String tenantId);

    /**
     * 写入 canonical 成员列（{@code member_id}，varchar(160)）。
     *
     * <p>列为 {@code owner_member_id} 的实体（知识库、摄取管道）在这里显式桥接到自己的字段，
     * 不靠列名巧合。
     */
    void setMemberId(String memberId);
}
