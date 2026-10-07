package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.authorization.dao.*;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AuthorizationBatchReadTest {
    @Test void mixedTypesUseTenantAndCompositeKeysAndEmptyBatchesNeverQuery() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var resources = new AiResourceMapper(jdbc);
        var acls = new AiResourceAclMapper(jdbc);
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            Map<String,Object> params = call.getArgument(1);
            assertThat(sql).contains("tenant_id = :tenantId AND (", "resource_type = :type0", "resource_id IN (:ids0)", " OR ");
            assertThat(params.get("tenantId")).isEqualTo("T1");
            assertThat(params.values()).contains("KB", "DOCUMENT");
            assertThat(sql).doesNotContain("same-id", "T1");
            return List.of();
        });
        var keys = new LinkedHashMap<String,List<String>>();
        keys.put("KB", List.of("same-id")); keys.put("DOCUMENT", List.of("same-id"));
        resources.findByIds("T1", keys); acls.findByIds("T1", keys);
        verify(jdbc, times(2)).query(anyString(), anyMap(), any(RowMapper.class));
        clearInvocations(jdbc);
        resources.findByIds("T1", Map.of()); acls.findByIds("T1", Map.of());
        assertThatThrownBy(() -> resources.findByIds("", keys)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void twoHundredResourcesRemainBoundedAndRecheckRevocationAndDataScope() {
        var resources = mock(AiResourceMapper.class);
        var acls = mock(AiResourceAclMapper.class);
        var epochs = mock(AiAclEpochMapper.class);
        var facts = mock(PlatformFactsPort.class);
        ObjectProvider<PlatformFactsPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facts);
        var service = new AiResourceAuthorizationService(resources, acls, epochs, mock(ResourceSourceRefMapper.class), Clock.systemUTC());
        service.configurePlatformFacts(provider);
        var revoked = new AtomicBoolean(false);
        var refs = new ArrayList<String>();
        var rows = new ArrayList<AiResourceMapper.AiResourceRow>();
        var rules = new ArrayList<AiResourceAclMapper.AclRow>();
        for (int i = 0; i < 200; i++) {
            String id = "kb-" + i;
            refs.add("kb:" + id);
            rows.add(new AiResourceMapper.AiResourceRow("T1", "KB", id, i == 199 ? "platform:T1:9998" : "platform:T1:9999", null, null, null, "ACTIVE", 1));
            rules.add(new AiResourceAclMapper.AclRow("acl-" + i, "T1", "KB", id, "TENANT_ALL", null, "kb.read", null, "platform:T1:2101"));
        }
        when(epochs.findVersion("T1")).thenReturn(Optional.of(1));
        when(resources.findByIds(eq("T1"), anyMap())).thenReturn(rows);
        when(acls.findByIds(eq("T1"), anyMap())).thenAnswer(call -> revoked.get() ? List.of() : rules);
        when(facts.match(any(), anyList(), eq("kb.read"))).thenAnswer(call -> {
            List<PlatformFactsPort.Candidate> candidates = call.getArgument(1);
            assertThat(candidates.size()).isLessThanOrEqualTo(200);
            return new PlatformFactsPort.MatchOutcome(1, candidates.stream()
                    .map(candidate -> !candidate.dataScope() || !"platform:T1:9998".equals(candidate.ownerMemberId())).toList(), null);
        });
        var principal = new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 1, Set.of("kb.read"), "jti", "test", 0, Long.MAX_VALUE);
        var first = service.checkBatch(principal, "kb.read", refs);
        assertThat(first.values().stream().filter(verdict -> verdict == ResourceAuthorizationService.Verdict.GRANT).count()).isEqualTo(199);
        assertThat(first.get("kb:kb-199")).isEqualTo(ResourceAuthorizationService.Verdict.DENY);
        verify(resources, never()).findByPk(anyString(), anyString(), anyString());
        verify(acls, never()).findByResource(anyString(), anyString(), anyString());
        verify(resources, atMost(4)).findByIds(anyString(), anyMap());
        revoked.set(true);
        assertThat(service.checkBatch(principal, "kb.read", refs).values()).containsOnly(ResourceAuthorizationService.Verdict.DENY);
    }
}
