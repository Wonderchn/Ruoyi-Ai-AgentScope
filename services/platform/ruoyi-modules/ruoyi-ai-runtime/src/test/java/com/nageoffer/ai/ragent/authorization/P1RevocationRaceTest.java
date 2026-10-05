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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard.PermitRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * P1.4 强撤权屏障：permit 生命周期与屏障语义。
 *
 * <p>本类钉住的是"不虚假成功/不静默放行"的具体判定：
 * <ul>
 *   <li>epoch 缺失、版本失配、屏障 PENDING/CLOSED/UNKNOWN → <b>拒绝登记且零 INSERT</b>；</li>
 *   <li>epoch 读取是 {@code FOR UPDATE}：这是与撤权动作的互斥点（"检查后执行"不可能与
 *       "撤权生效"交错），也是双节点共享锁的事实；</li>
 *   <li>活跃集是共享表查询：另一节点的活跃段对撤权方可见（不依赖单 JVM 计数）；</li>
 *   <li>release 幂等（同 operationId 重复无新写），不存在/不匹配必须暴露；</li>
 *   <li>UNKNOWN 可写入但 NO_ROW 不可写入；UNKNOWN 的解除只能显式写 OPEN。</li>
 * </ul>
 */
@Tag("dev")
class P1RevocationRaceTest {

