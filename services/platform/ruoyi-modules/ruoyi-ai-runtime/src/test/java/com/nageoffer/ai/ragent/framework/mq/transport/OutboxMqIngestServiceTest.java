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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2.2 / C12.3 / C12.4：消费端摄取管线的失败语义。
 *
 * <p><b>为什么这些判据必须在 broker 之外钉死。</b>本机与 VM 都没有 RocketMQ broker，
 * "重复/乱序/崩溃/重平衡"的**真机**判据只能记 NOT_RUN。但其中真正会丢数据的部分
 * ——"重复不得产生第二次副作用"、"消费前授权拒绝不得被当可重试"、
 * "授权事实不可得不得被当拒绝"、"handler 失败后必须能重来"——
 * 全部发生在**管线内部**，可以用确定性测试钉住。把它们留给真机验证，
 * 等于把最危险的四条语义留到环境最不可控的时候才发现。
 *
 * <p>{@link FakeLedger} 镜像 V18/真库已验证的状态机（认领 / 租约 / 终局拒绝）。
 * 真库侧的证据是 `.scratch/platform-embedded-impl/WP-043/20261006-t3-v18-consumer-dedup/`
 * 的 {@code V18Probe}（真 PG 17.11，20 项检查含租约过期再认领与 CONSUMED 不被租约绕过）。
 */
@Tag("dev")
class OutboxMqIngestServiceTest {

    private static final Set<String> TYPES = Set.of("run.status", "run.terminal");

    private FakeLedger ledger;
    private RecordingHandler handler;
    private OutboxMqReauthorizer.Decision decision;

    @BeforeEach
    void setUp() {
        ledger = new FakeLedger();
        handler = new RecordingHandler();
        decision = OutboxMqReauthorizer.Decision.ALLOW;
    }

    private OutboxMqIngestService service() {
        return new OutboxMqIngestService(envelope -> decision, ledger, handler, 300, TYPES);
    }

    private static OutboxMqEnvelope envelope() {
        return OutboxMqEnvelope.builder()
                .tenantId("000000").eventId("e-1").runId("r-1").eventType("run.status").seq(7L)
                .payload("{}").sentAt(System.currentTimeMillis()).build();
    }

    @Test
    @DisplayName("首次消费：执行副作用并记 CONSUMED")
    void firstDeliveryConsumesOnce() {
        assertThat(service().ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.CONSUMED);
        assertThat(handler.handled).hasSize(1);
        assertThat(ledger.stateOf("000000", "evt:e-1")).isEqualTo(OutboxMqConsumeState.CONSUMED);
    }

    @Test
    @DisplayName("重复投递：返回 DUPLICATE 且**不产生第二次副作用**（C12.4 第一条）")
    void duplicateDeliveryNeverRepeatsTheSideEffect() {
        OutboxMqIngestService service = service();
        assertThat(service.ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.CONSUMED);
        assertThat(service.ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.DUPLICATE);
        assertThat(service.ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.DUPLICATE);

        assertThat(handler.handled).as("三次投递只允许一次副作用").hasSize(1);
    }

    @Test
    @DisplayName("消费前重新授权被拒：不执行副作用、不重试（REJECTED 是终局，不是可重试失败）")
    void deniedReauthorizationIsATerminalRejection() {
        decision = OutboxMqReauthorizer.Decision.DENY;

        assertThat(service().ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.REJECTED);
        assertThat(handler.handled).isEmpty();
        assertThat(ledger.stateOf("000000", "evt:e-1")).isEqualTo(OutboxMqConsumeState.REJECTED);
        assertThat(ledger.released).as("拒绝不得被记为可重试").isZero();
    }

    @Test
    @DisplayName("拒绝后再投递仍是 REJECTED（重投一万次也不会变得可消费）")
    void rejectionSurvivesRedelivery() {
        decision = OutboxMqReauthorizer.Decision.DENY;
        OutboxMqIngestService service = service();

        assertThat(service.ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.REJECTED);
        assertThat(service.ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.REJECTED);
        assertThat(handler.handled).isEmpty();
        assertThat(ledger.released).isZero();
    }

