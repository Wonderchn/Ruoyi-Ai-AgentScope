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

package com.nageoffer.ai.ragent.mcp.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重放账本语义判据：ON CONFLICT 原子消费（1=首次 / 0=重复）、存储异常原样上抛
 * （过滤器按 503 拒绝，绝不当作未消费放行）。
 */
@Tag("dev")
class McpJdbcDelegationReplayStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final McpJdbcDelegationReplayStore store = new McpJdbcDelegationReplayStore(jdbc);

    @Test
    @DisplayName("插入 1 行=首次消费 true；SQL 带 ON CONFLICT DO NOTHING（原子性由主键承载）")
    void firstConsumeReturnsTrue() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        boolean first = store.consume("platform", "jti-1", "T1", Instant.now().plusSeconds(60));

        assertThat(first).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(), any(), any(), any(), any());
        assertThat(sql.getValue())
                .contains("INSERT INTO mcp_delegation_replay")
                .contains("ON CONFLICT DO NOTHING");
    }

    @Test
    @DisplayName("冲突 0 行=重复 jti false（过滤器按 401 拒绝）")
    void duplicateConsumeReturnsFalse() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThat(store.consume("platform", "jti-1", "T1", Instant.now().plusSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("存储不可用：异常原样上抛，绝不吞掉当未消费")
    void storageFailurePropagates() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> store.consume("platform", "jti-1", "T1", Instant.now().plusSeconds(60)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
