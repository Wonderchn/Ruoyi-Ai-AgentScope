package com.nageoffer.ai.ragent.coding.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.app.CodingConflictException;
import com.nageoffer.ai.ragent.coding.app.CodingHarnessApplicationService;
import com.nageoffer.ai.ragent.coding.app.CodingNotFoundException;
import com.nageoffer.ai.ragent.coding.approval.ApprovalDecision;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.event.HarnessEventSubscription;
import com.nageoffer.ai.ragent.coding.model.HarnessApprovalPolicy;
import com.nageoffer.ai.ragent.coding.model.HarnessBudget;
import com.nageoffer.ai.ragent.coding.model.HarnessEvent;
import com.nageoffer.ai.ragent.coding.model.HarnessInputKind;
import com.nageoffer.ai.ragent.coding.model.HarnessOwner;
import com.nageoffer.ai.ragent.coding.model.HarnessPermissionMode;
import com.nageoffer.ai.ragent.coding.model.HarnessRunState;
import com.nageoffer.ai.ragent.coding.model.HarnessSessionState;
import com.nageoffer.ai.ragent.coding.runtime.CodingModelCatalog;
import com.nageoffer.ai.ragent.coding.sandbox.SandboxCommandException;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.tool.CommandExecutionResult;
import com.nageoffer.ai.ragent.coding.workspace.CodingWorkspaceService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * F20-A coding harness inner controller (class-level {@code /internal/ai/v1/coding}).
 *
 * <p>Public surface: {@code /api/ai/v1/coding/**} (gateway whitelist). Identity only comes from
 * the delegation filter ({@link PrincipalContext}); no {@code @SaCheckPermission} is placed here.
 * GET replies are JSON envelopes plus P2 delivery permit headers; write replies are JSON only
 * (casting a permit on the JSON branch would leak an ACTIVE permit).
 */
@RestController
@RequestMapping("/internal/ai/v1/coding")
public class CodingHarnessController {

    private static final long STREAM_TIMEOUT_MILLIS = 24 * 60 * 60 * 1000L;

    private final CodingHarnessApplicationService service;
    private final HarnessEventHub eventHub;
    private final CodingWorkspaceService workspaceService;
    private final CodingToolRuntime tools;
    private final DeliveryPermits deliveryPermits;
    private final ObjectMapper objectMapper;
    private final CodingModelCatalog modelCatalog;

    public CodingHarnessController(CodingHarnessApplicationService service, HarnessEventHub eventHub,
                                   CodingWorkspaceService workspaceService, CodingToolRuntime tools,
                                   DeliveryPermits deliveryPermits, ObjectMapper objectMapper,
                                   CodingModelCatalog modelCatalog) {
        this.service = service;
        this.eventHub = eventHub;
        this.workspaceService = workspaceService;
        this.tools = tools;
        this.deliveryPermits = deliveryPermits;
        this.objectMapper = objectMapper;
        this.modelCatalog = modelCatalog;
    }

    // ------------------------------------------------------------------ sessions

    public record CreateSessionRequest(String workspacePath, String model,
                                       HarnessPermissionMode permissionMode,
                                       HarnessApprovalPolicy approvalPolicy, String title,
                                       String idempotencyKey, String thinkingLevel) {
    }

    public record PinSessionRequest(Boolean pinned) {
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestBody CreateSessionRequest request) {
        return write("coding.write", () -> {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            return service.createSession(owner(), new CodingHarnessApplicationService
                    .CreateSessionCommand(request.workspacePath(), request.model(),
                    request.permissionMode(), request.approvalPolicy(), request.title(),
                    request.idempotencyKey()));
        });
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions() {
        return read("coding.use", "tenant:coding-sessions",
                () -> service.listSessions(owner()));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable String sessionId) {
        return read("coding.use", "session:" + sessionId,
                () -> service.getSession(owner(), sessionId));
    }

    @PutMapping("/sessions/{sessionId}/pin")
    public ResponseEntity<?> pinSession(@PathVariable String sessionId,
                                        @RequestBody PinSessionRequest request) {
        return write("coding.write", () -> {
            if (request == null || request.pinned() == null) {
                throw new IllegalArgumentException("pinned is required");
            }
            return service.setSessionPinned(owner(), sessionId, request.pinned());
        });
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> deleteSession(@PathVariable String sessionId) {
        return write("coding.write", () -> {
            service.deleteSession(owner(), sessionId);
            return Map.of("sessionId", sessionId, "deleted", true);
        });
    }

    @PostMapping("/sessions/{sessionId}/restore")
    public ResponseEntity<?> restoreSession(@PathVariable String sessionId) {
        return write("coding.write", () -> service.restoreSession(owner(), sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> messages(@PathVariable String sessionId,
                                      @RequestParam(defaultValue = "0") long afterSequence,
                                      @RequestParam(defaultValue = "500") int limit) {
        return read("coding.use", "session:" + sessionId,
                () -> service.readMessages(owner(), sessionId, afterSequence, limit));
    }

    // ------------------------------------------------------------------ runs

    public record CreateRunRequest(String requirement, HarnessBudget budget,
                                   String idempotencyKey) {
    }

    public record QueueInputRequest(HarnessInputKind kind, String content, String idempotencyKey) {
    }

    public record ResolveApprovalRequest(String decisionId, ApprovalDecision decision,
                                         long expectedRevision, String argumentsSha256,
                                         String note) {
    }

    public record ApprovePlanRequest(UUID taskId, long expectedRevision, String expectedHash,
                                     String idempotencyKey) {
    }

    public record RequestPlanRevisionRequest(UUID taskId, long expectedRevision,
                                             String expectedHash, String feedbackId,
                                             String content) {
    }

    @PostMapping("/sessions/{sessionId}/runs")
    public ResponseEntity<?> createRun(@PathVariable String sessionId,
                                       @RequestBody CreateRunRequest request) {
        return write("coding.write", () -> {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            return service.createRun(owner(), sessionId,
                    new CodingHarnessApplicationService.CreateRunCommand(request.requirement(),
                            request.budget(), request.idempotencyKey(), principal().scopes()));
        });
    }

    @GetMapping("/sessions/{sessionId}/runs")
    public ResponseEntity<?> listRuns(@PathVariable String sessionId) {
        return read("coding.use", "session:" + sessionId,
                () -> service.listRuns(owner(), sessionId));
    }

    @GetMapping("/sessions/{sessionId}/runs/{runId}")
    public ResponseEntity<?> getRun(@PathVariable String sessionId, @PathVariable String runId) {
        return read("coding.use", "run:" + runId,
                () -> service.getRun(owner(), sessionId, runId));
    }

    @GetMapping("/sessions/{sessionId}/runs/{runId}/events")
    public ResponseEntity<?> events(@PathVariable String sessionId, @PathVariable String runId,
                                    @RequestParam(defaultValue = "0") long afterSequence,
                                    @RequestParam(defaultValue = "1000") int limit) {
        return read("coding.use", "run:" + runId,
                () -> service.readEvents(owner(), sessionId, runId, afterSequence, limit));
    }

    @GetMapping("/sessions/{sessionId}/runs/{runId}/events/stream")
    public ResponseEntity<?> streamEvents(@PathVariable String sessionId, @PathVariable String runId,
                                          @RequestParam(required = false) Long afterSequence,
                                          @RequestHeader(value = "Last-Event-ID", required = false)
                                          String lastEventId) {
        String requestId = requestId();
        try {
            ExecutionPrincipal principal = principal();
            require(principal, "coding.use");
            HarnessOwner owner = owner(principal);
            service.getRun(owner, sessionId, runId);
            long cursor = resolveCursor(afterSequence, lastEventId);
            SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
            AtomicBoolean closed = new AtomicBoolean(false);
            HarnessEventSubscription subscription = eventHub.subscribe(owner, sessionId, runId,
                    cursor,
                    event -> sendEvent(emitter, event, closed, principal, "coding.use",
                            "run:" + runId),
                    error -> {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                emitter.completeWithError(error);
                            } catch (IllegalStateException ignored) {
                                // The client already disconnected.
                            }
                        }
                    });
            emitter.onCompletion(() -> {
                closed.set(true);
                subscription.close();
            });
            emitter.onTimeout(() -> {
                closed.set(true);
                subscription.close();
            });
            emitter.onError(ignored -> {
                closed.set(true);
                subscription.close();
            });
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("X-Request-Id", requestId).body(emitter);
        } catch (RunApiException error) {
            return fail(error.errorCode(), error.getMessage(), requestId);
        } catch (CodingNotFoundException notFound) {
            return fail(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, notFound.getMessage(),
                    requestId);
        } catch (IllegalArgumentException invalid) {
            return fail(RunErrorCode.BAD_REQUEST, invalid.getMessage(), requestId);
        } catch (RuntimeException failure) {
            return fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    @PostMapping("/sessions/{sessionId}/runs/{runId}/inputs")
    public ResponseEntity<?> queueInput(@PathVariable String sessionId, @PathVariable String runId,
                                        @RequestBody QueueInputRequest request) {
        return write("coding.write", () -> {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            return service.queueInput(owner(), sessionId, runId,
                    new CodingHarnessApplicationService.QueueInputCommand(request.kind(),
                            request.content(), request.idempotencyKey(),
                            principal().scopes()));
        });
    }

    @PostMapping("/sessions/{sessionId}/runs/{runId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String sessionId, @PathVariable String runId) {
        return write("coding.write", () -> service.cancel(owner(), sessionId, runId));
    }

    @PostMapping("/sessions/{sessionId}/runs/{runId}/resume")
    public ResponseEntity<?> resume(@PathVariable String sessionId, @PathVariable String runId) {
        return write("coding.write", () -> service.resume(owner(), sessionId, runId));
    }

    @PostMapping("/sessions/{sessionId}/runs/{runId}/approvals/{approvalId}/resolve")
    public ResponseEntity<?> resolveApproval(@PathVariable String sessionId,
                                             @PathVariable String runId,
                                             @PathVariable String approvalId,
                                             @RequestBody ResolveApprovalRequest request) {
        return write("coding.approve", () -> {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            return service.resolveToolApproval(owner(), sessionId, runId, approvalId,
                    request.decisionId(), request.decision(), request.expectedRevision(),
                    request.argumentsSha256(), request.note());
        });
    }

    @PostMapping("/sessions/{sessionId}/runs/{runId}/plan/approve")
    public ResponseEntity<?> approvePlan(@PathVariable String sessionId, @PathVariable String runId,
                                         @RequestBody ApprovePlanRequest request) {
        return write("coding.approve", () -> {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            return service.approvePlan(owner(), sessionId, runId, request.taskId(),
                    request.expectedRevision(), request.expectedHash(), request.idempotencyKey());
        });
    }

    @PostMapping("/sessions/{sessionId}/runs/{runId}/plan/revision")
    public ResponseEntity<?> requestPlanRevision(@PathVariable String sessionId,
                                                 @PathVariable String runId,
                                                 @RequestBody RequestPlanRevisionRequest request) {
        return write("coding.approve", () -> {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            return service.requestPlanRevision(owner(), sessionId, runId, request.taskId(),
                    request.expectedRevision(), request.expectedHash(), request.feedbackId(),
                    request.content());
        });
    }

    // ------------------------------------------------------------------ workspace/model surface

    public record FileWriteRequest(String sessionId, String workspacePath, String path,
                                   String content) {
    }

    public record CommandRequest(String sessionId, String executable, List<String> argv,
                                 String cwd, Long timeoutMs) {
    }

    public record ChatRequest(String sessionId, String workspacePath, String model,
                              String requirement, String title,
                              HarnessPermissionMode permissionMode,
                              HarnessApprovalPolicy approvalPolicy, String idempotencyKey) {
    }

    @GetMapping("/models")
    public ResponseEntity<?> models() {
        return read("coding.use", "tenant:coding-models", () -> modelCatalog.models());
    }

    @GetMapping("/workspace")
    public ResponseEntity<?> workspace(@RequestParam(required = false) String sessionId,
                                       @RequestParam(required = false) String workspacePath) {
        return read("coding.use", sessionId == null || sessionId.isBlank()
                        ? "tenant:coding-workspace" : "session:" + sessionId,
                () -> workspaceService.list(workspaceFor(owner(), sessionId, workspacePath)));
    }

    @GetMapping("/file")
    public ResponseEntity<?> file(@RequestParam(required = false) String sessionId,
                                  @RequestParam(required = false) String workspacePath,
                                  @RequestParam String path) {
        return read("coding.use", sessionId == null || sessionId.isBlank()
                        ? "tenant:coding-file" : "session:" + sessionId,
                () -> workspaceService.read(workspaceFor(owner(), sessionId, workspacePath), path));
    }

    @PutMapping("/file")
    public ResponseEntity<?> writeFile(@RequestBody FileWriteRequest request) {
        return write("coding.write", () -> {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            return workspaceService.write(
                    workspaceFor(owner(), request.sessionId(), request.workspacePath()),
                    request.path(), request.content());
        });
    }

    @PostMapping("/command")
    public ResponseEntity<?> command(@RequestBody CommandRequest request) {
        String requestId = requestId();
        try {
            if (request == null || request.executable() == null || request.executable().isBlank()) {
                throw new IllegalArgumentException("executable is required");
            }
            ExecutionPrincipal principal = principal();
            require(principal, "coding.command");
            Path workspace = workspaceFor(owner(principal), request.sessionId(), null);
            CommandExecutionResult result = tools.executeCommand(workspace, request.executable(),
                    request.argv(), request.cwd(), request.timeoutMs());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("executable", result.executable());
            data.put("argv", result.argv());
            data.put("exitCode", result.exitCode());
            data.put("stdout", result.stdout());
            data.put("stderr", result.stderr());
            data.put("timedOut", result.timedOut());
            return ok(data, requestId);
        } catch (SandboxCommandException sandbox) {
            return fail(HttpStatus.CONFLICT, sandbox.code(), sandbox.getMessage(), requestId);
        } catch (RunApiException error) {
            return fail(error.errorCode(), error.getMessage(), requestId);
        } catch (CodingNotFoundException notFound) {
            return fail(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, notFound.getMessage(), requestId);
        } catch (IllegalArgumentException invalid) {
            return fail(RunErrorCode.BAD_REQUEST, invalid.getMessage(), requestId);
        } catch (RuntimeException failure) {
            return fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    /** SSE chat: creates (or reuses) a session, creates a run, and streams its durable events. */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        String requestId = requestId();
        try {
            if (request == null || request.requirement() == null || request.requirement().isBlank()) {
                throw new IllegalArgumentException("requirement is required");
            }
            ExecutionPrincipal principal = principal();
            require(principal, "coding.write");
            HarnessOwner owner = owner(principal);
            String sessionId = request.sessionId();
            if (sessionId == null || sessionId.isBlank()) {
                if (request.model() == null || request.model().isBlank()) {
                    throw new IllegalArgumentException("model is required for a new chat session");
                }
                HarnessSessionState session = service.createSession(owner,
                        new CodingHarnessApplicationService.CreateSessionCommand(
                                request.workspacePath(), request.model(), request.permissionMode(),
                                request.approvalPolicy(), request.title(),
                                request.idempotencyKey() == null
                                        ? "chat-session:" + UUID.randomUUID()
                                        : "chat-session:" + request.idempotencyKey()));
                sessionId = session.sessionId();
            }
            String runKey = request.idempotencyKey() == null
                    ? "chat-run:" + UUID.randomUUID() : "chat-run:" + request.idempotencyKey();
            HarnessRunState run = service.createRun(owner, sessionId,
                    new CodingHarnessApplicationService.CreateRunCommand(request.requirement(),
                            null, runKey, principal.scopes()));
            SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
            AtomicBoolean closed = new AtomicBoolean(false);
            String chatResource = "run:" + run.runId();
            HarnessEventSubscription subscription = eventHub.subscribe(owner, sessionId,
                    run.runId(), 0,
                    event -> sendEvent(emitter, event, closed, principal, "coding.write", chatResource),
                    error -> {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                emitter.completeWithError(error);
                            } catch (IllegalStateException ignored) {
                                // The client already disconnected.
                            }
                        }
                    });
            emitter.onCompletion(() -> {
                closed.set(true);
                subscription.close();
            });
            emitter.onTimeout(() -> {
                closed.set(true);
                subscription.close();
            });
            emitter.onError(ignored -> {
                closed.set(true);
                subscription.close();
            });
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("X-Request-Id", requestId).body(emitter);
        } catch (RunApiException error) {
            return fail(error.errorCode(), error.getMessage(), requestId);
        } catch (CodingNotFoundException notFound) {
            return fail(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, notFound.getMessage(), requestId);
        } catch (CodingConflictException conflict) {
            return fail(RunErrorCode.RUN_STATE_CONFLICT, conflict.getMessage(), requestId);
        } catch (IllegalArgumentException invalid) {
            return fail(RunErrorCode.BAD_REQUEST, invalid.getMessage(), requestId);
        } catch (RuntimeException failure) {
            return fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<?> read(String action, String resource, ReadSupplier supplier) {
        String requestId = requestId();
        DeliveryPermits.Permit permit = null;
        try {
            ExecutionPrincipal principal = principal();
            require(principal, action);
            permit = deliveryPermits.enter(principal, action, resource);
            return ResponseEntity.ok().header("X-Request-Id", requestId)
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(supplier.get()));
        } catch (RunApiException error) {
            closeQuietly(permit);
            return fail(error.errorCode(), error.getMessage(), requestId);
        } catch (CodingNotFoundException notFound) {
            closeQuietly(permit);
            return fail(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, notFound.getMessage(), requestId);
        } catch (IllegalArgumentException invalid) {
            closeQuietly(permit);
            return fail(RunErrorCode.BAD_REQUEST, invalid.getMessage(), requestId);
        } catch (RuntimeException failure) {
            closeQuietly(permit);
            return fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    private ResponseEntity<?> write(String action, WriteSupplier supplier) {
        String requestId = requestId();
        try {
            ExecutionPrincipal principal = principal();
            require(principal, action);
            return ok(supplier.get(), requestId);
        } catch (RunApiException error) {
            return fail(error.errorCode(), error.getMessage(), requestId);
        } catch (CodingNotFoundException notFound) {
            return fail(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, notFound.getMessage(), requestId);
        } catch (CodingConflictException conflict) {
            return fail(RunErrorCode.RUN_STATE_CONFLICT, conflict.getMessage(), requestId);
        } catch (IllegalArgumentException invalid) {
            return fail(RunErrorCode.BAD_REQUEST, invalid.getMessage(), requestId);
        } catch (RuntimeException failure) {
            return fail(RunErrorCode.INTERNAL_ERROR, requestId);
        }
    }

    private ResponseEntity<?> ok(Object data, String requestId) {
        return ResponseEntity.ok().header("X-Request-Id", requestId)
                .body(ApiEnvelope.ok(data));
    }

    private ResponseEntity<?> fail(RunErrorCode code, String message, String requestId) {
        return fail(code.status(), code.name(), message, requestId);
    }

    private ResponseEntity<?> fail(RunErrorCode code, String requestId) {
        return fail(code.status(), code.name(), code.message(), requestId);
    }

    private ResponseEntity<?> fail(HttpStatus status, String errorCode, String message,
                                   String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", errorCode);
        data.put("retryable", status.is5xxServerError());
        data.put("requestId", requestId);
        return ResponseEntity.status(status).header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiEnvelope<>(status.value(), message, data));
    }

    private void sendEvent(SseEmitter emitter, HarnessEvent event, AtomicBoolean closed,
                           ExecutionPrincipal principal, String action, String resource) {
        if (closed.get()) {
            return;
        }
        DeliveryPermits.Permit permit = null;
        try {
            // B1：每一帧都必须携带 `: ai-delivery <permit> <operation>`；本地/HTTP 网关对
            // 裸 id/event/data 帧 fail-closed（"unprotected stream frame"）。
            permit = deliveryPermits.enter(principal, action, resource);
            String json = objectMapper.writeValueAsString(event);
            emitter.send(CodingSseFrames.event(permit.permitId(), permit.operationId(),
                    event.sequence(), event.type(), json));
            if (event.isTerminalType() && closed.compareAndSet(false, true)) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException error) {
            if (closed.compareAndSet(false, true)) {
                try {
                    emitter.completeWithError(error);
                } catch (IllegalStateException ignored) {
                    // The response is already complete.
                }
            }
        } catch (RuntimeException error) {
            // Missing permit port / serialization failure: never emit an unprotected frame.
            if (closed.compareAndSet(false, true)) {
                try {
                    emitter.completeWithError(error);
                } catch (IllegalStateException ignored) {
                    // The response is already complete.
                }
            }
        }
    }

    private long resolveCursor(Long queryCursor, String lastEventId) {
        long cursor = queryCursor == null ? 0 : queryCursor;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                cursor = Math.max(cursor, Long.parseLong(lastEventId));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                        "Last-Event-ID must be a non-negative sequence");
            }
        }
        if (cursor < 0) {
            throw new IllegalArgumentException("Event cursor cannot be negative");
        }
        return cursor;
    }

    private ExecutionPrincipal principal() {
        if (!PrincipalContext.hasPrincipal()) {
            throw new RunApiException(RunErrorCode.AUTH_REQUIRED);
        }
        return PrincipalContext.require();
    }

    private HarnessOwner owner() {
        return owner(principal());
    }

    private HarnessOwner owner(ExecutionPrincipal principal) {
        try {
            return new HarnessOwner(principal.tenantId(), Long.parseLong(principal.userId()));
        } catch (NumberFormatException invalid) {
            throw new RunApiException(RunErrorCode.MEMBERSHIP_INVALID);
        }
    }

    private void require(ExecutionPrincipal principal, String action) {
        if (!principal.hasScope(action)) {
            throw new RunApiException(RunErrorCode.FORBIDDEN);
        }
    }

    private Path workspaceFor(HarnessOwner owner, String sessionId, String workspacePath) {
        if (sessionId != null && !sessionId.isBlank()) {
            HarnessSessionState session = service.getSession(owner, sessionId);
            return Path.of(session.workspace());
        }
        return workspaceService.resolveHarnessRoot(owner, workspacePath);
    }

    private static void closeQuietly(DeliveryPermits.Permit permit) {
        if (permit != null) {
            try {
                permit.close();
            } catch (RuntimeException ignored) {
                // Closing failure must not mask the original rejection.
            }
        }
    }

    private String requestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "");
    }

    @FunctionalInterface
    private interface ReadSupplier {
        Object get();
    }

    @FunctionalInterface
    private interface WriteSupplier {
        Object get();
    }

    @ExceptionHandler(RunApiException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleRunApi(RunApiException error) {
        String requestId = requestId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", error.errorCode().name());
        data.put("retryable", error.errorCode().retryable());
        data.put("requestId", requestId);
        return ResponseEntity.status(error.errorCode().status())
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiEnvelope<>(error.errorCode().status().value(), error.getMessage(),
                        data));
    }
}
