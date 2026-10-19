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

package com.nageoffer.ai.ragent.runtime.exec;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F13:2 坑 2：{@code flow.run} 必须走自己的 run 作用域分支，不能沿用 kb/document。
 *
 * <p>若少了这个分支，执行首步会以 {@code kb.read} 查 run 作用域主体并 403
 * （{@code ProviderCallBoundary} 只认 kb/document 的旧形态）。
 */
@Tag("dev")
class ProviderCallBoundaryFlowRunTest {

    private static final String TENANT = "T1";
    private static final String USER = "2101";
    private static final String MEMBER = "platform:T1:2101";
    private static final String RUN_ID = "r-" + "5f".repeat(16);
    private static final String PRINCIPAL_JTI = "async-" + RUN_ID;

    private final RunAccessService access = mock(RunAccessService.class);
    private final RevocationGuard revocationGuard = mock(RevocationGuard.class);
    private final ObjectProvider<RevocationGuard> guards = mock(ObjectProvider.class);
    private final ProviderCallBoundary boundary = new ProviderCallBoundary(access, guards);

    @Test
    @DisplayName("flow.run 走 flow.run 作用域（不是 kb.read）并登记 run:<runId>")
    void flowRunUsesItsOwnScope() {
        RunExecutionGuard executionGuard = mock(RunExecutionGuard.class);
        when(executionGuard.commitAtomic(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        ExecutionPrincipal principal = new ExecutionPrincipal(TENANT, USER, MEMBER, 3, 5,
                java.util.Set.of("flow.run"), PRINCIPAL_JTI, "platform",
                Instant.now().getEpochSecond(), Instant.now().getEpochSecond() + 300);
        RunRecord run = new RunRecord(TENANT, RUN_ID, MEMBER, USER, "flow.run", "RUNNING", "hash", "idem",
                "{}", "{}", "f13-flow-v1", 3, 5, "[]", 1L, 1L, 1, 1L, "worker-1",
                Instant.now().plusSeconds(30), null, null, null, null, Instant.now(), Instant.now(), null);
        when(access.current(run, java.util.Set.of("flow.run"))).thenReturn(principal);
        when(guards.getIfAvailable()).thenReturn(revocationGuard);
        when(revocationGuard.enter(principal, "flow.run", "run:" + RUN_ID))
                .thenReturn(new RevocationGuard.Operation(revocationGuard, "permit-1", "op-1"));

        boundary.enter(new RunExecution(run, executionGuard));

        verify(access).current(run, java.util.Set.of("flow.run"));
        verify(revocationGuard).enter(principal, "flow.run", "run:" + RUN_ID);
        verify(access, never()).current(run, java.util.Set.of("kb.read"));
        verify(access, never()).current(run, java.util.Set.of("kb.read", "document.read"));
    }

    @Test
    @DisplayName("非 flow.run 动作保持既有 kb/document 分支")
    void otherActionsKeepKbBranch() {
        RunExecutionGuard executionGuard = mock(RunExecutionGuard.class);
        when(executionGuard.commitAtomic(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        ExecutionPrincipal principal = new ExecutionPrincipal(TENANT, USER, MEMBER, 3, 5,
                java.util.Set.of("kb.read"), PRINCIPAL_JTI, "platform",
                Instant.now().getEpochSecond(), Instant.now().getEpochSecond() + 300);
        RunRecord run = new RunRecord(TENANT, RUN_ID, MEMBER, USER, "rag.chat", "RUNNING", "hash", "idem",
                "{}", "{}", "p2-v1", 3, 5, "[]", 1L, 1L, 1, 1L, "worker-1",
                Instant.now().plusSeconds(30), null, null, null, null, Instant.now(), Instant.now(), null);
        when(access.current(run, java.util.Set.of("kb.read"))).thenReturn(principal);
        when(guards.getIfAvailable()).thenReturn(revocationGuard);
        when(revocationGuard.enter(principal, "kb.read", "run:" + RUN_ID))
                .thenReturn(new RevocationGuard.Operation(revocationGuard, "permit-2", "op-2"));

        boundary.enter(new RunExecution(run, executionGuard));

        verify(access).current(run, java.util.Set.of("kb.read"));
        verify(revocationGuard).enter(principal, "kb.read", "run:" + RUN_ID);
        verify(access, never()).current(run, java.util.Set.of("flow.run"));
    }
}
