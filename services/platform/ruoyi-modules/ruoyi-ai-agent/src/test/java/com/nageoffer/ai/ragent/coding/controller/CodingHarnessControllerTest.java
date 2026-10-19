package com.nageoffer.ai.ragent.coding.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.app.CodingHarnessApplicationService;
import com.nageoffer.ai.ragent.coding.app.CodingModelGateway;
import com.nageoffer.ai.ragent.coding.app.CodingRunExecutor;
import com.nageoffer.ai.ragent.coding.app.CodingRunProcessor;
import com.nageoffer.ai.ragent.coding.app.ModelTurn;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy;
import com.nageoffer.ai.ragent.coding.model.HarnessPermissionMode;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.runtime.CodingModelCatalog;
import com.nageoffer.ai.ragent.coding.sandbox.DockerSandboxConfig;
import com.nageoffer.ai.ragent.coding.store.FileHarnessStore;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.workspace.CodingWorkspaceService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F20-A 控制器判据：scope 门、属主隔离、ApiEnvelope、GET 交付回执、文件边界、
 * 未配置沙箱的命令零副作用拒绝。
 */
@Tag("dev")
class CodingHarnessControllerTest {

    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private CodingHarnessController controller;

    @BeforeEach
    void setUp() throws Exception {
        FileHarnessStore store = new FileHarnessStore(mapper, temp.resolve("state"));
        CodingWorkspaceService workspace = new CodingWorkspaceService(temp.resolve("ws"), null);
        HarnessEventHub hub = new HarnessEventHub(store, Runnable::run);
        CodingToolRuntime tools = new CodingToolRuntime(workspace, DockerSandboxConfig.UNCONFIGURED, false);
        CodingModelGateway gateway = request -> ModelTurn.content("ok");
        CodingRunProcessor processor = new CodingRunProcessor(store, hub, gateway, tools, mapper);
        CodingRunExecutor executor = new CodingRunExecutor(Runnable::run, processor);
        CodingHarnessApplicationService service = new CodingHarnessApplicationService(
                store, hub, workspace, executor, tools);
        controller = new CodingHarnessController(service, hub, workspace, tools,
                permits(), mapper, new CodingModelCatalog(List.of("deepseek-flash")));
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    private ExecutionPrincipal principal(Set<String> scopes, String tenant, String user) {
        return new ExecutionPrincipal(tenant, user, "platform:" + tenant + ":" + user, 1, 1,
                scopes, "jti", "test", 1, 9_999_999_999L);
    }

    private static DeliveryPermits permits() {
        AtomicInteger counter = new AtomicInteger();
        RevocationGuard guard = new RevocationGuard() {
            @Override
            public PermitGrant acquire(PermitRequest request) {
                return new PermitGrant("p-" + counter.incrementAndGet(), 1);
            }

            @Override
            public void release(String permitId, String operationId) {
            }

            @Override
            public BarrierState barrierState(String tenantId) {
                return BarrierState.NO_ROW;
            }

            @Override
            public long activePermitCount(String tenantId) {
                return 0;
            }

            @Override
            public void setBarrierState(String tenantId, BarrierState state, String barrierId,
                                        Integer targetAclVersion, String reason) {
            }
        };
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("guard", guard);
        return new DeliveryPermits(factory.getBeanProvider(RevocationGuard.class));
    }

    private HarnessSessionState createSession(String tenant, String user, Set<String> scopes) {
        PrincipalContext.set(principal(scopes, tenant, user));
        try {
            ResponseEntity<?> response = controller.createSession(new CodingHarnessController.CreateSessionRequest(
                    null, "deepseek-flash", HarnessPermissionMode.WORKSPACE_WRITE,
                    HarnessApprovalPolicy.ON_REQUEST, "s", "ctrl-key", null));
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            return (HarnessSessionState) ((ApiEnvelope<?>) response.getBody()).data();
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void missingPrincipalIsUnauthorized() {
        ResponseEntity<?> response = controller.listSessions();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(((java.util.Map<?, ?>) ((ApiEnvelope<?>) response.getBody()).data())
                .get("errorCode")).isEqualTo(RunErrorCode.AUTH_REQUIRED.name());
    }

    @Test
    void useOnlyCannotWriteButCanRead() {
        HarnessSessionState session = createSession("T1", "42",
                Set.of("coding.use", "coding.write"));
        PrincipalContext.set(principal(Set.of("coding.use"), "T1", "42"));
        try {
            ResponseEntity<?> denied = controller.createSession(new CodingHarnessController.CreateSessionRequest(
                    null, "deepseek-flash", HarnessPermissionMode.READ_ONLY,
                    HarnessApprovalPolicy.ON_REQUEST, "x", "k2", null));
            assertThat(denied.getStatusCode().value()).isEqualTo(403);
            ResponseEntity<?> list = controller.listSessions();
            assertThat(list.getStatusCode().value()).isEqualTo(200);
            assertThat(list.getHeaders().getFirst("X-AI-Delivery-Permit")).isNotBlank();
            assertThat(((ApiEnvelope<?>) list.getBody()).code()).isEqualTo(200);
            assertThat((List<?>) ((ApiEnvelope<?>) list.getBody()).data()).hasSize(1);
            assertThat(session.sessionId()).isNotBlank();
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void fileWriteAndReadStayInsideWorkspaceAndCarryPermit() throws Exception {
        HarnessSessionState session = createSession("T1", "42", Set.of("coding.use", "coding.write"));
        PrincipalContext.set(principal(Set.of("coding.use", "coding.write"), "T1", "42"));
        try {
            ResponseEntity<?> write = controller.writeFile(new CodingHarnessController.FileWriteRequest(
                    session.sessionId(), null, "src/a.txt", "hello"));
            assertThat(write.getStatusCode().value()).isEqualTo(200);

            ResponseEntity<?> read = controller.file(session.sessionId(), null, "src/a.txt");
            assertThat(read.getStatusCode().value()).isEqualTo(200);
            assertThat(read.getHeaders().getFirst("X-AI-Delivery-Permit")).isNotBlank();
            assertThat(read.getHeaders().getFirst("X-AI-Delivery-Operation")).isNotBlank();
            CodingWorkspaceService.FileContent content =
                    (CodingWorkspaceService.FileContent) ((ApiEnvelope<?>) read.getBody()).data();
            assertThat(content.content()).isEqualTo("hello");

            ResponseEntity<?> escaped = controller.file(session.sessionId(), null, "../outside.txt");
            assertThat(escaped.getStatusCode().value()).isEqualTo(400);
            assertThat(Files.exists(Path.of(session.workspace()).getParent().resolve("outside.txt")))
                    .isFalse();
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void commandWithoutSandboxConfigurationFailsClosedWithZeroSideEffects() throws Exception {
        HarnessSessionState session = createSession("T1", "42", Set.of("coding.use", "coding.write", "coding.command"));
        PrincipalContext.set(principal(
                Set.of("coding.use", "coding.write", "coding.command"), "T1", "42"));
        try {
            String marker = "coding-command-marker-" + System.nanoTime();
            ResponseEntity<?> response = controller.command(new CodingHarnessController.CommandRequest(
                    session.sessionId(), "sh", List.of("-c", "touch " + marker), null, 1000L));
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            ApiEnvelope<?> body = (ApiEnvelope<?>) response.getBody();
            assertThat(((java.util.Map<?, ?>) body.data()).get("errorCode"))
                    .isEqualTo("SANDBOX_NOT_CONFIGURED");
            assertThat(Files.exists(Path.of(session.workspace()).resolve(marker))).isFalse();
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void foreignTenantSeesNotFound() {
        HarnessSessionState session = createSession("T1", "42", Set.of("coding.use", "coding.write"));
        PrincipalContext.set(principal(Set.of("coding.use"), "T2", "43"));
        try {
            ResponseEntity<?> response = controller.getSession(session.sessionId());
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void modelsAndWorkspaceAreReadableWithUseScope() {
        HarnessSessionState session = createSession("T1", "42", Set.of("coding.use", "coding.write"));
        PrincipalContext.set(principal(Set.of("coding.use"), "T1", "42"));
        try {
            ResponseEntity<?> models = controller.models();
            assertThat(models.getStatusCode().value()).isEqualTo(200);
            assertThat(((List<?>) ((ApiEnvelope<?>) models.getBody()).data())
                    .stream().map(String::valueOf).toList()).contains("deepseek-flash");

            ResponseEntity<?> workspace = controller.workspace(session.sessionId(), null);
            assertThat(workspace.getStatusCode().value()).isEqualTo(200);
            assertThat(workspace.getHeaders().getFirst("X-AI-Delivery-Permit")).isNotBlank();
        } finally {
            PrincipalContext.clear();
        }
    }

    private com.nageoffer.ai.ragent.coding.model.HarnessRunState createRun(String sessionId) {
        PrincipalContext.set(principal(Set.of("coding.use", "coding.write"), "T1", "42"));
        try {
            ResponseEntity<?> response = controller.createRun(sessionId,
                    new CodingHarnessController.CreateRunRequest("do it", null, "run-key"));
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            return (com.nageoffer.ai.ragent.coding.model.HarnessRunState)
                    ((ApiEnvelope<?>) response.getBody()).data();
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void chatWithInvalidBodyReturnsAJsonEnvelope() {
        PrincipalContext.set(principal(Set.of("coding.use", "coding.write"), "T1", "42"));
        try {
            ResponseEntity<?> response = controller.chat(new CodingHarnessController.ChatRequest(
                    null, null, "deepseek-flash", "", null, null, null, null));
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getHeaders().getContentType())
                    .isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
            ApiEnvelope<?> body = (ApiEnvelope<?>) response.getBody();
            assertThat(body).isNotNull();
            assertThat(((java.util.Map<?, ?>) body.data()).get("errorCode"))
                    .isEqualTo(RunErrorCode.BAD_REQUEST.name());
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void invalidStreamRequestsReturn404And400InsteadOf503() {
        HarnessSessionState session = createSession("T1", "42", Set.of("coding.use", "coding.write"));
        var run = createRun(session.sessionId());

        PrincipalContext.set(principal(Set.of("coding.use"), "T2", "43"));
        try {
            ResponseEntity<?> foreign = controller.streamEvents(session.sessionId(), run.runId(),
                    null, null);
            assertThat(foreign.getStatusCode().value()).isEqualTo(404);
        } finally {
            PrincipalContext.clear();
        }

        PrincipalContext.set(principal(Set.of("coding.use"), "T1", "42"));
        try {
            ResponseEntity<?> negative = controller.streamEvents(session.sessionId(), run.runId(),
                    -1L, null);
            assertThat(negative.getStatusCode().value()).isEqualTo(400);
            ResponseEntity<?> badLastEvent = controller.streamEvents(session.sessionId(),
                    run.runId(), null, "not-a-number");
            assertThat(badLastEvent.getStatusCode().value()).isEqualTo(400);
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void restoreUnknownSessionIs404() {
        PrincipalContext.set(principal(Set.of("coding.use", "coding.write"), "T1", "42"));
        try {
            ResponseEntity<?> response = controller.restoreSession("s_missing");
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        } finally {
            PrincipalContext.clear();
        }
    }
}
