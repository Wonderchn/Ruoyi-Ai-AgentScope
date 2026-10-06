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
 * outbox 投递到 broker 失败。
 *
 * <p>存在的唯一理由是**让失败分类可辨**：{@code OutboxRelay} 会把异常类名写进
 * {@code outbox_event.last_error}（{@code markRetry}）。若这里直接抛
 * {@code RuntimeException("...")}，运维在积压表里只能看到一堆同名的泛化异常，
 * 分不清"broker 不可达"、"返回非 SEND_OK"、"载荷序列化失败"。
 * 类名本身就是可查询的运维信号，所以值得一个专门的类型。
 *
 * <p><b>它一定是 RuntimeException</b>：{@code OutboxTransport#send} 的契约里
 * "抛出 = 投递失败"，而 relay 是按 {@code RuntimeException} 捕获的。
 * 若抛受检异常，编译期就得在 relay 侧改签名——那是在动 T2 的契约，不是本组该做的。
 */
public class OutboxTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OutboxTransportException(String message) {
        super(message);
    }

    public OutboxTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
