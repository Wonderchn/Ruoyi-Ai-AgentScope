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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AI 侧请求追踪标识：解析/生成 {@code X-Request-Id}，写入响应头与 MDC，请求结束后清理线程上下文。
 *
 * <p>实测事实（Spec §8.1 的 C2）：AI 的 {@code Result.requestId} 只有字段声明、全仓库没有赋值，
 * 因此这不是"复用"而是**新增**最小管道。客户端值必须匹配白名单字符集，否则替换为服务端生成值，
 * 以防日志注入。
 */
public class AiRequestIdFilter extends OncePerRequestFilter {

    /** 请求/响应头名称。 */
    public static final String HEADER = "X-Request-Id";

    private static final String MDC_KEY = "requestId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static String currentOrEmpty() {
        String value = CURRENT.get();
        return value == null ? "" : value;
    }

    public static String resolve(String incoming) {
        if (incoming != null && VALID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        CURRENT.set(requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            CURRENT.remove();
        }
    }
}
