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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;

import java.util.function.Supplier;

/**
 * Agent 引擎公开面的主体/scope/身份桥（C13 + C3 + C6）。
 *
 * <p><b>为什么引擎面需要一个共享的边界帮助类，而不是每个方法各写一遍。</b>
 * 本包的历史缺陷正是"两处各写一遍、其中一处漏了一层"（WP-033B 的可达性缺口）。
 * 授权判定分散复制时，漏掉的那一份不会有任何判据报警。这里把它收敛成三个方法，
 * 四条端点只能通过它们取主体。
 *
 * <p><b>为什么内层还要复核 scope（网关不是已经校验过路由动作了吗）。</b>
 * C3.1 要求"功能权限 + 动作 scope + 资源授权 + 租户边界"四层同时成立。网关按白名单
 * 校验客户端路径到动作的映射，但内嵌形态下内层主体是本地桥接构造的（
 * {@code LocalAiGatewayClient.dispatchTo} 里由平台登录事实构造 {@link ExecutionPrincipal}），
 * 一旦两侧漂移，"网关已校验"就不构成内层免检的理由。授权判定必须与请求路径在
 * <b>同一边界内闭合</b>（fail-closed）：缺主体、缺 scope 一律拒绝。
 *
 * <p><b>身份桥（{@code PrincipalContext} → {@code UserContext}）。</b>
 * 引擎链（{@code AgentChatServiceImpl}）从 {@code UserContext.getUserId()} 取用户，
 * 而 {@code UserContext} 只由独立 AI 应用的 {@code UserContextInterceptor} 填充 —— 内嵌进程
 * 里<b>没有任何组件设置它</b>。直接注册引擎面会让每条请求都拿到 {@code userId == null}：
 * 要么查不到数据，要么把作用域退化成"没有用户限定"（跨用户读写的直接成因）。
 * 平台的规范身份源是 {@link PrincipalContext}，因此这里做一次显式桥接，
 * <b>并在委托返回后清理</b>，避免把身份泄漏给同一线程上的后续工作。
 *
 * <p>桥接是同步作用域：{@code AgentChatServiceImpl} 在方法入口就把 {@code userId} 读成局部变量
 * 再逐层传参（不依赖线程上下文贯穿异步段），所以"返回即清理"不会打断已经启动的流。
 */
final class AgentEngineSurface {

    private AgentEngineSurface() {
    }

    /**
     * 取执行主体并复核 scope。
     *
     * @param action canonical 动作（与 C13.4 登记的路由动作一致）
     */
    static ExecutionPrincipal requireScope(String action) {
        ExecutionPrincipal principal = PrincipalContext.get();
        if (principal == null) {
            // C6：缺身份一律拒绝，绝不退化成"无用户限定"
            throw new P04AiException(P04AiErrorCode.AUTH_REQUIRED);
        }
        if (!principal.hasScope(action)) {
            throw new P04AiException(P04AiErrorCode.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 在 {@code UserContext} 已桥接的前提下执行引擎调用。
     *
     * <p>用 try/finally 恢复而不是只 clear：调用点可能已经处在另一个 {@code UserContext}
     * 作用域内（例如网关直调链路上的复用线程），无条件 clear 会把外层的身份抹掉。
     */
    static <T> T asUser(ExecutionPrincipal principal, Supplier<T> body) {
        LoginUser previous = UserContext.get();
        UserContext.set(loginUser(principal));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                UserContext.clear();
            } else {
                UserContext.set(previous);
            }
        }
    }

    private static LoginUser loginUser(ExecutionPrincipal principal) {
        LoginUser user = new LoginUser();
        user.setUserId(principal.userId());
        user.setUsername(principal.membershipId());
        return user;
    }
}