    @Test
    @DisplayName("授权事实不可得：抛出重投，**不记拒绝**、不留认领行（否则一次 DB 抖动＝永久丢弃）")
    void unavailableReauthorizationRetriesWithoutRecordingRejection() {
        OutboxMqIngestService service = new OutboxMqIngestService(envelope -> {
            throw new OutboxMqReauthorizationUnavailableException("db down");
        }, ledger, handler, 300, TYPES);

        assertThatThrownBy(() -> service.ingest(envelope()))
                .isInstanceOf(OutboxMqReauthorizationUnavailableException.class);
        assertThat(handler.handled).isEmpty();
        assertThat(ledger.stateOf("000000", "evt:e-1"))
                .as("不可得 ≠ 被拒：账本不得留下 REJECTED").isNull();
    }

    @Test
    @DisplayName("handler 客观失败：抛出**且账本回到可再认领**，重投必须能真正重做副作用")
    void handlerFailureMustBeRetryableNotSkippedAsDuplicate() {
        OutboxMqIngestService service = service();
        handler.failFirst = true;

        assertThatThrownBy(() -> service.ingest(envelope())).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.stateOf("000000", "evt:e-1")).isEqualTo(OutboxMqConsumeState.RETRYABLE);

        // 关键后半段：若失败时没有 release，这里会返回 DUPLICATE 而**永远不再执行**副作用。
        assertThat(service.ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.CONSUMED);
        assertThat(handler.attempts).as("handle() 被调用两次：第一次失败、重投后第二次成功").isEqualTo(2);
        assertThat(handler.handled).as("只有成功的那次留下记录").hasSize(1);
        assertThat(ledger.stateOf("000000", "evt:e-1")).isEqualTo(OutboxMqConsumeState.CONSUMED);
    }

    @Test
    @DisplayName("未知 schemaVersion：拒绝而不是猜字段，且不执行副作用")
    void unknownSchemaVersionIsRejected() {
        OutboxMqEnvelope newer = OutboxMqEnvelope.builder()
                .schemaVersion(99).tenantId("000000").eventId("e-9").runId("r-1")
                .eventType("run.status").seq(1L).build();

        assertThat(service().ingest(newer)).isEqualTo(OutboxMqIngestService.IngestOutcome.REJECTED);
        assertThat(handler.handled).isEmpty();
        assertThat(ledger.stateOf("000000", "evt:e-9")).isEqualTo(OutboxMqConsumeState.REJECTED);
    }

    @Test
    @DisplayName("未订阅的事件类型：拒绝（主题与类型必须自洽）")
    void unsubscribedEventTypeIsRejected() {
        OutboxMqEnvelope other = OutboxMqEnvelope.builder()
                .tenantId("000000").eventId("e-8").runId("r-1").eventType("run.usage").seq(1L).build();

        assertThat(service().ingest(other)).isEqualTo(OutboxMqIngestService.IngestOutcome.REJECTED);
        assertThat(handler.handled).isEmpty();
    }

    @Test
    @DisplayName("不可键化 / 缺租户：抛出而不是 ack（ack 等于悄悄丢弃）")
    void unkeyableMessagesAreNotAcked() {
        OutboxMqEnvelope noTenant = OutboxMqEnvelope.builder()
                .tenantId("  ").eventId("e-1").eventType("run.status").build();
        OutboxMqEnvelope noKey = OutboxMqEnvelope.builder()
                .tenantId("000000").eventType("run.status").build();

        assertThatThrownBy(() -> service().ingest(noTenant)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺租户");
        assertThatThrownBy(() -> service().ingest(noKey)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("去重键不可得");
        assertThatThrownBy(() -> service().ingest(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(handler.handled).isEmpty();
        assertThat(ledger.rows).isEmpty();
    }

    @Test
    @DisplayName("租户边界：同 eventId 不同租户各自独立消费（去重键含租户，不得互相压制）")
    void dedupKeyIsTenantScoped() {
        OutboxMqIngestService service = service();
        OutboxMqEnvelope otherTenant = OutboxMqEnvelope.builder()
                .tenantId("000002").eventId("e-1").runId("r-1").eventType("run.status").seq(7L).build();

        assertThat(service.ingest(envelope())).isEqualTo(OutboxMqIngestService.IngestOutcome.CONSUMED);
        assertThat(service.ingest(otherTenant)).isEqualTo(OutboxMqIngestService.IngestOutcome.CONSUMED);
        assertThat(handler.handled).as("两个租户各一次副作用").hasSize(2);
    }

    @Test
    @DisplayName("外部业务动作（operationKey）用 op: 键去重，且与 eventId 键互不干扰")
    void operationKeyDrivesItsOwnDedupKey() {
        OutboxMqIngestService service = service();
        OutboxMqEnvelope external = OutboxMqEnvelope.builder()
                .tenantId("000000").eventId("e-1").eventType("run.status").operationKey("op-7").build();

        assertThat(external.dedupKey()).isEqualTo("op:op-7");
        assertThat(service.ingest(external)).isEqualTo(OutboxMqIngestService.IngestOutcome.CONSUMED);
        assertThat(service.ingest(envelope())).as("evt:e-1 与 op:op-7 是两个键")
                .isEqualTo(OutboxMqIngestService.IngestOutcome.CONSUMED);
        assertThat(handler.handled).hasSize(2);
    }

    // ------------------------------------------------------------------ 测试替身

    private static final class RecordingHandler implements OutboxMqHandler {

        private final List<OutboxMqEnvelope> handled = new ArrayList<>();
        /** 调用次数（含失败的那次）：用来区分"重投真的重做了"与"只是没报错"。 */
        private int attempts;
        private boolean failFirst;

        @Override
        public void handle(OutboxMqEnvelope envelope) {
            attempts++;
            if (failFirst) {
                failFirst = false;
                throw new IllegalStateException("handler boom");
            }
            handled.add(envelope);
        }
    }

    /**
     * 镜像 V18 真库已验证的状态机（认领 / 租约 / 终局）。刻意**不用** mock：
     * mock 只能断言"调了哪个方法"，而这里要断言的是**状态迁移之后的结局**
     * ——"重投能重来"与"重投被自己判成重复"在 mock 层面长得一模一样。
     */
    private static final class FakeLedger implements OutboxMqConsumeLedger {

        private final Map<String, String> rows = new HashMap<>();
        private final Map<String, Instant> claimedAt = new HashMap<>();
        private int released;

        private static String k(String tenantId, String dedupKey) {
            return tenantId + "|" + dedupKey;
        }

        String stateOf(String tenantId, String dedupKey) {
            return rows.get(k(tenantId, dedupKey));
        }

        @Override
        public ClaimOutcome claim(OutboxMqEnvelope envelope, int leaseSeconds) {
            String key = k(envelope.getTenantId(), envelope.dedupKey());
            String state = rows.get(key);
            if (state == null) {
                rows.put(key, OutboxMqConsumeState.PROCESSING);
                claimedAt.put(key, Instant.now());
                return ClaimOutcome.CLAIMED;
            }
            if (OutboxMqConsumeState.RETRYABLE.equals(state)) {
                rows.put(key, OutboxMqConsumeState.PROCESSING);
                claimedAt.put(key, Instant.now());
                return ClaimOutcome.CLAIMED;
            }
            if (OutboxMqConsumeState.PROCESSING.equals(state)) {
                Instant at = claimedAt.getOrDefault(key, Instant.EPOCH);
                if (at.plusSeconds(Math.max(0, leaseSeconds)).isBefore(Instant.now())) {
                    rows.put(key, OutboxMqConsumeState.PROCESSING);
                    claimedAt.put(key, Instant.now());
                    return ClaimOutcome.CLAIMED;
                }
            }
            // CONSUMED / REJECTED / 租约内的 PROCESSING ⇒ 重复
            return ClaimOutcome.DUPLICATE;
        }

        @Override
        public void markConsumed(String tenantId, String dedupKey) {
            rows.put(k(tenantId, dedupKey), OutboxMqConsumeState.CONSUMED);
        }

        @Override
        public void markRejected(String tenantId, String dedupKey, String reason) {
            rows.put(k(tenantId, dedupKey), OutboxMqConsumeState.REJECTED);
        }

        @Override
        public void release(String tenantId, String dedupKey, String reason) {
            released++;
            rows.put(k(tenantId, dedupKey), OutboxMqConsumeState.RETRYABLE);
        }
    }
}
