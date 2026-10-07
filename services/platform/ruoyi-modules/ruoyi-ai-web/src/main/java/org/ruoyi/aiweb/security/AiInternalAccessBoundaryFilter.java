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

package org.ruoyi.aiweb.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AI 内部路径对外边界（E3/C3 在内嵌装配下的重新表达）。
 *
 * <p>原独立应用里 {@code SaasEntryFilter} 承担"未授权路径不暴露"：生产只放行
 * 内部 API 的授权入口。内嵌后 AI 侧 {@code /internal/ai/v1/**} 控制器注册在
 * platform 同一容器内，这些路径对外<b>一律不存在</b>——唯一公开面是
 * {@code /api/ai/v1/**}（网关白名单），内部可达只经 {@code LocalAiGatewayClient}
 * 的 FORWARD 分派。
 *
 * <p>行为：
 * <ul>
 *   <li>仅注册于 {@code REQUEST} 分派：外部直接请求 /internal/ai/v1/** 一律 404，
 *       关闭响应与 {@code SaasEntryFilter} 同形（HTTP status == body.code == 404）；</li>
 *   <li>FORWARD/ASYNC/ERROR 分派不经过本过滤器：内部转送与异步收尾不受影响；</li>
 *   <li>不解析、不记录 body/header 中的任何身份字段。</li>
 * </ul>
 *
 * <p>"不存在"在 handler 之前判定：即使内部控制器已装配，外部请求也不触碰
 * 任何业务 bean。
 */
public class AiInternalAccessBoundaryFilter implements Filter {

    /** 与 SaasEntryFilter 关闭形状一致的自包含 404 体。 */
    public static final String CLOSED_BODY =
            "{\"code\":404,\"msg\":\"资源不存在或无权访问\","
                    + "\"data\":{\"errorCode\":\"RESOURCE_NOT_FOUND_OR_FORBIDDEN\"}}";

    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    @Override
    public void init(FilterConfig filterConfig) {
        // 无状态过滤器
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(response instanceof jakarta.servlet.http.HttpServletResponse servletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        servletResponse.setStatus(404);
        servletResponse.setContentType(CONTENT_TYPE_JSON);
        servletResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        servletResponse.getWriter().write(CLOSED_BODY);
    }

    /** 只服务 REQUEST 分派（与注册方约定一致，双保险断言）。 */
    public static boolean blocks(DispatcherType type) {
        return type == DispatcherType.REQUEST;
    }
}
