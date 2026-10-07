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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 消费端摄取管线：**重新授权 → 原子认领去重 → 副作用 → 记终局**（C2.2/C12.3/C12.4）。
 *
 * <p><b>为什么把顺序钉成"先授权、后认领"。</b>反过来的话，一条**会被拒绝**的消息
 * 也会在账本里留下一条已认领记录；而认领本身就已经改变了"这条消息在集群里的处理状态"。
 * 更关键的是：授权不可得时必须**什么都不做**（抛出重投），先认领就会留下一条
 * {@code PROCESSING} 行，等租约过期后又被认领一次，白白制造重试抖动。
 *
 * <p><b>三种"非成功"结局的落点必须分开。</b>
 * <ul>
 *   <li>{@link IngestOutcome#DUPLICATE}：**正常返回**（ack）。重复不是错误，重投是
 *       至少一次投递的正常形态；把它当失败抛出会让 broker 无限重投同一条重复消息。</li>
 *   <li>{@link IngestOutcome#REJECTED}：**正常返回**（ack）+ 账本记 {@code REJECTED}。
 *       授权拒绝是确定性结论，重投无意义；之所以正常返回而不是抛出，是为了让
 *       broker 的 DLQ 只收"技术性失败"，不被确定性的越权消息灌满。</li>
 *   <li>客观失败：**抛异常**（不 ack）。账本先置回 {@code RETRYABLE}，让重投能再次认领；
 *       不置回的话消息虽会重投，却会被自己的账本判成重复而**永远跳过副作用** ——
 *       这是本管线最隐蔽的一个坑。</li>
 * </ul>
 *
 * <p><b>乱序的处置方式（显式登记）。</b>C12.4 要求"以 seq 为序，迟到消息不得回退可见状态"。
 * 本实现**不在传输层建第二套序**（C2.1 禁止第二套执行账本）：可见状态由
 * {@code ai_run_event} 的 {@code (tenant_id, run_id, seq)} 游标按 seq 重建，
 * 传输层的唤醒是幂等的，迟到的一条只会多唤醒一次，不会把可见状态推回去。
 * 因此这里不做"高水位丢旧消息" —— 那需要一个跨实例的序账本，且在跨实例下
 * 内存高水位不可靠，误丢一条唤醒的代价高于多唤醒一次。真机乱序验证：**NOT_RUN**（无 broker）。
 */
public class OutboxMqIngestService {

    private static final Logger log = LoggerFactory.getLogger(OutboxMqIngestService.class);

    /** 摄取结局。可重试的客观失败不在此枚举里：它以异常形式抛出（见类注释）。 */
    public enum IngestOutcome {
        /** 副作用已完成。 */
        CONSUMED,
        /** 重复投递，未执行副作用。 */
        DUPLICATE,
        /** 授权拒绝 / 版本或类型闸门拒绝，未执行副作用，且不会重试。 */
        REJECTED
    }

    private final OutboxMqReauthorizer reauthorizer;
    private final OutboxMqConsumeLedger ledger;
    private final OutboxMqHandler handler;
    private final int claimLeaseSeconds;
    private final Set<String> subscribedEventTypes;

    public OutboxMqIngestService(OutboxMqReauthorizer reauthorizer, OutboxMqConsumeLedger ledger,
                                 OutboxMqHandler handler, int claimLeaseSeconds,
                                 Set<String> subscribedEventTypes) {
        this.reauthorizer = reauthorizer;
        this.ledger = ledger;
        this.handler = handler;
        this.claimLeaseSeconds = claimLeaseSeconds;
        this.subscribedEventTypes = subscribedEventTypes == null ? Set.of() : Set.copyOf(subscribedEventTypes);
    }

    public IngestOutcome ingest(OutboxMqEnvelope envelope) {
        // 1. 可键化闸门：连去重键都拼不出来的消息**不能 ack**（ack 等于悄悄丢弃），
        //    抛出让 broker 重投、最终进 DLQ 等人工处置。
        if (envelope == null) {
            throw new IllegalArgumentException("outbox MQ 收到空信封，拒绝 ack（不静默丢弃）");
        }
        requireKeyable(envelope);
        String tenantId = envelope.getTenantId();
        String dedupKey = envelope.dedupKey();

        // 2. schemaVersion 闸门：未知版本拒绝而不是猜字段。
        //    这里能记 REJECTED，因为去重键已经可得了。
        if (envelope.getSchemaVersion() != OutboxMqEnvelope.CURRENT_SCHEMA_VERSION) {
            String reason = "未知信封版本 schemaVersion=" + envelope.getSchemaVersion()
                    + "（本消费端支持 " + OutboxMqEnvelope.CURRENT_SCHEMA_VERSION + "）";
            log.warn("拒绝旧/新版本信封：tenant={}, eventId={}, {}", tenantId, envelope.getEventId(), reason);
            ledger.markRejected(tenantId, dedupKey, reason);
            return IngestOutcome.REJECTED;
        }

        // 3. 事件类型闸门：消费端只处理自己订阅过的类型（主题与类型必须自洽）。
        if (!subscribedEventTypes.isEmpty() && !subscribedEventTypes.contains(envelope.getEventType())) {
            String reason = "未订阅的事件类型 eventType=" + envelope.getEventType();
            log.warn("拒绝未订阅类型：tenant={}, eventId={}, {}", tenantId, envelope.getEventId(), reason);
            ledger.markRejected(tenantId, dedupKey, reason);
            return IngestOutcome.REJECTED;
        }

        // 4. 消费前重新授权（C2.2）。DENY 记终局拒绝；UNAVAILABLE 由实现抛出让 broker 重投。
        OutboxMqReauthorizer.Decision decision = reauthorizer.reauthorize(envelope);
        if (decision == OutboxMqReauthorizer.Decision.DENY) {
            log.warn("消费前重新授权被拒，未执行副作用：tenant={}, eventId={}, runId={}",
                    tenantId, envelope.getEventId(), envelope.getRunId());
            ledger.markRejected(tenantId, dedupKey, "reauthorization denied");
            return IngestOutcome.REJECTED;
        }

        // 5. 原子认领去重（C12.3）。重复 ⇒ ack，不执行副作用。
        if (ledger.claim(envelope, claimLeaseSeconds) == OutboxMqConsumeLedger.ClaimOutcome.DUPLICATE) {
            log.debug("重复投递，未执行副作用：tenant={}, dedupKey={}", tenantId, dedupKey);
            return IngestOutcome.DUPLICATE;
        }

        // 6. 副作用。失败必须先把账本置回可再认领，否则重投会被自己的账本判成重复。
        try {
            handler.handle(envelope);
        } catch (RuntimeException e) {
            ledger.release(tenantId, dedupKey, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
        ledger.markConsumed(tenantId, dedupKey);
        return IngestOutcome.CONSUMED;
    }

    private static void requireKeyable(OutboxMqEnvelope envelope) {
        if (envelope.getTenantId() == null || envelope.getTenantId().isBlank()) {
            throw new IllegalArgumentException("outbox MQ 信封缺租户，拒绝 ack（C6：缺身份一律拒绝）");
        }
        if (envelope.getEventType() == null || envelope.getEventType().isBlank()) {
            throw new IllegalArgumentException("outbox MQ 信封缺事件类型，拒绝 ack");
        }
        // dedupKey() 在 eventId 与 operationKey 同时为空时抛，正是我们要的"不可键化即拒绝"
        envelope.dedupKey();
    }
}
