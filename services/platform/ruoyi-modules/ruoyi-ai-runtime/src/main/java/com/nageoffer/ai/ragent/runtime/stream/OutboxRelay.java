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
 * outbox relay：认领持久投递任务 → 通知订阅端 → 标记 PUBLISHED。
 *
 * <p>PG 首期方案（无 MQ）：投递是 at-least-once；事件事实以 {@code ai_run_event}
 * 为准，重复/乱序由订阅端按 {@code (runId, seq)}/eventId 去重；扫描补偿（SSE 轮询）
 * 保证通知丢失也不影响可见状态。失败按退避重试，超限进 DEAD_LETTER 保留人工重放。
 */
@Component
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
@ConditionalOnProperty(name = "p2.outbox.relay-enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxDao dao;
    private final NotificationBus bus;
    private final P2RuntimeProperties properties;
    private final ObjectProvider<P2FaultInjector> faultInjector;
    private final String relayId = "relay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    public OutboxRelay(OutboxDao dao, NotificationBus bus, P2RuntimeProperties properties,
                       ObjectProvider<P2FaultInjector> faultInjector) {
        this.dao = dao;
        this.bus = bus;
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
            bus.publish(row.tenantId(), row.runId(), row.seq() == null ? 0 : row.seq(), row.eventId());
            if (faults != null) {
                faults.checkpoint(P2FaultInjector.OUTBOX_AFTER_PUBLISH);
                faults.checkpoint(P2FaultInjector.OUTBOX_BEFORE_MARK);
            }
            dao.markPublished(row.tenantId(), row.eventId());
        } catch (RuntimeException e) {
            boolean dead = row.attemptCount() >= properties.getOutbox().getMaxAttempts();
            int backoff = Math.min(60, properties.getOutbox().getBackoffSeconds()
                    * Math.max(1, row.attemptCount()));
            dao.markRetry(row.tenantId(), row.eventId(), e.getClass().getSimpleName(), backoff, dead);
        }
    }
}
