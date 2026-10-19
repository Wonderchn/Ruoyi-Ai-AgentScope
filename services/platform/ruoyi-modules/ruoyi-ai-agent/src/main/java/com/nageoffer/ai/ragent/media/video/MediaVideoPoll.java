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

package com.nageoffer.ai.ragent.media.video;

import java.util.Map;

/**
 * provider 视频轮询结果（状态已归一为大写）。
 *
 * <p>{@code status} 取值：{@code PENDING/RUNNING/SUCCEEDED/FAILED/CANCELED/UNKNOWN}。
 * 未知状态按 UNKNOWN 处理（不自动重发付费请求）。
 */
public record MediaVideoPoll(String status, String videoUrl, String errorCode, String message,
                             String providerRequestId, Map<String, Object> usage) {
}
