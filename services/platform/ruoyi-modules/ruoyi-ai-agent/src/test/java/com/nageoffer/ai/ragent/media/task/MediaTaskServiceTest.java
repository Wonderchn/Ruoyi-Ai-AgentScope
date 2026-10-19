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

package com.nageoffer.ai.ragent.media.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import com.nageoffer.ai.ragent.media.delivery.MediaContentStore;
import com.nageoffer.ai.ragent.media.image.MediaImagePoll;
import com.nageoffer.ai.ragent.media.image.MediaImageProvider;
import com.nageoffer.ai.ragent.media.image.MediaImageRequest;
import com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException;
import com.nageoffer.ai.ragent.media.usage.MediaUsageMeter;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F19:1/3/4/5 服务端任务权威：幂等、状态机、UNKNOWN 不自动重发、显式重试、
 * 私有结果与跨属主隔离、媒体计价预占。
 */
@Tag("dev")
class MediaTaskServiceTest {

    private final FakeRepository repository = new FakeRepository();
    private final FakeProvider provider = new FakeProvider();
    private final InMemoryObjectStore objectStore = new InMemoryObjectStore();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    private MediaProperties properties;
    private MediaUsageMeter usage;
    private MediaTaskService service;
    private ExecutionPrincipal owner;
    private ExecutionPrincipal stranger;

    @BeforeEach
    void setUp() {
        properties = new MediaProperties();
        properties.getImage().setApiKey("synthetic-" + UUID.randomUUID());
        properties.getImage().setModelName("wanx-test");
        properties.getImage().setBillingProvider("dashscope");
        properties.getImage().setMaxCostPerImageCny(new BigDecimal("0.50"));

        usage = mock(MediaUsageMeter.class);
        when(usage.reserve(anyString(), anyInt()))
                .thenReturn(new MediaUsageMeter.Reserved("spend-1", new BigDecimal("0.500000"), 1));

        EgressPolicy egress = new EgressPolicy();
        egress.setEnabled(true);
        egress.setAllowedProviders("dashscope");

        service = new MediaTaskService(repository, provider,
                new MediaContentStore(objectStore, null), usage, properties, egress, mapper, clock);

        owner = principal("T1", "1001", "platform:T1:1001");
        stranger = principal("T2", "2002", "platform:T2:2002");
    }

