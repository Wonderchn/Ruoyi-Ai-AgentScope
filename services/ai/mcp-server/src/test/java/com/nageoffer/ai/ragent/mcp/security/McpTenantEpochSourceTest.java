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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 租户开通版本事实源判据：有行返回值；无行=0（过滤器 503 拒绝，绝不默认 1）；
 * 存储异常原样上抛（同样收敛为 503，不降级）。
 */
@Tag("dev")
class McpTenantEpochSourceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final McpTenantEpochSource source = new McpTenantEpochSource(jdbc);

    @Test
    @DisplayName("有开通行：返回真实 epoch")
    void returnsConfiguredEpoch() {
        when(jdbc.queryForObject(eq(McpTenantEpochSource.EPOCH_QUERY), eq(Integer.class), any(Object[].class)))
                .thenReturn(7);

        assertThat(source.currentVersion("T1")).isEqualTo(7);
    }

    @Test
    @DisplayName("无行=未开通：返回 0，调用方必须拒绝")
    void missingRowReturnsZero() {
        when(jdbc.queryForObject(eq(McpTenantEpochSource.EPOCH_QUERY), eq(Integer.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(source.currentVersion("T2")).isZero();
    }

    @Test
    @DisplayName("存储不可用：异常上抛（过滤器映射 503）")
    void storageFailurePropagates() {
        when(jdbc.queryForObject(eq(McpTenantEpochSource.EPOCH_QUERY), eq(Integer.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> source.currentVersion("T1"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
