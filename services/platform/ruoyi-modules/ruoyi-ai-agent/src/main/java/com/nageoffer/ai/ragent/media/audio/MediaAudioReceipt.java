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

package com.nageoffer.ai.ragent.media.audio;

import java.util.Map;

/**
 * 同步语音合成的即时回执（qwen-tts HTTP 无轮询端点）。
 *
 * <p>{@code audioUrl} 与 {@code audioBase64} 至少一个非空；两者都为空视为结果未知
 * （可能已计费，不自动重发）。{@code providerRequestId} 是 provider 的 request_id，
 * 可为空（仅影响对账定位，不影响结果私有化）。
 */
public record MediaAudioReceipt(String providerRequestId, String audioUrl, String audioBase64,
                                String declaredFormat, Map<String, Object> usage) {
}
