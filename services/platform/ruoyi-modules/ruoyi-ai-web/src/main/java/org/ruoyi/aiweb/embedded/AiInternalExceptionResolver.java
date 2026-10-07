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

package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * AI 侧异常解析（E3/C9）：把 AI 冻结异常类型映射为 AI 信封
 * （HTTP status == body.code，符号码在 {@code data.errorCode}），供内嵌直调使用。
 *
 * <p>映射与 AI 侧控制器本地 {@code @ExceptionHandler} 逐条一致：
 * <ul>
 *   <li>{@link P04AiException} → 其符号码状态；</li>
 *   <li>{@link StaleVersionException} → 409 {@code POLICY_VERSION_STALE}；</li>
 *   <li>{@link ClientException} → 403 {@code TENANT_CONTEXT_MISSING}（不区分"没带"与"不完整"）；</li>
 *   <li>{@link ServiceException} → 503 {@code AUTHORIZATION_UNAVAILABLE}（不放行）。</li>
 * </ul>
 * 其余异常返回 null，交由 Spring MVC 既有解析链（保持原行为，不吞异常）。
 *
 * <p>排序为最高优先级：内嵌直调只可能承载 AI 路由，platform 的全局
 * {@code @RestControllerAdvice} 不得抢先改变 AI 信封形状。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiInternalExceptionResolver implements HandlerExceptionResolver, Ordered {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        ApiEnvelope<Map<String, Object>> envelope = envelopeOf(ex);
        if (envelope == null) {
            return null;
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(envelope);
            response.setStatus(envelope.code());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.getOutputStream().flush();
            return new ModelAndView();
        } catch (Exception writeFailure) {
            return null;
        }
    }

    private static ApiEnvelope<Map<String, Object>> envelopeOf(Throwable ex) {
        if (ex instanceof com.nageoffer.ai.ragent.runtime.RunApiException run) {
            return ApiEnvelope.error(run.errorCode().status().value(), run.errorCode().message(), run.errorCode().name());
        }
        if (ex instanceof com.nageoffer.ai.ragent.runtime.config.ConfigAuthorityUnavailable) {
            return ApiEnvelope.error(503, "运行配置不可用", "CONFIG_AUTHORITY_UNAVAILABLE");
        }
        if (ex instanceof org.springframework.dao.DataAccessException
                || ex instanceof org.springframework.transaction.TransactionException) {
            return ApiEnvelope.error(503, "依赖不可用", "DEPENDENCY_UNAVAILABLE");
        }
        if (ex instanceof P04AiException p04) {
            P04AiErrorCode code = p04.errorCode();
            return ApiEnvelope.error(code.httpStatus(), p04.getMessage(), code.name());
        }
        if (ex instanceof StaleVersionException stale) {
            return ApiEnvelope.error(409, stale.getMessage(), P04AiErrorCode.POLICY_VERSION_STALE.name());
        }
        if (ex instanceof ClientException client) {
            int status = P04AiErrorCode.TENANT_CONTEXT_MISSING.httpStatus();
            return ApiEnvelope.error(status, client.getMessage(), P04AiErrorCode.TENANT_CONTEXT_MISSING.name());
        }
        if (ex instanceof ServiceException service) {
            int status = P04AiErrorCode.AUTHORIZATION_UNAVAILABLE.httpStatus();
            return ApiEnvelope.error(status, service.getMessage(), P04AiErrorCode.AUTHORIZATION_UNAVAILABLE.name());
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
