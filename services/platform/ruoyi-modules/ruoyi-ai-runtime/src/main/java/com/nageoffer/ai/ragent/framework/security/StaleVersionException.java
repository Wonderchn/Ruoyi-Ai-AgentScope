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

package com.nageoffer.ai.ragent.framework.security;

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 版本过期（pv/av 与本次请求不再一致）。
 *
 * <p>与"权限不足"区分开：过期是可恢复的（重新取当前版本再来），
 * 权限不足不是。HTTP 上对应 409 语义；不实现 {@code IErrorCode} 以免改动共享枚举，
 * 由 canonical 异常处理器按类型映射。
 */
public class StaleVersionException extends ClientException {

    public StaleVersionException(String message) {
        super(message, BaseErrorCode.CLIENT_ERROR);
    }
}
