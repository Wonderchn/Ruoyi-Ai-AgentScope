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

package org.ruoyi.aiintegration.web;

import cn.dev33.satoken.exception.NotLoginException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合并门修复（CI native 矩阵 N1-unauthenticated-401 实测回归）：本 advice 以最高优先级
 * 覆盖 {@code org.ruoyi.aiintegration} 包，未登录若不被显式映射，就会落进兜底分支成 500。
 *
 * <p>契约：匿名请求 → HTTP 401 + {@code body.code == 401} + {@code data.errorCode == AUTH_REQUIRED}
 * （P04 协议口径：HTTP 状态 == body.code，符号码在 data.errorCode），且不得产生受理行
 * （受理行由真实 DB 断言在 native 矩阵覆盖，本单测只钉映射）。
 *
 * <p>少了本判据：删掉 p04 的 NotLoginException 分支不会在任何测试里变红（回归再次静默）。
 */
@Tag("dev")
class P04ExceptionHandlerNotLoginTest {

    private final P04ExceptionHandler handler = new P04ExceptionHandler();

    @Test
    void notLoginMapsTo401AuthRequired() {
        NotLoginException ex = NotLoginException.newInstance("login",
                NotLoginException.NOT_TOKEN, NotLoginException.NOT_TOKEN_MESSAGE, null);

        var response = handler.handleNotLogin(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(401);
        assertThat(String.valueOf(response.getBody().data().get("errorCode"))).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void unexpectedFailureStillMapsToInternalError() {
        var response = handler.handleUnexpected(new IllegalStateException("boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(String.valueOf(response.getBody().data().get("errorCode"))).isEqualTo("INTERNAL_ERROR");
    }
}
