package com.nageoffer.ai.ragent.authorization;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class BarrierReconciliationAuditTest {
    @Test void aRollbackReopenWritesAttributionAndStillUsesTheTenantBarrierCas() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
        when(jdbc.update(anyString(), anyMap())).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("reconciled_at=now()", "reconciled_by='write-reconciler'",
                    "tenant_id=:tenant", "barrier_id=:barrier", "status='PENDING'");
            assertThat((Map<String,Object>) call.getArgument(1)).containsEntry("tenant", "T1").containsEntry("barrier", "barrier-1");
            return 1;
        });
        var reconciler = new TenantBarrierReconciler(jdbc, transactions);
        assertThat(reconciler.reclaimIfNoActivePermit("T1", "barrier-1", "permit-1").outcome())
                .isEqualTo(TenantBarrierReconciler.Outcome.RECLAIMED);
        clearInvocations(jdbc);
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);
        reconciler.reclaimIfNoActivePermit("T1", "barrier-1", "permit-1");
        verify(jdbc, never()).update(anyString(), anyMap());
    }
}
