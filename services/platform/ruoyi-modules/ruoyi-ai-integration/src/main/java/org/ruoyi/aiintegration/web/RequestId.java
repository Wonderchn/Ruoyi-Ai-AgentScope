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

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 请求追踪标识的解析、校验与线程内传递。
 *
 * <p>实测事实（Spec §8.1 的 C2）：platform 侧原本没有 {@code X-Request-Id} 机制，
 * 只有一个供 RAG 链路使用的 {@code TraceContext} ThreadLocal。本实验按需新增这一最小管道，
 * 不改动 RAG trace 子系统。
 *
 * <p>客户端传入的值必须匹配 {@link #VALID}，否则<b>替换为服务端生成值</b>，
 * 以防日志注入。
 */
public final class RequestId {

    /** 请求/响应头名称。 */
    public static final String HEADER = "X-Request-Id";

    /** 允许的字符与长度：1–64 个 {@code [A-Za-z0-9._-]}。 */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestId() {
    }

    /**
     * @param incoming 客户端传入的原始值，可为 {@code null}
     * @return 合法则原样返回，否则返回新生成的值
     */
    public static String resolve(String incoming) {
        if (incoming != null && VALID.matcher(incoming).matches()) {
            return incoming;
        }
        return generate();
    }

    public static String generate() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public static void set(String requestId) {
        CURRENT.set(requestId);
    }

    public static String current() {
        return CURRENT.get();
    }

    public static String currentOrEmpty() {
        String v = CURRENT.get();
        return v == null ? "" : v;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 失败响应的 data 结构；符号错误码放在 {@code errorCode}。
     */
    public static Map<String, Object> errorDetail(P04ErrorCode errorCode) {
        return Map.of("errorCode", errorCode.name());
    }
}
