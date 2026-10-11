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

package com.nageoffer.ai.ragent.rag.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消息点赞/点踩反馈事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageFeedbackEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 租户ID（ENV-BATCH-5b 修，2026-10-11）：消费者线程没有登录主体，
     * MyBatis 租户行拦截器对业务表（ai_message 等）fail-closed——缺本字段时
     * 消费者必须**响亮拒绝**（不落默认租户、不静默跳过）；生产者受理线程从
     * 执行主体（退路：旧 UserContext 租户）显式带上。
     */
    private String tenantId;

    /**
     * 消息ID
     */
    private String messageId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 反馈值：1=点赞，-1=点踩
     */
    private Integer vote;

    /**
     * 反馈原因（可选）
     */
    private String reason;

    /**
     * 补充说明（可选）
     */
    private String comment;

    /**
     * 是否为取消反馈事件；旧消息缺少该字段时默认为 false
     */
    private boolean cancelled;

    /**
     * 用户提交时间戳（毫秒），用于多节点消费时保证最终一致性
     */
    private long submitTime;
}
