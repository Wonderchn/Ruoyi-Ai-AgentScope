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
 * 提供方<b>明确拒绝</b>：4xx 等确定性响应，可以断言没有创建付费任务。
 *
 * <p>失败原因只保留脱敏后有界的 provider 文案，绝不包含请求头或凭据。
 */
public class MediaProviderRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public MediaProviderRejectedException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
