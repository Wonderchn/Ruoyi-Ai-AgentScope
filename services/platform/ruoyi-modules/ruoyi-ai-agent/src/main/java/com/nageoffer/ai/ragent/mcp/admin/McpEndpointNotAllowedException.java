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
 * 目标不在 MCP 受控端点 allow-list 内（具名 403）。
 *
 * <p>抛出点在任何网络调用之前：未批准目标必须<b>零网络</b>，而不是"连了再拒"。
 */
public class McpEndpointNotAllowedException extends RuntimeException {

    public static final String ERROR_CODE = "MCP_ENDPOINT_NOT_ALLOWED";

    public McpEndpointNotAllowedException() {
        super("MCP endpoint is not in the approved allow-list");
    }

    public McpEndpointNotAllowedException(String reason) {
        super("MCP endpoint is not in the approved allow-list: " + reason);
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
