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

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("dev")
class SaTokenConfigTest {

    @Test
    void missingExperimentRoutesReturn404BeforeLoginForAllContexts() throws Exception {
        HandlerInterceptor login = loginInterceptor();
        for (String context : List.of("", "/ragent")) {
            for (String path : List.of("/internal/ai/v1/runs", "/internal/ai/v1/runs/example",
                    "/p04", "/p04/health", "/p04/control/reset")) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", context + path);
                request.setContextPath(context);
                MockHttpServletResponse response = new MockHttpServletResponse();
                try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                    assertFalse(login.preHandle(request, response, new ResourceHttpRequestHandler()));
                    assertEquals(404, response.getStatus());
                    stp.verifyNoInteractions();
                }
            }
        }
    }

    @Test
    void mappedExperimentHandlerStillRequiresLogin() throws Exception {
        HandlerMethod handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("run"));
        assertLoginChecked("/internal/ai/v1/runs", handler);
        assertLoginChecked("/p04/health", handler);
    }

    @Test
    void unrelatedFallbackPathsStillRequireLogin() throws Exception {
        for (String path : List.of("/chat", "/p04-other", "/internal/ai/v1/runs-other")) {
            assertLoginChecked(path, new ResourceHttpRequestHandler());
        }
    }

    private void assertLoginChecked(String path, Object handler) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityException denied = new SecurityException("login required");
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::checkLogin).thenThrow(denied);
            assertSame(denied, assertThrows(SecurityException.class,
                    () -> loginInterceptor().preHandle(request, response, handler)));
            stp.verify(StpUtil::checkLogin);
            assertEquals(200, response.getStatus());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private HandlerInterceptor loginInterceptor() {
        Registry registry = new Registry();
        new SaTokenConfig(new UserContextInterceptor(null)).addInterceptors(registry);
        return ((MappedInterceptor) registry.interceptors().get(0)).getInterceptor();
    }

    private static final class Registry extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }

    public static final class Endpoint {
        public void run() {
        }
    }
}