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

package com.nageoffer.ai.ragent.media.task;

import java.util.Locale;

/**
 * 媒体任务状态机（服务端唯一权威）。
 *
 * <pre>
 * QUEUED ──提交成功──▶ RUNNING ──成功──▶ SUCCEEDED
 *    │                   │
 *    ├──明确拒绝────────▶ FAILED
 *    └──结果未知────────▶ UNKNOWN
 *
 * RUNNING ──明确失败──▶ FAILED
 * RUNNING ──查询未知──▶ UNKNOWN
 * </pre>
 *
 * <p>UNKNOWN 是"不自动重发付费请求"的终态：只能由用户显式重试产生新的 attempt。
 * FAILED 与 UNKNOWN 都允许显式重试；RUNNING/QUEUED/SUCCEEDED 不允许。
 */
public enum MediaTaskStatus {

    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    public static MediaTaskStatus fromDb(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** 轮询/查询不再推进的状态。UNKNOWN 与 FAILED 只能通过显式重试离开。 */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == UNKNOWN;
    }

    /** 允许显式重试的状态。 */
    public boolean retryable() {
        return this == FAILED || this == UNKNOWN;
    }

    /** 面向 workbench 的稳定小写状态。 */
    public String wireStatus() {
        return switch (this) {
            case QUEUED -> "pending";
            case RUNNING -> "processing";
            case SUCCEEDED -> "completed";
            case FAILED -> "failed";
            case UNKNOWN -> "unknown";
        };
    }
}
