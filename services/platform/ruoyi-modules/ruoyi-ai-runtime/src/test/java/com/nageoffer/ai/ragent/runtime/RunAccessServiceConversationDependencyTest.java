package com.nageoffer.ai.ragent.runtime;

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.usage.PlatformFactsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Tag("dev")
class RunAccessServiceConversationDependencyTest {
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p=mock(ObjectProvider.class); when(p.getIfAvailable()).thenReturn(value); return p;
    }
    // F16 作者修正（2026-10-15）：原夹具 userId="u1"/membershipId="m1" 违反 ExecutionPrincipal
    // 的构造契约（userId 必须十进制字符串、membershipId 必须 canonical platform:<tenant>:<user>），
    // 三例在构造期即 error，无法充当行为红基线。改为合法主体，断言一字未动。
    private final ExecutionPrincipal principal=new ExecutionPrincipal("t1","1001","platform:t1:1001",1,1,Set.of("kb.read"),"j","platform",0,9999999999L);
    private final RunRecord run=new RunRecord("t1","r1","platform:t1:1001","1001","rag.chat","SUCCEEDED","h","k","{}","{}","v",1,1,"[]",1,1,1,1,"w",null,"{}",null,null,null,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH);
    private final DocumentPort documents=mock(DocumentPort.class);
    private RunAccessService service() {
        RunLedgerDao ledger=mock(RunLedgerDao.class);
        when(ledger.latestCompletedStep("t1","r1","retrieve")).thenReturn(Optional.of(new RunLedgerDao.StepRow("retrieve",1,1,"retrieve","COMPLETED","{\"chunks\":[{\"docId\":\"doc1\",\"versionId\":\"ver1\"}]}",null,"{}",1,Instant.EPOCH,Instant.EPOCH)));
        AiResourceAuthorizationService resources=mock(AiResourceAuthorizationService.class);
        when(resources.check(any(),eq("document.read"),eq("doc:doc1"))).thenReturn(ResourceAuthorizationService.Verdict.GRANT);
        return new RunAccessService(provider(resources),provider(mock(PlatformFactsClient.class)),provider(ledger),provider(documents));
    }
    @Test void dependencyFailureIsVisibleAndNeverMasqueradesAsMissingHistory() {
        when(documents.findDocument("t1","doc1")).thenThrow(new IllegalStateException("dependency offline"));
        assertEquals(RunErrorCode.AUTHORIZATION_UNAVAILABLE,assertThrows(RunApiException.class,()->service().visible(principal,run)).errorCode());
    }
    @Test void actuallyMissingOrRepublishedDocumentRemainsPrivate404() {
        when(documents.findDocument("t1","doc1")).thenReturn(Optional.empty());
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN,assertThrows(RunApiException.class,()->service().visible(principal,run)).errorCode());
        when(documents.findDocument("t1","doc1")).thenReturn(Optional.of(new DocumentPort.DocumentRow("doc1","kb1","title","m1","ver2",null,null)));
        assertEquals(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN,assertThrows(RunApiException.class,()->service().visible(principal,run)).errorCode());
    }
    @Test void unchangedPublishedDocumentRemainsVisible() {
        when(documents.findDocument("t1","doc1")).thenReturn(Optional.of(new DocumentPort.DocumentRow("doc1","kb1","title","m1","ver1",null,null)));
        assertDoesNotThrow(()->service().visible(principal,run));
    }
}
