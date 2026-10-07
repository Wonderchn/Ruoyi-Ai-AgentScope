package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.authorization.dao.AiAclEpochMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceAclMapper;
import com.nageoffer.ai.ragent.authorization.dao.AiResourceMapper;
import com.nageoffer.ai.ragent.authorization.dao.ResourceSourceRefMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.AuthorizedResourceScope;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.framework.security.StaleVersionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Tag("dev")
class P2RetrievalProjectionTest {
    private final ExecutionPrincipal principal = new ExecutionPrincipal("T1", "1001", "platform:T1:1001",
            1, 7, Set.of("kb.retrieve"), "test-jti", "test", 0, Long.MAX_VALUE);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AiResourceAuthorizationService service = spy(new AiResourceAuthorizationService(
            mock(AiResourceMapper.class), mock(AiResourceAclMapper.class), mock(AiAclEpochMapper.class),
            mock(ResourceSourceRefMapper.class), Clock.systemUTC()));

    @AfterEach
    void cleanup() {
        PrincipalContext.clear();
    }

    private void grantKbAndOneDocument() {
        PrincipalContext.set(principal);
        service.configureProjection(jdbc);
        doReturn(7).when(service).currentAclVersion("T1");
        doReturn(Verdict.GRANT).when(service).check(principal, "kb.retrieve", "kb:kb1");
        doReturn(List.of("doc:doc1", "doc:denied")).when(service).childResourceRefs("T1", "kb:kb1");
        doReturn(Map.of("doc:doc1", Verdict.GRANT, "doc:denied", Verdict.DENY)).when(service)
                .checkBatch(principal, "kb.retrieve", Set.of("doc:doc1", "doc:denied"));
    }

    @Test
    void projectsPublishedP2ChunksOnlyForGrantedDocuments() throws Exception {
        grantKbAndOneDocument();
        doAnswer(call -> {
            String sql = call.getArgument(0);
            Map<String, Object> parameters = call.getArgument(1);
            assertThat(sql).contains("FROM ai_document_chunk", "c.version_id=d.published_version_id");
            assertThat(sql).doesNotContain("ai_knowledge_vector", "ai_knowledge_document");
            assertThat(parameters).containsEntry("tenant", "T1").containsEntry("kbs", List.of("kb1"))
                    .containsEntry("docs", List.of("doc1"));
            var row = mock(ResultSet.class);
            when(row.getString("id")).thenReturn("version1:chunk-key1");
            when(row.getString("collection_name")).thenReturn("collection1");
            ((RowCallbackHandler) call.getArgument(2)).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
        var scope = service.toRetrievalScope(AuthorizedResourceScope.granted(
                principal, "kb.retrieve", List.of("kb:kb1"), 0));
        assertThat(scope.publishedChunkRefs()).containsExactly("chunk:version1:chunk-key1");
        assertThat(scope.authorizedDocRefs()).containsExactly("doc:doc1");
        assertThat(scope.authorizedCollections()).containsExactly("collection1");
    }

    @Test
    void emptyAuthorizationNeverQueriesAProjection() {
        grantKbAndOneDocument();
        var scope = service.toRetrievalScope(AuthorizedResourceScope.empty(principal, "kb.retrieve", 0));
        assertThat(scope.isEmpty()).isTrue();
        verifyNoInteractions(jdbc);
    }

    @Test
    void deniedDocumentsNeverBecomeAChunkScope() {
        grantKbAndOneDocument();
        doReturn(Map.of("doc:doc1", Verdict.DENY, "doc:denied", Verdict.DENY)).when(service)
                .checkBatch(principal, "kb.retrieve", Set.of("doc:doc1", "doc:denied"));
        var scope = service.toRetrievalScope(AuthorizedResourceScope.granted(
                principal, "kb.retrieve", List.of("kb:kb1"), 0));
        assertThat(scope.publishedChunkRefs()).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void aScopeFromAnotherTenantIsRejectedBeforeSql() {
        grantKbAndOneDocument();
        var other = new ExecutionPrincipal("T2", "1001", "platform:T2:1001", 1, 7,
                Set.of("kb.retrieve"), "test-other", "test", 0, Long.MAX_VALUE);
        var foreign = AuthorizedResourceScope.granted(other, "kb.retrieve", List.of("kb:kb1"), 0);
        assertThatThrownBy(() -> service.toRetrievalScope(foreign)).isInstanceOf(ClientException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void anEpochChangeDuringProjectionInvalidatesTheWholeResult() {
        grantKbAndOneDocument();
        doReturn(7, 8).when(service).currentAclVersion("T1");
        var allowed = AuthorizedResourceScope.granted(principal, "kb.retrieve", List.of("kb:kb1"), 0);
        assertThatThrownBy(() -> service.toRetrievalScope(allowed)).isInstanceOf(StaleVersionException.class);
        verify(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }
}
