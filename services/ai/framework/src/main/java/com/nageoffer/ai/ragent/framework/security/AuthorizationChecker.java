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

/**
 * platform 在线授权复核的抽象。
 *
 * <p>抽出接口是为了让受理编排能在<b>无外部服务</b>的单测层验证"权限检查先于幂等命中"这一顺序
 * 约束（Spec §7.1 第一层）；真实 HTTP 行为（超时/503/坏响应映射）由集成层覆盖。
 */
public interface AuthorizationChecker {

    /**
     * @param allowed       是否放行
     * @param policyVersion platform 当前策略版本
     */
    record AuthorizeResult(boolean allowed, int policyVersion) {
    }

    /**
     * @throws P04AiException 平台侧符号码（403/404/409 等）或 503 {@code AUTHORIZATION_UNAVAILABLE}
     */
    AuthorizeResult check(DelegatedPrincipal principal, String action, String resourceRef);
}
