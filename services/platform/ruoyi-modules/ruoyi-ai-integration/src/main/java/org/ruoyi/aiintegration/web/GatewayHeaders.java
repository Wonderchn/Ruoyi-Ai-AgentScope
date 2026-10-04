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

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 网关转发头净化：剥内部身份头、逐跳头、cookie 与浏览器凭证（普通/流式共用）。 */
public final class GatewayHeaders {

    private static final Set<String> INTERNAL_IDENTITY_HEADERS = Set.of(
            "x-tenant", "x-tenant-id", "x-user", "x-user-id", "x-member-id",
            "x-membership-id", "x-policy-version", "x-acl-version", "x-principal");

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "authorization", "host", "content-length", "connection", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "cookie", "expect");

    private GatewayHeaders() {
    }

    public static Map<String, String> sanitize(HttpServletRequest request) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        var headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return sanitized;
        }
        List<String> names = new ArrayList<>();
        headerNames.asIterator().forEachRemaining(names::add);
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-ai-delivery-")) {
                continue;
            }
            if (lower.equals("x-p04-service-credential") || lower.equals("x-service-credential")) {
                continue;
            }
            if (INTERNAL_IDENTITY_HEADERS.contains(lower) || HOP_BY_HOP_HEADERS.contains(lower)) {
                continue;
            }
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                sanitized.put(name, value);
            }
        }
        if (request.getContentType() != null && !request.getContentType().isBlank()) {
            sanitized.put("Content-Type", request.getContentType());
        }
        return sanitized;
    }
}
