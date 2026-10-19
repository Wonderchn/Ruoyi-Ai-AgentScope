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

package com.nageoffer.ai.ragent.shortdrama.service;

import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * 短剧输入校验与视图格式化的小工具。
 *
 * <p>不做 Bean Validation：内嵌直调链路里 {@code MethodArgumentNotValidException}
 * 不是 AI 信封异常，会被网关收敛成 503。这里显式抛
 * {@link P04AiException#BAD_REQUEST}，保证失败响应也是 {@code HTTP status == body.code}
 * 的整数信封。
 */
public final class DramaInput {

    public static final int MAX_PROJECT_NAME = 200;
    public static final int MAX_DESCRIPTION = 5000;
    public static final int MAX_IDEA = 12000;
    public static final int MAX_SCRIPT_NAME = 200;
    public static final int MAX_SCRIPT_TEXT = 200000;
    public static final int MAX_OUTLINE_TEXT = 20000;
    public static final int MAX_TONE = 100;
    public static final int MAX_ART_STYLE = 32;
    public static final int MAX_IDEMPOTENCY_KEY = 128;

    private static final Set<String> STATUSES = Set.of("draft", "active", "archived");
    private static final Set<String> ASPECT_RATIOS = Set.of("9:16", "16:9", "1:1");

    private DramaInput() {
    }

    public static String requireText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                    field + " 超过 " + maxLength + " 字符");
        }
        return normalized;
    }

    public static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                    field + " 超过 " + maxLength + " 字符");
        }
        return normalized;
    }

    public static String statusOr(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                    "status 只允许 draft/active/archived");
        }
        return normalized;
    }

    public static String aspectRatioOr(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.strip();
        if (!ASPECT_RATIOS.contains(normalized)) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST,
                    "aspectRatio 只允许 9:16/16:9/1:1");
        }
        return normalized;
    }

    public static String artStyleOr(String value, String fallback) {
        String normalized = optionalText(value, "artStyle", MAX_ART_STYLE);
        return normalized == null ? fallback : normalized;
    }

    public static String idempotencyKey(String value) {
        String normalized = optionalText(value, "idempotencyKey", MAX_IDEMPOTENCY_KEY);
        return normalized;
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static String isoTime(Timestamp timestamp) {
        return timestamp == null ? null : DateTimeFormatter.ISO_INSTANT.format(timestamp.toInstant());
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
