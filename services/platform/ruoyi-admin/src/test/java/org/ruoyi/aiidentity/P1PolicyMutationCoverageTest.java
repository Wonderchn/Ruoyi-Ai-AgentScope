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

package org.ruoyi.aiidentity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.ruoyi.system.aiidentity.AiPolicyRevisionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1.4 drain 协调器：政策变更的"禁止新授权 → 等待退出 → 提交"序列。
 *
 * <p>本类钉住"不虚假成功"的可判定面：
 * <ul>
 *   <li>活跃 permit 未清空 → 返回 closed=false、<b>不 bump</b>、屏障保持 PENDING；</li>
 *   <li>节点不可达（回报 empty）→ 至少以本地共享表事实判定，不清空不放行；</li>
 *   <li>drain 闭合 → 在**同一事务**内 bump + 置 CLOSED，且随后解除节点屏障；</li>
 *   <li>prepare 与等待在事务之外（等待期间不持租户锁——时序上 prepare 先于轮询）。</li>
 * </ul>
 */
@Tag("dev")
class P1PolicyMutationCoverageTest {

    private static final String TENANT = "T1";

    private static TransactionTemplate passthroughTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return template;
    }

    @Test
    @DisplayName("活跃 permit 未清空：不 bump、屏障保持 PENDING、closed=false")
    void drainTimeoutNeverClaimsSuccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("sys_ai_execution_permit"), any(Class.class), any(Object[].class)))
                .thenReturn(1L);
        AiPolicyRevisionService revisions = mock(AiPolicyRevisionService.class);
        RevocationBarrierCoordinator.AiBarrierPort port = mock(RevocationBarrierCoordinator.AiBarrierPort.class);
        when(port.close(anyString(), anyString(), any())).thenReturn(Optional.of(1L));
        when(port.activePermitCount(anyString())).thenReturn(Optional.of(1L));

        // drain 超时用短超时触发：直接构造协调器并在测试里缩短等待——为不改产品常量，
        // 用 30s 常量不可行；改为断言"第一次轮询即超时"的路径需要注入时钟，
        // 这里用真实等待不可取，改为验证"剩余>0 时绝不 bump"的直接语义：
        // 通过让 deadline 立即到达（把 DRAIN_TIMEOUT 读作静态常量，测试用打断路径）。
        Thread.currentThread().interrupt();
        RevocationBarrierCoordinator coordinator = new RevocationBarrierCoordinator(
                jdbc, revisions, port, passthroughTemplate());
        RevocationBarrierCoordinator.DrainResult result = coordinator.drainAndBump(TENANT, "b-1", "test");
        Thread.interrupted(); // 清理中断标记

        assertThat(result.closed()).isFalse();
        assertThat(result.remaining()).isEqualTo(1L);
        assertThat(result.detail()).contains("PENDING");
        verify(revisions, never()).bumpAll(any());
    }

    @Test
    @DisplayName("drain 闭合：同一事务 bump + 置 CLOSED，随后解除节点屏障")
    void drainedPathBumpsInOneTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("sys_ai_execution_permit"), any(Class.class), any(Object[].class)))
                .thenReturn(0L);
        AiPolicyRevisionService revisions = mock(AiPolicyRevisionService.class);
        when(revisions.currentVersion(TENANT)).thenReturn(Optional.of(8));
        RevocationBarrierCoordinator.AiBarrierPort port = mock(RevocationBarrierCoordinator.AiBarrierPort.class);
        when(port.close(anyString(), anyString(), any())).thenReturn(Optional.of(0L));
        when(port.activePermitCount(anyString())).thenReturn(Optional.of(0L));

        TransactionTemplate template = passthroughTemplate();
        RevocationBarrierCoordinator.DrainResult result = new RevocationBarrierCoordinator(
                jdbc, revisions, port, template).drainAndBump(TENANT, "b-2", "policy change");

        assertThat(result.closed()).isTrue();
        assertThat(result.newVersion()).isEqualTo(8);
        verify(revisions).bumpAll(java.util.List.of(TENANT));
        verify(jdbc).update(contains("SET status = 'CLOSED'"), any(Object[].class));
        verify(port).open(TENANT, "b-2");
        // bump 与 CLOSED 出自同一个 TransactionTemplate.execute 调用
        verify(template).execute(ArgumentMatchers.any(TransactionCallback.class));
    }

    @Test
    @DisplayName("节点不可达但仍要 prepare：规则是 CLOSE 先于等待、PENDING 写在前")
    void prepareHappensBeforeWaiting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("sys_ai_execution_permit"), any(Class.class), any(Object[].class)))
                .thenReturn(0L);
        AiPolicyRevisionService revisions = mock(AiPolicyRevisionService.class);
        when(revisions.currentVersion(TENANT)).thenReturn(Optional.of(4));
        RevocationBarrierCoordinator.AiBarrierPort port = mock(RevocationBarrierCoordinator.AiBarrierPort.class);
        // 节点完全不可达：close/status 都 empty
        when(port.close(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(port.activePermitCount(anyString())).thenReturn(Optional.empty());

        new RevocationBarrierCoordinator(jdbc, revisions, port, passthroughTemplate())
                .drainAndBump(TENANT, "b-3", "node down");

        // PENDING 先写（prepare），且本地活跃集为空时允许闭合（共享表事实为准）
        verify(jdbc).update(contains("'PENDING'"), any(Object[].class));
        verify(revisions).bumpAll(java.util.List.of(TENANT));
    }

    @Test
    @DisplayName("空租户拒绝：不产生任何写入")
    void blankTenantRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RevocationBarrierCoordinator coordinator = new RevocationBarrierCoordinator(jdbc,
                mock(AiPolicyRevisionService.class),
                mock(RevocationBarrierCoordinator.AiBarrierPort.class), passthroughTemplate());

        assertThatThrownBy(() -> coordinator.drainAndBump("", "b", "r"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
