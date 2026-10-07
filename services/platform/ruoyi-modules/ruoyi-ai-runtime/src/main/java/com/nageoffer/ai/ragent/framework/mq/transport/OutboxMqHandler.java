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
 * 一条已通过授权与去重的 outbox 消息要产生的**副作用**。
 *
 * <p><b>实现者必须知道自己在 C2.1 的哪一侧。</b>传输层的副作用应当是**通知类**的
 * （本仓库的默认实现只唤醒进程内可见性通道）。任何"写 run 状态 / 写执行账本 / 判终态"
 * 的实现都越界了：那些事实归 T2 的 {@code ai_run} / {@code ai_run_event} / Worker，
 * 传输层不得新建第二套。
 *
 * <p>抛出 = 客观失败：{@link OutboxMqIngestService} 会把去重账本置回可再认领并抛出，
 * 由 broker 重投。**不要**把"永久不可恢复"做成抛出之外的形态（例如返回 false），
 * 那会让重试语义与事实脱节。
 */
@FunctionalInterface
public interface OutboxMqHandler {

    /** 执行副作用；抛出即视为客观失败（可重试）。 */
    void handle(OutboxMqEnvelope envelope);
}
