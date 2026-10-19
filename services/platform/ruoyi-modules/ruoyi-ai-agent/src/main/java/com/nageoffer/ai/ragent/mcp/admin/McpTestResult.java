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
 * 连接测试结果（四态：可达 / 不可达 / 鉴权失败 / 未知 id）。
 *
 * <p>不可达消息固定以「连接失败」开头，可带异常类名，但<b>不包含内网地址</b>；
 * 鉴权失败固定为「鉴权失败」；未知 id 固定为「工具不存在」。
 */
public record McpTestResult(boolean success, Integer toolCount, String message) {
}
