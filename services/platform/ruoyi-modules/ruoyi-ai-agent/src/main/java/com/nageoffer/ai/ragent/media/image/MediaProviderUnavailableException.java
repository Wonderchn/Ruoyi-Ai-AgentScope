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

/**
 * 提供方结果<b>未知</b>（超时/5xx/连接失败/成功响应缺任务 ID）。
 *
 * <p>调用方必须把它落成 UNKNOWN 任务：不自动重发付费请求、不退还预算预占。
 */
public class MediaProviderUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MediaProviderUnavailableException(String message) {
        super(message);
    }

    public MediaProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