    @Test
    void operationEntryAndCloseUseRealTransactionalProxy() {
        var jdbc=jdbcWithEpoch(3,List.of());
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });
        var transactions=new org.springframework.transaction.support.AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition){}
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){}
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){}
        };
        var target=new DefaultRevocationGuard(jdbc);
        var factory=new org.springframework.aop.framework.ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxy=(DefaultRevocationGuard)factory.getProxy();
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("guard",proxy);
        target.configureSelf(beans.getBeanProvider(DefaultRevocationGuard.class));
        var principal=new com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal(TENANT,"2101","platform:T1:2101",7,3,
                java.util.Set.of("kb.read"),"test-jti","platform",0,Long.MAX_VALUE);
        var operation=proxy.enter(principal,"kb.read","kb:test");
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        operation.close();
        verify(jdbc).update(contains("INSERT INTO ai_execution_permit"),any(Object[].class));
        verify(jdbc).update(contains("SET status = 'RELEASED'"),any(Object[].class));
    }

    private static final String TENANT = "T1";

    private static PermitRequest request(int aclVersion) {
        return new PermitRequest(TENANT, "platform:T1:2101", "kb.write", 7, aclVersion,
                "refshash-1", "op-" + System.nanoTime());
    }

    private static JdbcTemplate jdbcWithEpoch(int version, List<String> barrierRows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("ai_acl_epoch"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(version));
        when(jdbc.query(contains("ai_tenant_barrier"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(barrierRows);
        return jdbc;
    }

    @Test
    @DisplayName("无 epoch 行：拒绝登记且零 INSERT（不默认初始化到 1）")
    void missingEpochRefusesWithoutInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("ai_acl_epoch"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> new DefaultRevocationGuard(jdbc).acquire(request(1)))
                .isInstanceOf(ServiceException.class);
        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("aclVersion 失配：拒绝（调用方须重取），零 INSERT")
    void aclVersionMismatchRefuses() {
        JdbcTemplate jdbc = jdbcWithEpoch(5, List.of());

        assertThatThrownBy(() -> new DefaultRevocationGuard(jdbc).acquire(request(3)))
                .isInstanceOf(com.nageoffer.ai.ragent.framework.security.StaleVersionException.class);
        verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
    }

    @Test
    @DisplayName("屏障 PENDING/CLOSED/UNKNOWN：一律拒绝新 permit，零 INSERT")
    void nonOpenBarrierRefuses() {
        for (String state : List.of("PENDING", "CLOSED", "UNKNOWN")) {
            JdbcTemplate jdbc = jdbcWithEpoch(3, List.of(state));
            assertThatThrownBy(() -> new DefaultRevocationGuard(jdbc).acquire(request(3)))
                    .as("屏障 %s 必须拒绝", state)
                    .isInstanceOf(ServiceException.class);
            verify(jdbc, never()).update(contains("INSERT INTO ai_execution_permit"), any(Object[].class));
        }
    }

    @Test
    @DisplayName("epoch 读取必须 FOR UPDATE（与撤权互斥的锁点）")
    void epochReadTakesRowLock() {
        JdbcTemplate jdbc = jdbcWithEpoch(3, List.of());
        when(jdbc.update(contains("INSERT INTO ai_execution_permit"), any(Object[].class))).thenReturn(1);

        new DefaultRevocationGuard(jdbc).acquire(request(3));

        verify(jdbc).query(contains("FOR UPDATE"), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @DisplayName("正常登记：ACTIVE permit 写入共享表，grant 携带登记时 aclVersion")
    void happyPathRegistersActivePermit() {
        JdbcTemplate jdbc = jdbcWithEpoch(3, List.of());
        when(jdbc.update(contains("INSERT INTO ai_execution_permit"), any(Object[].class))).thenReturn(1);

        RevocationGuard.PermitGrant grant = new DefaultRevocationGuard(jdbc).acquire(request(3));

        assertThat(grant.permitId()).isNotBlank();
        assertThat(grant.aclVersion()).isEqualTo(3);
        verify(jdbc).update(contains("'ACTIVE'"), any(Object[].class));
    }

    @Test
    @DisplayName("活跃集是共享表查询：另一节点写入的活跃段对撤权方可见（跨节点事实来源）")
    void activeCountReadsSharedTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("ai_execution_permit"), any(Class.class), any(Object[].class)))
                .thenReturn(2L);

        assertThat(new DefaultRevocationGuard(jdbc).activePermitCount(TENANT)).isEqualTo(2L);
        verify(jdbc).queryForObject(contains("status = 'ACTIVE'"),
                any(Class.class), any(Object[].class));
    }

    @Test
    @DisplayName("release 幂等：重复同体不失败；不存在/不匹配必须暴露；租约只用于失联判定")
    void releaseIsIdempotentButExposesMismatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("SET status = 'RELEASED'"), any(Object[].class))).thenReturn(1);
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);
        guard.release("permit-1", "op-1");
        verify(jdbc).update(contains("operation_id = ?"), any(Object[].class));

        // 二次 release：UPDATE 影响 0 行，但行仍存在 → 幂等通过
        when(jdbc.update(contains("SET status = 'RELEASED'"), any(Object[].class))).thenReturn(0);
        when(jdbc.query(contains("count(*)"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(1));
        guard.release("permit-1", "op-1");

        // 行不存在 → 暴露
        when(jdbc.query(contains("count(*)"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(0));
        assertThatThrownBy(() -> guard.release("permit-x", "op-x"))
                .isInstanceOf(ClientException.class);
    }

    @Test
    @DisplayName("屏障状态写入：NO_ROW 拒绝；UNKNOWN 可写；upsert 含 ON CONFLICT（原位更新）")
    void barrierStateWritesAreGuarded() {
        JdbcTemplate jdbc = jdbcWithEpoch(3, List.of());
        DefaultRevocationGuard guard = new DefaultRevocationGuard(jdbc);

        assertThatThrownBy(() -> guard.setBarrierState(TENANT, RevocationGuard.BarrierState.NO_ROW,
                "b-1", null, "x")).isInstanceOf(ClientException.class);

        when(jdbc.update(contains("INSERT INTO ai_tenant_barrier"), any(Object[].class))).thenReturn(1);
        guard.setBarrierState(TENANT, RevocationGuard.BarrierState.UNKNOWN, "b-1", 5, "node unreachable");
        verify(jdbc).update(contains("ON CONFLICT (tenant_id) DO UPDATE"), any(Object[].class));
    }
}
