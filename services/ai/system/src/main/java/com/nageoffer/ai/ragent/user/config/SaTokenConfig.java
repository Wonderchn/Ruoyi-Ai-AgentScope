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

package com.nageoffer.ai.ragent.user.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * SaToken 配置类
 * 配置登录拦截和用户上下文拦截器
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 用户上下文拦截器
     */
    private final UserContextInterceptor userContextInterceptor;

    /**
     * 拦截器全局顺序：登录(0) → 演示只读(10，由 RagentWebMvcConfiguration 注册) → 用户上下文(20)
     */
    public static final int ORDER_LOGIN = 0;
    public static final int ORDER_DEMO_MODE = 10;
    public static final int ORDER_USER_CONTEXT = 20;

    /**
     * 添加拦截器配置
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 SaToken 登录拦截器
        registry.addInterceptor(new SaInterceptor(handler -> {
                    // 异步调度请求跳过登录检查（SSE 完成回调会触发 asyncDispatch，此时 SaToken 上下文已丢失）
                    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attrs != null) {
                        HttpServletRequest request = attrs.getRequest();
                        // 判断是否为异步调度请求，如果是则跳过登录检查
                        if (request.getDispatcherType() == DispatcherType.ASYNC) {
                            return;
                        }
                        // 预检请求直接放行，避免 CORS 被拦截
                        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                            return;
                        }
                    }
                    // 执行登录检查
                    StpUtil.checkLogin();
                }) {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                        String path = request.getRequestURI().substring(request.getContextPath().length());
                        if (path.startsWith("/internal/ai/v1/")
                                && (com.nageoffer.ai.ragent.framework.context.PrincipalContext.get() != null
                                || (path.equals("/internal/ai/v1/authorization/barriers")
                                && Boolean.TRUE.equals(request.getAttribute("ai.service.authenticated"))))) { return true; }
                        // 未注册的实验路径应保持404；只处理静态资源兜底，已注册接口仍完整走登录检查。
                        if (handler instanceof ResourceHttpRequestHandler
                                && (path.equals("/internal/ai/v1/runs") || path.startsWith("/internal/ai/v1/runs/")
                                || path.equals("/p04") || path.startsWith("/p04/"))) {
                            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                            return false;
                        }
                        return super.preHandle(request, response, handler);
                    }
                })
                // 拦截所有路径
                .addPathPatterns("/**")
                // 排除认证相关路径和错误页面
                .excludePathPatterns("/auth/**", "/error")
                .order(ORDER_LOGIN);

        // 注册用户上下文拦截器
        registry.addInterceptor(userContextInterceptor)
                // 拦截所有路径
                .addPathPatterns("/**")
                // 排除认证相关路径和错误页面
                .excludePathPatterns("/auth/**", "/error")
                .order(ORDER_USER_CONTEXT);
    }
}
