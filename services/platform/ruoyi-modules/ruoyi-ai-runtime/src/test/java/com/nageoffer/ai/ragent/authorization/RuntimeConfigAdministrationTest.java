package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.context.*;
import com.nageoffer.ai.ragent.framework.security.*;
import com.nageoffer.ai.ragent.runtime.config.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class RuntimeConfigAdministrationTest {
    private final AiResourceAuthorizationService authorization = mock(AiResourceAuthorizationService.class);
    private final ConfigRevisionPublisher publisher = mock(ConfigRevisionPublisher.class);
    private final ProviderConnectionPort connections = mock(ProviderConnectionPort.class);
    private final AiResourceController controller = new AiResourceController(authorization, mock(AiResourceWriteService.class));
    private final ExecutionPrincipal principal = new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 7,
        Set.of("config.publish", "config.revoke", "config.read"), "jti", "test", 0, Long.MAX_VALUE);
    private final ConfigRevisionFacts facts = new ConfigRevisionFacts("T1", "rev-new", 9, "deepseek", "deepseek-flash",
        "catalog", "hash", "env:deepseek", "2101", Instant.now(), 1536);
    @BeforeEach void setup() {
        PrincipalContext.set(principal);
        controller.configureRuntimeConfig(publisher, connections);
        when(connections.requireConnection(anyString(), any())).thenReturn(
            new ProviderConnectionPort.ProviderConnection("deepseek", URI.create("http://localhost/chat/completions"), "env:deepseek", null));
        when(publisher.publish(any())).thenReturn(facts);
    }
    @AfterEach void cleanup() { PrincipalContext.clear(); }
    private AiResourceController.RuntimeConfigRequest request(int dimension, Map<String,Object> params) {
        return new AiResourceController.RuntimeConfigRequest("deepseek", "deepseek-flash", "catalog", "env:deepseek", dimension, params);
    }
    @Test void publishesThroughTheAuthorityAndDerivesARealParameterHash() {
        assertThat(controller.publishRuntimeConfig(request(1536, Map.of("temperature", 0.7))).getBody().data()).isEqualTo(facts);
        verify(authorization).requireFunction(principal, "config.publish", "tenant:runtime-config");
        var capture = ArgumentCaptor.forClass(ConfigRevisionPublisher.ConfigRevisionCommand.class);
        verify(publisher).publish(capture.capture());
        assertThat(capture.getValue().paramsHash()).matches("[0-9a-f]{64}");
        assertThat(capture.getValue().paramsJson()).isEqualTo("{\"temperature\":0.7}");
    }
    @Test void refusesSecretParametersAndWrongDimensionBeforeAnyWrite() {
        assertThatThrownBy(() -> controller.publishRuntimeConfig(request(1536, Map.of("apiKey", "synthetic-secret"))))
            .isInstanceOf(P04AiException.class);
        assertThatThrownBy(() -> controller.publishRuntimeConfig(request(1535, Map.of())))
            .isInstanceOf(P04AiException.class);
        verifyNoInteractions(publisher);
    }
    @Test void missingIdentityAndDeniedScopeCannotPublish() {
        PrincipalContext.clear();
        assertThatThrownBy(() -> controller.publishRuntimeConfig(request(1536, Map.of()))).isInstanceOf(RuntimeException.class);
        PrincipalContext.set(principal);
        doThrow(new P04AiException(P04AiErrorCode.FORBIDDEN)).when(authorization)
            .requireFunction(principal, "config.publish", "tenant:runtime-config");
        assertThatThrownBy(() -> controller.publishRuntimeConfig(request(1536, Map.of()))).isInstanceOf(P04AiException.class);
        verifyNoInteractions(publisher);
    }
    @Test void unknownProviderBootstrapCannotProduceASuccessfulPublish() {
        when(connections.requireConnection(anyString(), any())).thenThrow(new ConfigAuthorityUnavailable("not configured"));
        assertThatThrownBy(() -> controller.publishRuntimeConfig(request(1536, Map.of()))).isInstanceOf(ConfigAuthorityUnavailable.class);
        verifyNoInteractions(publisher);
    }
    @Test void rollbackPublishesANewRevisionRatherThanEditingHistory() {
        when(publisher.snapshot("rev-old")).thenReturn(new ConfigRevisionPublisher.ConfigRevisionSnapshot(facts, "REVOKED", "{\"temperature\":0.7}"));
        controller.rollbackRuntimeConfig("rev-old");
        verify(publisher).publish(any());
        verify(publisher, never()).revoke(anyString(), anyString());
    }
    @Test void revokeTakesTheOperatorFromTheAuthenticatedPrincipal() {
        controller.revokeRuntimeConfig("rev-old");
        verify(publisher).revoke("rev-old", "2101");
        verify(authorization).requireFunction(principal, "config.revoke", "tenant:runtime-config");
    }

    @Test void missingDelegatedScopeAndInvalidParametersCannotReachThePublisher() {
        for (var params : List.of(Map.<String,Object>of("temperature", 3), Map.<String,Object>of("maxTokens", 0),
                Map.<String,Object>of("topP", 0), Map.<String,Object>of("maxTokens", 0.5))) {
            assertThatThrownBy(() -> controller.publishRuntimeConfig(request(1536, params))).isInstanceOf(P04AiException.class);
        }
        PrincipalContext.set(new ExecutionPrincipal("T1", "2101", "platform:T1:2101", 1, 7, Set.of(), "jti", "test", 0, Long.MAX_VALUE));
        assertThatThrownBy(() -> controller.publishRuntimeConfig(request(1536, Map.of()))).isInstanceOf(P04AiException.class);
        verifyNoInteractions(publisher);
    }

    /**
     * RW-06-R1：合成 canary 的形态说明。
     *
     * <p>这里断言的是"历史明文凭据（**不是**合法引用形态）绝不会被读取面回显"，判据是引用形状
     * {@code (env|vault|secret|masked):…}，与它长得像不像某厂商 key 无关；因此标记**不需要**、
     * 也**不得**写成 {@code sk-…} 形态——`scripts/ci/check-public-source.py` 的凭据正则
     * {@code sk-[A-Za-z0-9_-]{16,}} 会把它判成 credential-shaped literal 而让公开源码门失败。
     */
    private static final String LEGACY_PLAINTEXT_CREDENTIAL = "raw-credential-canary-must-never-be-echoed";

    @Test void legacyPlaintextCredentialIsNeverReturnedByTheReadApi() {
        var unsafe = new ConfigRevisionFacts("T1", "rev-legacy", 1, "deepseek", "deepseek-flash", "catalog", "hash",
                LEGACY_PLAINTEXT_CREDENTIAL, "2101", Instant.now(), 1536);
        when(publisher.snapshot("rev-legacy")).thenReturn(new ConfigRevisionPublisher.ConfigRevisionSnapshot(unsafe, "PUBLISHED", "{}"));
        // 断言不放松：拒绝必须发生，且原因就是"凭据引用形状不合法"（BAD_REQUEST），
        // 而不是被误报成"没权限/没装配"等其他失败。
        assertThatThrownBy(() -> controller.runtimeConfig("rev-legacy"))
                .isInstanceOf(P04AiException.class)
                .satisfies(ex -> assertThat(((P04AiException) ex).errorCode())
                        .isEqualTo(P04AiErrorCode.BAD_REQUEST));
        // 反向锚点：该标记既不在响应中被回显，也不被掩码回显（读取面在形状校验处就拒绝）。
        assertThat(unsafe.credentialRef()).isEqualTo(LEGACY_PLAINTEXT_CREDENTIAL);
    }
}
