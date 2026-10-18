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

package com.nageoffer.ai.ragent.rag.core.graph;

/**
 * 知识图谱操作的符号错误码。
 *
 * <p>HTTP 状态与 {@code data.errorCode} 一起构成对外契约：前端只看 HTTP status 与 envelope
 * {@code msg}（共享客户端不保留 {@code data.errorCode}），所以 {@link #message()} 必须是
 * 可读中文原因，不能交给前端猜。
 *
 * <p>不改 {@code P04AiErrorCode}（P0.4 冻结枚举）：本枚举只服务 F08 图谱面，
 * 由 {@code AiInternalExceptionResolver} 单独分支映射。
 */
public enum GraphErrorCode {

    /** 入参不合法（二选一缺失、超上限等）。 */
    GRAPH_BAD_REQUEST(400, "图谱请求参数不合法"),
    /** 跨租户 / 不存在 / 无权访问统一 404，不泄露存在性。 */
    RESOURCE_NOT_FOUND_OR_FORBIDDEN(404, "资源不存在或无权访问"),
    /** 文档尚未完成 LightRAG 抽取（pending/processing/首次写入后）。 */
    GRAPH_DOCUMENT_NOT_READY(409, "文档图谱尚未就绪，请稍后重试"),
    /** 文档没有可用的分块正文。 */
    GRAPH_DOCUMENT_EMPTY(409, "文档没有可用于图谱的正文"),
    /** LightRAG 抽取失败。 */
    GRAPH_DOCUMENT_FAILED(409, "文档图谱抽取失败，请重新解析文档"),
    /** /graphs 返回截断：拒绝用不完整图覆盖已有投影。 */
    GRAPH_PROJECTION_TRUNCATED(409, "图谱规模超过单次上限，已拒绝写入不完整投影"),
    /** 单个 KB 一次重建的文档数超上限。 */
    GRAPH_DOCUMENT_LIMIT_EXCEEDED(400, "本次重建的文档数超过上限，请按文档重试"),
    /** 单次重建拼接正文超上限。 */
    GRAPH_TEXT_TOO_LARGE(400, "本次重建的正文长度超过上限，请缩小范围"),
    /** LightRAG 不可达/超时/非 2xx。 */
    GRAPH_BACKEND_UNAVAILABLE(503, "知识图谱后端不可用");

    private final int httpStatus;
    private final String message;

    GraphErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
