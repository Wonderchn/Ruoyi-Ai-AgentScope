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
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;

/**
 * 短剧内层面（{@code /internal/ai/v1/short-drama/**}）的动作 scope 复核。
 *
 * <p>网关已按白名单路由做过一次动作到权限的判定；本层再核一次不是重复劳动，
 * 而是 C3.1 的要求：功能权限、动作 scope、资源授权、租户边界必须在<b>同一边界内</b>
 * 闭合。内嵌形态的主体由 {@code LocalAiGatewayClient.dispatchTo} 构造，两侧一旦漂移，
 * "网关已校验"不能成为内层免检的理由。缺主体 401、缺 scope 403，均不退化。
 */
public final class DramaAccess {

    /** 读面动作：项目/脚本/资产列表与详情。 */
    public static final String ACTION_READ = "drama.read";

    /** 写面动作：项目与脚本的手动增删改。 */
    public static final String ACTION_WRITE = "drama.write";

    /** 生成面动作：从创意创建、脚本生成/润色、资产分析、摄影规则、表演指导。 */
    public static final String ACTION_GENERATE = "drama.generate";

    private DramaAccess() {
    }

    /**
     * 取当前主体并复核 action scope。
     *
     * @param action canonical 动作（必须与网关 ROUTES 登记的动作逐字一致）
     * @throws P04AiException 缺主体 → 401；缺 scope → 403
     */
    public static ExecutionPrincipal require(String action) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }
        if (action == null || action.isBlank() || !principal.hasScope(action)) {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
        return principal;
    }
}
