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

package com.nageoffer.ai.ragent.framework.mq.transport;

/**
 * 消费前重新授权时**授权事实暂时不可得**。
 *
 * <p>与"授权拒绝"是两件事，因此是两个类型：拒绝是**结论**（记录后不再重试），
 * 不可得是**状态**（抛出让 broker 重投，消息不丢）。混用会让一次数据库抖动
 * 变成一批事件的永久丢弃。
 *
 * <p>类名会进 RocketMQ 的重试日志与 {@code ai_mq_consume_dedup.last_error}
 * （若已认领），所以值得一个能被识别的名字。
 */
public class OutboxMqReauthorizationUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OutboxMqReauthorizationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public OutboxMqReauthorizationUnavailableException(String message) {
        super(message);
    }
}
