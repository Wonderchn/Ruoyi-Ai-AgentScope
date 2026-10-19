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

/**
 * F19:2 视频生成提交请求（provider 无关的窄参数）。
 *
 * <p>{@code quality} 记录并参与重试，但 DashScope 文生视频协议没有 quality 字段，
 * 不下发未定义参数。
 */
public record MediaVideoRequest(String model, String prompt, String size, Integer seconds, String quality) {
}
