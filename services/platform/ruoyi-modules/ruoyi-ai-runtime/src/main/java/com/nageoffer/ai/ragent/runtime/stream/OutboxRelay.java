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

package com.nageoffer.ai.ragent.runtime.stream;

import com.nageoffer.ai.ragent.runtime.P2FaultInjector;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.dao.OutboxDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * outbox relay：认领持久投递任务 → 逐传输投递 → 标记 PUBLISHED。
 *
 * <p>PG 首期方案：投递是 at-least-once；事件事实以 {@code ai_run_event}
 * 为准，重复/乱序由订阅端按 {@code (runId, seq)}/eventId 去重；扫描补偿（SSE 轮询）
 * 保证通知丢失也不影响可见状态。失败按退避重试，超限进 DEAD_LETTER 保留人工重放。
 *
 * <p><b>为什么投递要经 {@link OutboxTransport} 而不是直接调 {@link NotificationBus}（C12.2）。</b>
 * 引入 MQ 之后"投递"就不再只有一个落点：进程内总线负责本节点 SSE 唤醒，
 * MQ 负责跨实例。如果 relay 直接调总线、再由某个旁路偷偷发 MQ，就会出现
 * "总线成功 → 标记 PUBLISHED → MQ 那边永远没人发"的静默丢失。改成 seam 之后语义收敛成
 * 一句话：<b>所有已注册传输都必须成功，这一条才算投递成功</b>；任一传输抛出即走既有
 * 退避/DEAD_LETTER，outbox 行保持 PENDING 且积压可查（C2.3/C12.4）。
 *
 * <p>{@code NotificationBus} 通过 {@link NotificationBusTransport} 作为一个普通传输参与，
 * <b>不因引入 MQ 而删除</b>（C12.2：MQ 是附加不是替代）。
 * 没有任何 MQ 实现注册时（默认，C12.5-5），行为与引入本 seam 之前逐字一致。
 */
@Component
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@ConditionalOnProperty(name = "p2.outbox.relay-enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxDao dao;
    private final List<OutboxTransport> transports;
    private final P2RuntimeProperties properties;
    private final ObjectProvider<P2FaultInjector> faultInjector;
    private final String relayId = "relay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    /**
     * @param transports 全部已注册传输（按注入顺序投递）。用 {@link List} 而不是单个 bean：
     *                   阶段 1 是"进程内总线"（{@link NotificationBusTransport}），
     *                   阶段 2 追加 RocketMQ（T3）；两者必须**同时**投递，不是二选一。
     */
    public OutboxRelay(OutboxDao dao, List<OutboxTransport> transports, P2RuntimeProperties properties,
                       ObjectProvider<P2FaultInjector> faultInjector) {
        this.dao = dao;
        this.transports = transports == null ? List.of() : List.copyOf(transports);
        this.properties = properties;
        this.faultInjector = faultInjector;
    }

    @Scheduled(fixedDelayString = "${p2.outbox.poll-interval-ms:1000}")
    public void relayOnce() {
        try {
            List<OutboxDao.OutboxRow> rows = dao.claim(relayId,
                    properties.getOutbox().getLockSeconds(), properties.getOutbox().getBatch());
            for (OutboxDao.OutboxRow row : rows) {
                deliver(row);
            }
        } catch (RuntimeException e) {
            log.warn("outbox relay tick failed: {}", e.getClass().getSimpleName());
        }
    }

    private void deliver(OutboxDao.OutboxRow row) {
        P2FaultInjector faults = faultInjector.getIfAvailable();
        try {
            if (faults != null) {
                faults.checkpoint(P2FaultInjector.OUTBOX_AFTER_CLAIM);
            }
            OutboxMessage message = new OutboxMessage(row.tenantId(), row.eventId(), row.runId(),
                    row.eventType(), row.seq(), row.payload(), row.operationKey());
            for (OutboxTransport transport : transports) {
                transport.send(message);
            }
            if (faults != null) {
                faults.checkpoint(P2FaultInjector.OUTBOX_AFTER_PUBLISH);
                faults.checkpoint(P2FaultInjector.OUTBOX_BEFORE_MARK);
            }
            dao.markPublished(row.tenantId(), row.eventId());
        } catch (RuntimeException e) {
            // 投递失败：outbox 行保持 PENDING（不丢），按退避重试，超限进 DEAD_LETTER 待人工重放。
            // 这里刻意把失败类型只写进 last_error：不向调用方返回"假执行成功"。
            boolean dead = row.attemptCount() >= properties.getOutbox().getMaxAttempts();
            int backoff = Math.min(60, properties.getOutbox().getBackoffSeconds()
                    * Math.max(1, row.attemptCount()));
            dao.markRetry(row.tenantId(), row.eventId(), e.getClass().getSimpleName(), backoff, dead);
        }
    }
}
