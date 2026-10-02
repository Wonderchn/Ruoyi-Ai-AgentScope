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

package com.nageoffer.ai.ragent.boundary;

import com.nageoffer.ai.ragent.authorization.AuthorizedDownloadService;
import com.nageoffer.ai.ragent.authorization.DefaultRevocationGuard;
import com.nageoffer.ai.ragent.authorization.TenantObjectReferenceRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Permit must cover the actual transaction/output lifetime, including failure paths. */
class P1IsolationAcceptanceTest {
    @AfterEach void cleanup() {
        PrincipalContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test void releasesOnlyAfterCommitOrRollback() {
        for (int outcome : new int[]{TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK}) {
            RevocationGuard guard = mock(RevocationGuard.class);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            var operation = new RevocationGuard.Operation(guard, "p", "op");
            operation.close();
            operation.close();
            verifyNoInteractions(guard);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations().get(0).afterCompletion(outcome);
            verify(guard).release("p", "op");
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void releaseFailureCanBeRetried() {
        RevocationGuard guard = mock(RevocationGuard.class);
        doThrow(new ServiceException("unknown")).doNothing().when(guard).release("p", "op");
        var operation = new RevocationGuard.Operation(guard, "p", "op");
        assertThatThrownBy(operation::close).isInstanceOf(ServiceException.class);
        operation.close();
        operation.close();
        verify(guard, times(2)).release("p", "op");
    }

    @Test void streamIsHeldUntilCloseAndCannotReadAfterRelease() throws IOException {
        RevocationGuard guard = mock(RevocationGuard.class);
        InputStream stream = download(guard, new ByteArrayInputStream(new byte[]{42}), true);
        assertThat(stream.read()).isEqualTo(42);
        verify(guard, never()).release(anyString(), anyString());
        stream.close();
        stream.close();
        verify(guard).release("p", "op");
        assertThatThrownBy(stream::read).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> stream.read(new byte[1], 0, 1)).isInstanceOf(IOException.class);
    }

    @Test void failedUnderlyingCloseDoesNotRelease() {
        RevocationGuard guard = mock(RevocationGuard.class);
        InputStream source = new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException { throw new IOException("still running"); }
        };
        InputStream stream = download(guard, source, true);
        assertThatThrownBy(stream::close).isInstanceOf(IOException.class);
        verify(guard, never()).release(anyString(), anyString());
    }

    @Test void highRiskDefaultClosedBeforeStorageIo() {
        RevocationGuard guard = mock(RevocationGuard.class);
        assertThatThrownBy(() -> download(guard, new ByteArrayInputStream(new byte[0]), false))
                .isInstanceOf(ServiceException.class);
        verify(guard, never()).enter(any(), anyString(), anyString());
    }

    @Test void unknownPermitCountCannotBeReadAsZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> new DefaultRevocationGuard(jdbc).activePermitCount("T1"))
                .isInstanceOf(ServiceException.class);
    }

    @Test void missingParentAndChildGrantCannotExpandParentScope() {
        var principal = new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 7, 3,
                Set.of("kb.read"), "jti", "platform", 0, Long.MAX_VALUE);
        ResourceAuthorizationService.FactPort facts = mock(ResourceAuthorizationService.FactPort.class);
        when(facts.aclVersion("T1")).thenReturn(Optional.of(3));
        var child = new ResourceAuthorizationService.ResourceFact("doc:d", "DOCUMENT", "kb:k",
                principal.membershipId(), null, "ACTIVE", 1, java.util.List.of());
        when(facts.facts(eq("T1"), any())).thenReturn(java.util.Map.of("doc:d", child));
        ResourceAuthorizationService.SubjectMatchPort match = (p, refs, version) ->
                new ResourceAuthorizationService.MatchResult(Set.of("member:" + principal.membershipId()), version);
        var service = new com.nageoffer.ai.ragent.framework.security.DefaultResourceAuthorizationService(facts, match, java.time.Clock.systemUTC());
        assertThat(service.check(principal, "kb.read", "doc:d")).isEqualTo(ResourceAuthorizationService.Verdict.UNKNOWN);
        var parent = new ResourceAuthorizationService.ResourceFact("kb:k", "KB", null,
                "platform:T1:2102", null, "ACTIVE", 1, java.util.List.of());
        when(facts.facts(eq("T1"), any())).thenReturn(java.util.Map.of("doc:d", child, "kb:k", parent));
        assertThat(service.check(principal, "kb.read", "doc:d")).isEqualTo(ResourceAuthorizationService.Verdict.DENY);
    }

    private InputStream download(RevocationGuard guard, InputStream source, boolean enabled) {
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 7, 3,
                Set.of("document.download"), "jti", "platform", 0, Long.MAX_VALUE));
        ResourceAuthorizationService authorization = mock(ResourceAuthorizationService.class);
        when(authorization.check(any(), eq("document.download"), eq("doc:d")))
                .thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        TenantObjectReferenceRepository refs = mock(TenantObjectReferenceRepository.class);
        when(refs.findDocumentStorage("T1", "d"))
                .thenReturn(Optional.of(new TenantObjectReferenceRepository.DocumentStorage("T1/docs/key.txt", "text/plain")));
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.openStream(anyString())).thenReturn(source);
        ObjectProvider<ResourceAuthorizationService> authProvider = mock(ObjectProvider.class);
        when(authProvider.getIfAvailable()).thenReturn(authorization);
        ObjectProvider<FileStorageService> storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(guard.enter(any(), anyString(), anyString())).thenReturn(new RevocationGuard.Operation(guard, "p", "op"));
        AuthorizedDownloadService service = new AuthorizedDownloadService(authProvider, refs, storageProvider);
        service.setRevocations(guard);
        if (enabled) { service.setHighRiskEnabled(true); }
        return service.openDocumentStream("d");
    }
}
