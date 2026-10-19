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

package com.nageoffer.ai.ragent.shortdrama.service;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;

/**
 * 短剧用例的显式执行人快照。
 *
 * <p><b>为什么把 tenant/user 从主体里取出来再传。</b>F21:0 的流式创建在 LLM 回调线程
 * 完成落库；LLM 客户端线程池只保证 TTL 传播，不保证 SaToken 登录上下文存在。
 * 因此服务层不接受"当前线程上再去读一次"的隐式身份，而是由控制器在入口解析
 * {@link ExecutionPrincipal} 后把两个不可变值显式传到每一个用例。
 *
 * <p><b>为什么 userId 保留字符串形态。</b>主体契约里 userId 是十进制字符串
 * （{@link ExecutionPrincipal#requireUserId}）；到 SQL 参数时才转 {@code long}。
 * 解析失败必须拒绝，不做 0 兜底 —— 0 会命中意外的 {@code user_id = 0} 行。
 */
public record DramaActor(String tenantId, String userId, String membershipId) {

    public static DramaActor from(ExecutionPrincipal principal) {
        return new DramaActor(principal.tenantId(), principal.userId(), principal.membershipId());
    }

    /**
     * 转成 {@code bigint} 列可用的用户 id。
     *
     * @throws P04AiException 主体 userId 不是十进制长整型
     */
    public long numericUserId() {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw new P04AiException(P04AiErrorCode.TENANT_CONTEXT_MISSING,
                    "主体 userId 非数值，拒绝查询");
        }
    }
}
