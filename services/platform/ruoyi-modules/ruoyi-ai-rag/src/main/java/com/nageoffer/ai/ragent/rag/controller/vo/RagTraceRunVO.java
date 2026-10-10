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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * RAG Trace 运行记录
 */
@Data
@Builder
public class RagTraceRunVO {

    private String traceId;

    private String traceName;

    private String entryMethod;

    private String conversationId;

    private String taskId;

    private String userId;

    private String username;

    private String status;

    private String errorMessage;

    private Long durationMs;

    private Long ttftMs;

    private String question;

    private Date startTime;

    private Date endTime;

    /**
     * 提示词 token 用量；来源为 {@code ai_rag_trace_run.extra_data} 的 {@code prompt_tokens} 键。
     *
     * <p><b>三态语义（F18 op1 契约）</b>：缺键 / JSON {@code null} / 非数字 / 坏 JSON → {@code null}
     * （未知，不得伪 0）；显式写入 {@code 0} → {@code 0}。对账状态（UNKNOWN / 未对账）不在本面表达，
     * 归 F23 用量账本（B 面）。
     */
    private Integer promptTokens;

    /**
     * 补全 token 用量；来源为 {@code ai_rag_trace_run.extra_data} 的 {@code completion_tokens} 键。
     * 三态语义同 {@link #promptTokens}。
     */
    private Integer completionTokens;

    /**
     * 总 token 用量；来源为 {@code ai_rag_trace_run.extra_data} 的 {@code total_tokens} 键。
     * 缺键即 {@code null}，不由 prompt+completion 推算。三态语义同 {@link #promptTokens}。
     */
    private Integer totalTokens;
}
