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

/**
 * F19:0 语音合成请求（provider 无关的窄参数）。
 *
 * <p>{@code format} 是控制面请求参数与结果 MIME 提示：qwen-tts HTTP 协议没有
 * format 字段，当前返回 wav；{@code speed} 同样记录但不下发（协议无此参数）。
 */
public record MediaAudioRequest(String model, String text, String voice, String format,
                                Double speed, String instructions) {
}
