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

package com.nageoffer.ai.ragent.media.image;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 媒体 provider 的最小 HTTP 传输口（测试可注入假实现，生产用 JDK HttpClient）。
 *
 * <p>实现必须：不跟随重定向、限时、限体长。错误信息不得包含请求头（尤其是
 * {@code Authorization}）或响应正文全文。
 */
public interface WanxHttpTransport {

    /** 一次请求的受限响应。 */
    record Reply(int status, byte[] body, String contentType) {

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * 发送请求。
     *
     * @param method    HTTP 方法
     * @param url       绝对 URL
     * @param headers   请求头（含认证；不得记录）
     * @param body      请求体，GET 为 null
     * @param maxBytes  响应体上限；超出即 IOException
     */
    Reply send(String method, String url, Map<String, String> headers, byte[] body, long maxBytes)
            throws IOException;
}
