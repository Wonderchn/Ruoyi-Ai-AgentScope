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

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 媒体任务对外视图（workbench 契约）。
 *
 * <p>{@code status} 是稳定小写值：{@code pending/processing/completed/failed/unknown}。
 * 不包含 provider 裸 URL、API key、provider 原始响应。
 */
public record MediaTaskView(String id,
                            String type,
                            String provider,
                            String status,
                            String model,
                            String prompt,
                            String mimeType,
                            String message,
                            String errorCode,
                            int attempt,
                            String previousTaskId,
                            Long sizeBytes,
                            String createdAt,
                            String updatedAt) {

    public static MediaTaskView of(MediaTaskRecord record) {
        return of(record, null);
    }

    public static MediaTaskView of(MediaTaskRecord record, String transientMessage) {
        return new MediaTaskView(
                record.taskId(),
                record.mediaType(),
                record.provider(),
                record.status().wireStatus(),
                record.model(),
                record.prompt(),
                record.resultMime(),
                transientMessage != null ? transientMessage : record.errorMessage(),
                record.errorCode(),
                record.attempt(),
                record.previousTaskId(),
                record.resultSize(),
                format(record.createdAt()),
                format(record.updatedAt()));
    }

    private static String format(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    /** 列表页。 */
    public record Page(List<MediaTaskView> items) {
    }
}
