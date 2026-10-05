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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.authorization.dao.JdbcDelegationReplayStore;
import com.nageoffer.ai.ragent.framework.security.ProductionReplayGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcDelegationReplayStore} 的单测（mock JdbcTemplate，不连库）。
 *
 * <p>覆盖：SQL 形状（表/列/ON CONFLICT DO NOTHING）、影响行数 1→true（首次消费）、
 * 0→false（重复 jti）、参数绑定（issuer/jti/tenant_id/consumed_at/expires_at）、
 * 存储异常原样向上抛（不得吞掉降级放行）。
 *
 * <p>测试类带 {@code dev} 标签（用户长期要求）。
 */
@Tag("dev")
class JdbcDelegationReplayStoreTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-10-01T00:01:00Z");

    private JdbcTemplate jdbc;
    private JdbcDelegationReplayStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new JdbcDelegationReplayStore(jdbc);
    }

    /** 五个绑定参数的逐位匹配（varargs 展开为 5 个匹配器，避免数组匹配歧义）。 */
    private void stubUpdate(int inserted) {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(inserted);
    }

    @Test
    @DisplayName("SQL 形状：目标表、五列、ON CONFLICT DO NOTHING（绝不允许 DO UPDATE）")
    void sqlShapeMatchesV5Contract() {
        stubUpdate(1);
        store.consume("platform", "jti-1", "T1", EXPIRES_AT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(), any(), any(), any(), any());
        String statement = sql.getValue();
        assertTrue(statement.contains("INSERT INTO ai_delegation_replay"), statement);
        assertTrue(statement.contains("(issuer, jti, tenant_id, consumed_at, expires_at)"), statement);
        assertTrue(statement.contains("VALUES (?, ?, ?, ?, ?)"), statement);
        assertTrue(statement.contains("ON CONFLICT DO NOTHING"), statement);
        assertFalse(statement.toLowerCase().contains("do update"), "绝不允许覆盖已消费的 jti");
    }

    @Test
    @DisplayName("影响行数 = 1 → 首次消费 true；= 0 → 重复 jti false")
    void updateCountDecidesFirstConsumption() {
        stubUpdate(1);
        assertTrue(store.consume("platform", "jti-first", "T1", EXPIRES_AT));

        stubUpdate(0);
        assertFalse(store.consume("platform", "jti-repeat", "T1", EXPIRES_AT));
    }

    @Test
    @DisplayName("参数绑定：issuer/jti/tenant_id 精确，consumed_at≈now，expires_at 精确")
    void parametersAreBoundInOrder() {
        stubUpdate(1);
        Instant before = Instant.now().minus(5, ChronoUnit.SECONDS);
        store.consume("platform", "jti-42", "T1", EXPIRES_AT);

        ArgumentCaptor<Object> issuer = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> jti = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> tenantId = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> consumedAt = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> expiresAt = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), issuer.capture(), jti.capture(),
                tenantId.capture(), consumedAt.capture(), expiresAt.capture());

        assertEquals("platform", issuer.getValue());
        assertEquals("jti-42", jti.getValue());
        assertEquals("T1", tenantId.getValue());
        Timestamp stamp = assertInstanceOf(Timestamp.class, consumedAt.getValue());
        assertTrue(stamp.toInstant().isAfter(before), "consumed_at 应为当前时刻");
        assertEquals(Timestamp.from(EXPIRES_AT), expiresAt.getValue());
    }

    @Test
    @DisplayName("存储异常原样向上抛：调用方按 503 拒绝，不得吞掉")
    void storeFailurePropagates() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("store down");
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenThrow(failure);

        DataAccessResourceFailureException thrown = assertThrows(DataAccessResourceFailureException.class,
                () -> store.consume("platform", "jti-1", "T1", EXPIRES_AT));
        assertSame(failure, thrown);
    }

    @Test
    @DisplayName("实现的是 framework 的 ProductionReplayGuard 端口：异常跨端口向上传播")
    void implementsFrameworkPort() {
        ProductionReplayGuard guard = store;
        when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        // 端口契约：consume 声明 throws Exception，存储异常必须跨端口向上传播
        assertThrows(DataAccessResourceFailureException.class,
                () -> guard.consume("platform", "jti-1", "T1", EXPIRES_AT));
    }

    private static <T> T assertInstanceOf(Class<T> type, Object value) {
        assertTrue(type.isInstance(value), "expected " + type.getSimpleName());
        return type.cast(value);
    }
}
