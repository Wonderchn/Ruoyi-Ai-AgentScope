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

package org.ruoyi.system.aiidentity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.system.aiidentity.domain.SysAiPolicyRevision;
import org.ruoyi.system.aiidentity.mapper.SysAiPolicyRevisionMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * U04/P1.2b：策略版本服务的 SQL/调用序列语义（mock mapper，不引新测试依赖）。
 *
 * <p>口径：无行 → empty/拒绝，<b>绝不默认为 1</b>；bump 受影响行数必须为 1
 * （否则抛错回滚使用方事务）；bumpAll 排序去重逐租户递增（防死锁）；
 * 新租户初始化只建 version=1 一行；护栏必须在真实事务内调用。
 */
@Tag("dev")
class P1PolicyRevisionTest {

    private final SysAiPolicyRevisionMapper mapper = mock(SysAiPolicyRevisionMapper.class);
    private final AiPolicyRevisionServiceImpl service = new AiPolicyRevisionServiceImpl(mapper);

    @Test
    void missingRowMeansNoVersionNeverDefaultsToOne() {
        when(mapper.selectVersionByTenantId("T1")).thenReturn(null);
        assertEquals(Optional.empty(), service.currentVersion("T1"));
        ServiceException exception = assertThrows(ServiceException.class,
            () -> service.requireCurrentVersion("T1"));
        assertTrue(exception.getMessage().contains("无 AI 策略版本"),
            () -> "拒绝消息应说明无版本: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("不默认为 1"));
    }

    @Test
    void currentVersionReturnsTheStoredValue() {
        when(mapper.selectVersionByTenantId("T1")).thenReturn(7);
        assertEquals(Optional.of(7), service.currentVersion("T1"));
        assertEquals(7, service.requireCurrentVersion("T1"));
    }

    @Test
    void bumpRequiresExactlyOneAffectedRow() {
        when(mapper.incrementVersion("T1")).thenReturn(1);
        service.bump("T1");
        verify(mapper, times(1)).incrementVersion("T1");

        when(mapper.incrementVersion("T-MISSING")).thenReturn(0);
        ServiceException noRow = assertThrows(ServiceException.class, () -> service.bump("T-MISSING"));
        assertTrue(noRow.getMessage().contains("事务回滚"));

        // 防御：受影响行数 > 1 同样拒绝（不可能发生，但契约是「必须为 1」）
        when(mapper.incrementVersion("T-WEIRD")).thenReturn(2);
        assertThrows(ServiceException.class, () -> service.bump("T-WEIRD"));
    }

    @Test
    void bumpRejectsBlankTenantId() {
        ServiceException exception = assertThrows(ServiceException.class, () -> service.bump(" "));
        assertTrue(exception.getMessage().contains("租户编号为空"));
        verify(mapper, never()).incrementVersion(anyString());
    }

    @Test
    void bumpAllSortsAndDeduplicatesTenantsToAvoidDeadlock() {
        when(mapper.incrementVersion(anyString())).thenReturn(1);
        service.bumpAll(new LinkedHashSet<>(List.of("T2", "T1", "T2")));
        InOrder inOrder = inOrder(mapper);
        inOrder.verify(mapper).incrementVersion("T1");
        inOrder.verify(mapper).incrementVersion("T2");
        verify(mapper, times(2)).incrementVersion(anyString());
    }

    @Test
    void bumpAllStopsRollingUpOnFirstFailure() {
        when(mapper.incrementVersion("T1")).thenReturn(1);
        when(mapper.incrementVersion("T2")).thenReturn(0);
        when(mapper.incrementVersion("T3")).thenReturn(1);
        // 排序后 T2 失败，T3 不得被继续递增（使用方事务整体回滚）
        assertThrows(ServiceException.class, () -> service.bumpAll(Set.of("T3", "T2", "T1")));
        verify(mapper, never()).incrementVersion("T3");
    }

    @Test
    void bumpAllWithNoEnumeratedTenantsIsANoOp() {
        service.bumpAll(List.of());
        service.bumpAll(null);
        verify(mapper, never()).incrementVersion(anyString());
    }

    @Test
    void initializeInsertsExactlyOneVersionOneRowOnlyWhenAbsent() {
        when(mapper.selectVersionByTenantId("T-NEW")).thenReturn(null);
        when(mapper.insert(any(SysAiPolicyRevision.class))).thenReturn(1);
        service.initialize("T-NEW");
        org.mockito.ArgumentCaptor<SysAiPolicyRevision> captor =
            org.mockito.ArgumentCaptor.forClass(SysAiPolicyRevision.class);
        verify(mapper).insert(captor.capture());
        assertEquals("T-NEW", captor.getValue().getTenantId());
        assertEquals(1, captor.getValue().getVersion());

        // 已存在版本行 → 拒绝重复初始化（旧租户初始化来源归 C6，不在迁移/服务里默认）
        when(mapper.selectVersionByTenantId("T-OLD")).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.initialize("T-OLD"));
        verify(mapper, times(1)).insert(any(SysAiPolicyRevision.class));
    }

    @Test
    void guardRequiresAnActiveTransaction() {
        // 单测线程无事务：护栏必须直接拒绝（事务契约的失败模式是显式异常而非静默失效）
        AiPolicyMutationGuard guard = new AiPolicyMutationGuard(service);
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> guard.bumpTenant("T1"));
        assertTrue(exception.getMessage().contains("同一事务"));
        assertThrows(IllegalStateException.class, () -> guard.bump(Set.of("T1")));
        verify(mapper, never()).incrementVersion(anyString());
    }

}
