package com.nageoffer.ai.ragent.authorization;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("dev")
class KnowledgeBaseBatchReadTest {
    @Test void readsOnlyGrantedIdsInOneTenantScopedQuery() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var service = new AiResourceWriteService(jdbc, null, null, null,
                mock(org.springframework.transaction.support.TransactionOperations.class));
        var ids = java.util.stream.IntStream.range(0,200).mapToObj(i -> "kb"+i).toList();
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(invocation -> {
            assertThat((String)invocation.getArgument(0)).contains("platform.ai_knowledge_base", "tenant_id=:tenantId", "id IN (:kbIds)", "deleted=0");
            assertThat((Map<String,Object>)invocation.getArgument(1)).containsEntry("tenantId","T1").containsEntry("kbIds",ids);
            return List.of(new AiResourceWriteService.KnowledgeBaseView("kb199", "last", "e", "c", "m", "d"),
                new AiResourceWriteService.KnowledgeBaseView("kb0", "first", "e", "c", "m", "d"));
        });
        assertThat(service.findKnowledgeBases("T1", ids)).extracting(AiResourceWriteService.KnowledgeBaseView::kbId).containsExactly("kb0","kb199");
        verify(jdbc,times(1)).query(anyString(),anyMap(),any(RowMapper.class));
        clearInvocations(jdbc);
        assertThat(service.findKnowledgeBases("T1",List.of())).isEmpty();
        verifyNoInteractions(jdbc);
        assertThatThrownBy(() -> service.findKnowledgeBases("",ids)).isInstanceOf(RuntimeException.class);
    }
}
