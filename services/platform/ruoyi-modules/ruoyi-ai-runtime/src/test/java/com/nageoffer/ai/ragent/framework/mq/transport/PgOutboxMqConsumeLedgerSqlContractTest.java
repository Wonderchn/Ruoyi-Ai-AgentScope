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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V18 与 Java 侧去重账本的**跨产物漂移护栏**。
 *
 * <p><b>为什么需要它。</b>去重语义被写在两个地方：V18 的 {@code CHECK (state IN (...))}
 * 约束，与 {@link PgOutboxMqConsumeLedger} 里的 SQL 常量 / {@link OutboxMqConsumeState}。
 * 二者漂移的症状是本实现里最坏的一种：**写入被数据库约束拒绝**（
 * 例如 Java 端新增了一个状态而迁移没加），发生在消费路径上，而且只在真库上出现 ——
 * 单测全绿、编译全绿，只有真环境失败。所以这里直接读**真实迁移文件**比对，
 * 而不是在测试里再抄一份常量。
 *
 * <p>第二条判据（认领必须是单条语句）针对 TOCTOU：{@code claim} 若退化成
 * "先 SELECT 再判断"，两个实例同时投递同一个 key 会**双双判为首次**，副作用做两次。
 * 断言 SQL 里不出现 {@code SELECT}，是为了让这个退化在代码评审之外还有一道机器拦截。
 */
@Tag("dev")
class PgOutboxMqConsumeLedgerSqlContractTest {

    private static final String MIGRATION =
            "services/platform/docs/script/sql/postgres/V18__mq_consumer_deduplication.sql";

    /** 从测试工作目录逐级向上找迁移文件（surefire 的 cwd 是模块目录，不在仓库根）。 */
    private static Path migrationFile() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(MIGRATION);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("找不到 " + MIGRATION + "（从 " + Path.of("").toAbsolutePath()
                + " 逐级向上 8 层）—— 该护栏不允许静默消失");
    }

    private static String migrationSql() throws IOException {
        return Files.readString(migrationFile(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Java 的四个状态与 V18 的 CHECK 约束逐字一致（漂移会让真库写入被拒）")
    void javaStatesMatchTheMigrationCheckConstraint() throws IOException {
        String sql = migrationSql();
        for (String state : List.of(OutboxMqConsumeState.PROCESSING, OutboxMqConsumeState.CONSUMED,
                OutboxMqConsumeState.RETRYABLE, OutboxMqConsumeState.REJECTED)) {
            assertThat(sql)
                    .as("V18 的状态约束必须包含 %s", state)
                    .contains("'" + state + "'");
            assertThat(OutboxMqConsumeState.PROCESSING).isEqualTo("PROCESSING");
        }
        assertThat(sql).contains("CONSTRAINT ck_ai_mq_consume_dedup_state");
    }

    @Test
    @DisplayName("认领是**单条** INSERT ... ON CONFLICT ... RETURNING（不得退化成先查后写）")
    void claimIsASingleAtomicStatement() {
        assertThat(PgOutboxMqConsumeLedger.CLAIM_SQL)
                .startsWith("INSERT INTO platform.ai_mq_consume_dedup")
                .contains("ON CONFLICT (tenant_id, dedup_key) DO UPDATE")
                .contains("RETURNING state")
                .as("出现 SELECT 就说明退化成了先查后写（TOCTOU：两实例会双双认领成功）")
                .doesNotContain("SELECT");
    }

    @Test
    @DisplayName("可再认领的两个条件都在：RETRYABLE，或 PROCESSING 且超过租约（消费者崩溃的出路）")
    void reclaimabilityCoversRetryableAndExpiredLease() {
        assertThat(PgOutboxMqConsumeLedger.CLAIM_SQL)
                .contains("ai_mq_consume_dedup.state = 'RETRYABLE'")
                .contains("ai_mq_consume_dedup.state = 'PROCESSING'")
                .contains("interval '1 second'")
                .contains("updated_at <");
    }

    @Test
    @DisplayName("终局操作只对 PROCESSING 生效：不把 CONSUMED/REJECTED 重新拖回可重试")
    void terminalTransitionsAreGuarded() {
        assertThat(PgOutboxMqConsumeLedger.MARK_CONSUMED_SQL).contains("state='PROCESSING'");
        assertThat(PgOutboxMqConsumeLedger.RELEASE_SQL).contains("state='PROCESSING'");
        assertThat(PgOutboxMqConsumeLedger.MARK_REJECTED_SQL)
                .contains("state IN ('PROCESSING', 'RETRYABLE')")
                .as("拒绝是终局：不得允许从 REJECTED 再被改写")
                .doesNotContain("'REJECTED')");
    }

    @Test
    @DisplayName("表名/键与 V18 一致，且所有语句都按 (tenant_id, dedup_key) 定位（C6 租户边界）")
    void allStatementsAreTenantScoped() {
        for (String sql : List.of(PgOutboxMqConsumeLedger.CLAIM_SQL, PgOutboxMqConsumeLedger.MARK_CONSUMED_SQL,
                PgOutboxMqConsumeLedger.MARK_REJECTED_SQL, PgOutboxMqConsumeLedger.RELEASE_SQL)) {
            assertThat(sql).contains("platform.ai_mq_consume_dedup");
        }
        assertThat(PgOutboxMqConsumeLedger.MARK_CONSUMED_SQL).contains("WHERE tenant_id=? AND dedup_key=?");
        assertThat(PgOutboxMqConsumeLedger.MARK_REJECTED_SQL).contains("WHERE tenant_id=? AND dedup_key=?");
        assertThat(PgOutboxMqConsumeLedger.RELEASE_SQL).contains("WHERE tenant_id=? AND dedup_key=?");
        assertThat(migrationSqlQuietly()).contains("PRIMARY KEY (tenant_id, dedup_key)");
    }

    private static String migrationSqlQuietly() {
        try {
            return migrationSql();
        } catch (IOException e) {
            throw new AssertionError("读取 V18 失败", e);
        }
    }
}
