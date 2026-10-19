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
 * MCP 管理面具名错误：HTTP status 与 {@code errorCode} 一并固定，控制器逐条映射，
 * 不落到全局解析器的 500 兜底。
 */
public enum McpAdminError {

    /** 跨租户 id 与不存在同形：404，不泄露存在性。 */
    NOT_FOUND(404, "MCP_NOT_FOUND", "MCP 资源不存在"),

    /** 同一市场工具重复加载（brief §2.5：409 语义、零部分写）。 */
    TOOL_ALREADY_LOADED(409, "MCP_TOOL_ALREADY_LOADED", "工具已加载到本地"),

    /** 状态只允许 ENABLED / DISABLED。 */
    INVALID_STATUS(400, "MCP_INVALID_STATUS", "状态只能是 ENABLED 或 DISABLED"),

    /** 参数形状不合法。 */
    INVALID_ARGUMENT(400, "MCP_INVALID_ARGUMENT", "请求参数无效"),

    /** headers 里的 {@code env:NAME} 引用在运行环境不可解析。 */
    CREDENTIAL_UNAVAILABLE(400, "MCP_CREDENTIAL_UNAVAILABLE", "凭据引用不可解析");

    private final int httpStatus;
    private final String errorCode;
    private final String defaultMessage;

    McpAdminError(int httpStatus, String errorCode, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
