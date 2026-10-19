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

package com.nageoffer.ai.ragent.mcp.admin;

/**
 * MCP 管理面具名业务异常（404/409/400），由控制器 {@code @ExceptionHandler} 映射成
 * {@code HTTP status == body.code} 的整数信封。
 */
public class McpAdminException extends RuntimeException {

    private final McpAdminError error;

    public McpAdminException(McpAdminError error) {
        super(error.defaultMessage());
        this.error = error;
    }

    public McpAdminException(McpAdminError error, String detail) {
        super(error.defaultMessage() + ": " + detail);
        this.error = error;
    }

    public McpAdminError error() {
        return error;
    }

    public int httpStatus() {
        return error.httpStatus();
    }

    public String errorCode() {
        return error.errorCode();
    }
}