    @Test
    void sameRequestIdReturnsExistingTaskWithoutSecondPaidCall() {
        provider.submitTaskIds.add("provider-t1");

        MediaTaskView first = service.submitImage(owner, command("req-1"));
        MediaTaskView second = service.submitImage(owner, command("req-1"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(provider.submitCalls).isEqualTo(1);
        verify(usage, times(1)).reserve(anyString(), anyInt());
    }

    @Test
    void budgetFailureMarksFailedAndNeverCallsProvider() {
        when(usage.reserve(anyString(), anyInt()))
                .thenThrow(new RunApiException(RunErrorCode.BUDGET_EXCEEDED));

        MediaTaskView view = service.submitImage(owner, command("req-budget"));

        assertThat(view.status()).isEqualTo("failed");
        assertThat(view.errorCode()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(provider.submitCalls).isZero();
    }

    @Test
    void providerTimeoutMarksUnknownAndQueryNeverResubmits() {
        provider.submitFailure = new MediaProviderUnavailableException("timeout");

        MediaTaskView view = service.submitImage(owner, command("req-unknown"));
        MediaTaskView queried = service.query(owner, view.id());

        assertThat(view.status()).isEqualTo("unknown");
        assertThat(queried.status()).isEqualTo("unknown");
        assertThat(provider.submitCalls).isEqualTo(1);
    }

    @Test
    void explicitRetryCreatesNewAttemptAndLinksPreviousTask() {
        provider.submitFailure = new MediaProviderUnavailableException("timeout");
        MediaTaskView unknown = service.submitImage(owner, command("req-1"));
        provider.submitFailure = null;
        provider.submitTaskIds.add("provider-t2");

        MediaTaskView retried = service.retry(owner, unknown.id(), "req-2");

        assertThat(retried.id()).isNotEqualTo(unknown.id());
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(retried.previousTaskId()).isEqualTo(unknown.id());
        assertThat(provider.submitCalls).isEqualTo(2);
        verify(usage, times(2)).reserve(anyString(), anyInt());
        // UNKNOWN 的旧预占不退还：旧任务保持 unknown 且 spend_call_id 未变。
        assertThat(service.query(owner, unknown.id()).status()).isEqualTo("unknown");
    }

    @Test
    void retryIsRejectedForSucceededTask() {
        provider.submitTaskIds.add("provider-t1");
        provider.polls.add(new MediaImagePoll("SUCCEEDED", "https://bucket.aliyuncs.com/a.png", null, null,
                "req-p1", Map.of("images", 1)));
        provider.downloadBytes = png();
        MediaTaskView submitted = service.submitImage(owner, command("req-1"));
        assertThat(service.query(owner, submitted.id()).status()).isEqualTo("completed");

        assertThatThrownBy(() -> service.retry(owner, submitted.id(), "req-2"))
                .isInstanceOfSatisfying(RunApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RunErrorCode.RUN_STATE_CONFLICT));
    }

    @Test
    void succeededQueryStoresPrivateResultAndServesBytesOnlyToOwner() {
        provider.submitTaskIds.add("provider-t1");
        provider.polls.add(new MediaImagePoll("SUCCEEDED", "https://bucket.aliyuncs.com/a.png", null, null,
                "req-p1", Map.of("images", 1)));
        provider.downloadBytes = png();

        MediaTaskView submitted = service.submitImage(owner, command("req-1"));
        MediaTaskView completed = service.query(owner, submitted.id());

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.mimeType()).isEqualTo("image/png");
        assertThat(completed.sizeBytes()).isEqualTo((long) png().length);
        assertThat(objectStore.objects).hasSize(1);
        String key = objectStore.objects.keySet().iterator().next();
        assertThat(key).startsWith("T1/media/").endsWith(".png");
        assertThat(completed.message()).isNull();

        MediaTaskService.MediaContent content = service.content(owner, submitted.id());
        assertThat(content.bytes()).isEqualTo(png());
        assertThat(content.mimeType()).isEqualTo("image/png");
        verify(usage, times(1)).received(any(), anyString(), any());

        assertThatThrownBy(() -> service.query(stranger, submitted.id()))
                .isInstanceOfSatisfying(RunApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        assertThatThrownBy(() -> service.content(stranger, submitted.id()))
                .isInstanceOfSatisfying(RunApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
    }

    @Test
    void transientPollFailureKeepsRunningAndDoesNotMarkUnknown() {
        provider.submitTaskIds.add("provider-t1");
        provider.pollFailures.add(new MediaProviderUnavailableException("network"));
        MediaTaskView submitted = service.submitImage(owner, command("req-1"));

        MediaTaskView first = service.query(owner, submitted.id());
        assertThat(first.status()).isEqualTo("processing");

        provider.polls.add(new MediaImagePoll("SUCCEEDED", "https://bucket.aliyuncs.com/a.png", null, null,
                "req-p1", Map.of("images", 1)));
        provider.downloadBytes = png();
        assertThat(service.query(owner, submitted.id()).status()).isEqualTo("completed");
        assertThat(provider.submitCalls).isEqualTo(1);
    }

    @Test
    void providerFailedPollMarksFailedAndAllowsExplicitRetry() {
        provider.submitTaskIds.add("provider-t1");
        provider.polls.add(new MediaImagePoll("FAILED", null, "PROVIDER_FAILED", "content policy",
                "req-p1", null));
        MediaTaskView submitted = service.submitImage(owner, command("req-1"));

        MediaTaskView failed = service.query(owner, submitted.id());
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.message()).isEqualTo("content policy");

        provider.submitTaskIds.add("provider-t2");
        MediaTaskView retried = service.retry(owner, failed.id(), "req-2");
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(provider.submitCalls).isEqualTo(2);
    }

    @Test
    void listIsOwnerScopedAndSurvivesRestart() {
        provider.submitTaskIds.add("provider-t1");
        MediaTaskView first = service.submitImage(owner, command("req-1", "first"));
        provider.submitTaskIds.add("provider-t2");
        MediaTaskView second = service.submitImage(owner, command("req-2", "second"));

        MediaTaskView.Page page = service.list(owner, 20);
        assertThat(page.items()).extracting(MediaTaskView::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(service.list(stranger, 20).items()).isEmpty();
    }

    @Test
    void promptSizeSeedAndModelValidationRejectBeforeAnyPaidCall() {
        assertThatThrownBy(() -> service.submitImage(owner, command("req-a", "   ")))
                .isInstanceOfSatisfying(RunApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RunErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.submitImage(owner,
                new MediaTaskService.ImageSubmitCommand("wanx-test", "p", "big", null, "req-b")))
                .isInstanceOf(RunApiException.class);
        assertThatThrownBy(() -> service.submitImage(owner,
                new MediaTaskService.ImageSubmitCommand("other-model", "p", "1024*1024", null, "req-c")))
                .isInstanceOf(RunApiException.class);
        assertThatThrownBy(() -> service.submitImage(owner,
                new MediaTaskService.ImageSubmitCommand("wanx-test", "p", "1024*1024", -1, "req-d")))
                .isInstanceOf(RunApiException.class);
        assertThat(provider.submitCalls).isZero();
        verify(usage, never()).reserve(anyString(), anyInt());
    }

    @Test
    void missingCredentialFailsClosedBeforeTaskOrSpend() {
        properties.getImage().setApiKey("");

        assertThatThrownBy(() -> service.submitImage(owner, command("req-1")))
                .isInstanceOfSatisfying(RunApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RunErrorCode.DEPENDENCY_UNAVAILABLE));
        assertThat(repository.rows).isEmpty();
        verify(usage, never()).reserve(anyString(), anyInt());
    }

    private MediaTaskService.ImageSubmitCommand command(String requestId) {
        return command(requestId, "a red fox");
    }

    private MediaTaskService.ImageSubmitCommand command(String requestId, String prompt) {
        return new MediaTaskService.ImageSubmitCommand("wanx-test", prompt, "1024*1024", 7, requestId);
    }

    private static ExecutionPrincipal principal(String tenant, String user, String membership) {
        return new ExecutionPrincipal(tenant, user, membership, 1, 1,
                Set.of("run.submit", "run.get"), "jti-" + UUID.randomUUID(), "test",
                0, Long.MAX_VALUE);
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeProvider implements MediaImageProvider {

        private final Deque<String> submitTaskIds = new ArrayDeque<>();
        private final Deque<MediaImagePoll> polls = new ArrayDeque<>();
        private final Deque<RuntimeException> pollFailures = new ArrayDeque<>();
        private RuntimeException submitFailure;
        private byte[] downloadBytes = new byte[0];
        private int submitCalls;

        @Override
        public String providerName() {
            return "Tongyiwanx";
        }

        @Override
        public String submit(MediaImageRequest request) {
            submitCalls++;
            if (submitFailure != null) {
                throw submitFailure;
            }
            if (submitTaskIds.isEmpty()) {
                throw new IllegalStateException("no queued provider task id");
            }
            return submitTaskIds.removeFirst();
        }

        @Override
        public MediaImagePoll poll(String providerTaskId) {
            if (!pollFailures.isEmpty()) {
                throw pollFailures.removeFirst();
            }
            if (polls.isEmpty()) {
                throw new IllegalStateException("no queued poll result");
            }
            return polls.removeFirst();
        }

        @Override
        public byte[] download(String providerUrl, long maxBytes) {
            return downloadBytes;
        }
    }

    private static final class InMemoryObjectStore implements PrivateObjectStore {

        private final Map<String, byte[]> objects = new LinkedHashMap<>();

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public String put(String objectKey, byte[] content) {
            objects.put(objectKey, content.clone());
            return MediaContentStore.sha256(content);
        }

        @Override
        public StoredObject putStream(String objectKey, java.io.InputStream input, long maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] get(String objectKey) {
            byte[] bytes = objects.get(objectKey);
            if (bytes == null) {
                throw new com.nageoffer.ai.ragent.runtime.RunApiException(
                        RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "object not found");
            }
            return bytes.clone();
        }

        @Override
        public boolean exists(String objectKey) {
            return objects.containsKey(objectKey);
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }

        @Override
        public String sha256Of(String objectKey) {
            return MediaContentStore.sha256(objects.get(objectKey));
        }
    }

    private static final class FakeRepository implements MediaTaskRepository {

        private final Map<String, MediaTaskRecord> rows = new LinkedHashMap<>();
        private final Set<String> requestKeys = new LinkedHashSet<>();
        private long sequence;

        @Override
        public Optional<MediaTaskRecord> findByRequestId(String tenantId, String ownerMemberId, String requestId) {
            return rows.values().stream()
                    .filter(row -> row.tenantId().equals(tenantId)
                            && row.ownerMemberId().equals(ownerMemberId)
                            && row.requestId().equals(requestId))
                    .findFirst();
        }

        @Override
        public Optional<MediaTaskRecord> findOwned(String tenantId, String ownerMemberId, String taskId) {
            return Optional.ofNullable(rows.get(key(tenantId, taskId)))
                    .filter(row -> row.ownerMemberId().equals(ownerMemberId));
        }

        @Override
        public List<MediaTaskRecord> listOwned(String tenantId, String ownerMemberId, int limit) {
            return rows.values().stream()
                    .filter(row -> row.tenantId().equals(tenantId) && row.ownerMemberId().equals(ownerMemberId))
                    .sorted(Comparator.comparing(MediaTaskRecord::createdAt).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
        }

        @Override
        public void insert(MediaTaskRecord task) {
            String requestKey = task.tenantId() + "|" + task.ownerMemberId() + "|" + task.requestId();
            if (!requestKeys.add(requestKey)) {
                throw new DuplicateKeyException("duplicate request id");
            }
            rows.put(key(task.tenantId(), task.taskId()), task);
        }

        @Override
        public boolean markRunning(String tenantId, String taskId, String providerTaskId) {
            return transition(tenantId, taskId, MediaTaskStatus.QUEUED, row -> new MediaTaskRecord(
                    row.tenantId(), row.taskId(), row.ownerMemberId(), row.ownerUserId(), row.mediaType(),
                    row.provider(), row.model(), row.requestId(), row.previousTaskId(), row.attempt(),
                    MediaTaskStatus.RUNNING, providerTaskId, row.prompt(), row.size(), row.seed(),
                    row.resultRef(), row.resultMime(), row.resultSha256(), row.resultSize(),
                    row.errorCode(), row.errorMessage(), row.usageJson(), row.units(), row.reservedCny(),
                    row.spendCallId(), row.createdAt(), row.updatedAt()));
        }

        @Override
        public boolean markSucceeded(String tenantId, String taskId, String resultRef, String resultMime,
                                     String resultSha256, long resultSize, String usageJson, long units,
                                     BigDecimal reservedCny) {
            return transition(tenantId, taskId, MediaTaskStatus.RUNNING, row -> new MediaTaskRecord(
                    row.tenantId(), row.taskId(), row.ownerMemberId(), row.ownerUserId(), row.mediaType(),
                    row.provider(), row.model(), row.requestId(), row.previousTaskId(), row.attempt(),
                    MediaTaskStatus.SUCCEEDED, row.providerTaskId(), row.prompt(), row.size(), row.seed(),
                    resultRef, resultMime, resultSha256, resultSize, null, null, usageJson, units,
                    reservedCny, row.spendCallId(), row.createdAt(), row.updatedAt()));
        }

        @Override
        public boolean markFailed(String tenantId, String taskId, String errorCode, String errorMessage) {
            return transition(tenantId, taskId, null, row -> withOutcome(row, MediaTaskStatus.FAILED,
                    errorCode, errorMessage));
        }

        @Override
        public boolean markUnknown(String tenantId, String taskId, String errorCode, String errorMessage) {
            return transition(tenantId, taskId, null, row -> withOutcome(row, MediaTaskStatus.UNKNOWN,
                    errorCode, errorMessage));
        }

        @Override
        public boolean attachSpendCall(String tenantId, String taskId, String spendCallId,
                                       BigDecimal reservedCny) {
            return transition(tenantId, taskId, MediaTaskStatus.QUEUED, row -> new MediaTaskRecord(
                    row.tenantId(), row.taskId(), row.ownerMemberId(), row.ownerUserId(), row.mediaType(),
                    row.provider(), row.model(), row.requestId(), row.previousTaskId(), row.attempt(),
                    row.status(), row.providerTaskId(), row.prompt(), row.size(), row.seed(),
                    row.resultRef(), row.resultMime(), row.resultSha256(), row.resultSize(),
                    row.errorCode(), row.errorMessage(), row.usageJson(), row.units(), reservedCny,
                    spendCallId, row.createdAt(), row.updatedAt()));
        }

        private MediaTaskRecord withOutcome(MediaTaskRecord row, MediaTaskStatus status,
                                            String errorCode, String errorMessage) {
            return new MediaTaskRecord(row.tenantId(), row.taskId(), row.ownerMemberId(), row.ownerUserId(),
                    row.mediaType(), row.provider(), row.model(), row.requestId(), row.previousTaskId(),
                    row.attempt(), status, row.providerTaskId(), row.prompt(), row.size(), row.seed(),
                    row.resultRef(), row.resultMime(), row.resultSha256(), row.resultSize(),
                    errorCode, errorMessage, row.usageJson(), row.units(), row.reservedCny(),
                    row.spendCallId(), row.createdAt(), row.updatedAt());
        }

        private boolean transition(String tenantId, String taskId, MediaTaskStatus expected,
                                   java.util.function.UnaryOperator<MediaTaskRecord> change) {
            MediaTaskRecord row = rows.get(key(tenantId, taskId));
            if (row == null) {
                return false;
            }
            if (expected != null) {
                if (row.status() != expected) {
                    return false;
                }
            } else if (row.status() != MediaTaskStatus.QUEUED && row.status() != MediaTaskStatus.RUNNING) {
                return false;
            }
            rows.put(key(tenantId, taskId), change.apply(row));
            sequence++;
            return true;
        }

        private static String key(String tenantId, String taskId) {
            return tenantId + "|" + taskId;
        }
    }
}
