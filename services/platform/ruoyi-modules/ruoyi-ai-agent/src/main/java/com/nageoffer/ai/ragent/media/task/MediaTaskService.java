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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import com.nageoffer.ai.ragent.media.delivery.MediaContentStore;
import com.nageoffer.ai.ragent.media.image.MediaImagePoll;
import com.nageoffer.ai.ragent.media.image.MediaImageProvider;
import com.nageoffer.ai.ragent.media.image.MediaImageRequest;
import com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException;
import com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException;
import com.nageoffer.ai.ragent.media.usage.MediaUsageMeter;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 媒体任务权威（F19:1/F19:3/F19:4/F19:5）。
 *
 * <p><b>状态机。</b>{@link MediaTaskStatus}。提交先落 QUEUED 再调用 provider；
 * provider 明确拒绝 → FAILED；结果未知（超时/5xx/缺任务 ID）→ UNKNOWN。
 * UNKNOWN/FAILED 只能由用户显式 {@link #retry} 产生新的 attempt——绝不自动重发付费请求。
 *
 * <p><b>幂等。</b>{@code (tenant, member, requestId)} 唯一；同键重复提交返回已有任务，
 * 不产生第二次 provider 调用。
 *
 * <p><b>预算。</b>提交前 {@link MediaUsageMeter#reserve} 预占信封；失败 = 零 provider 调用。
 * UNKNOWN 不退还预占。成功只推进到 RESPONSE_RECEIVED，单位是张数。
 *
 * <p><b>私有结果。</b>provider 成功结果先经 {@link MediaContentStore} 私有化，
 * 视图只暴露任务 ID；取流按 tenant+属主限定并在读取后复核状态/对象引用。
 */
public class MediaTaskService {

    private static final Logger log = LoggerFactory.getLogger(MediaTaskService.class);

    private static final String KIND_IMAGE = "image";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern SIZE = Pattern.compile("(\\d{3,4})\\*(\\d{3,4})");
    private static final String UNKNOWN_MESSAGE = "结果未知，未自动重试付费请求；请核对后显式重试。";

    private final MediaTaskRepository repository;
    private final MediaImageProvider provider;
    private final MediaContentStore contentStore;
    private final MediaUsageMeter usage;
    private final MediaProperties properties;
    private final EgressPolicy egress;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MediaTaskService(MediaTaskRepository repository, MediaImageProvider provider,
                            MediaContentStore contentStore, MediaUsageMeter usage,
                            MediaProperties properties, EgressPolicy egress,
                            ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.provider = provider;
        this.contentStore = contentStore;
        this.usage = usage;
        this.properties = properties;
        this.egress = egress;
        this.mapper = mapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------ submit

    /** 图片提交命令（HTTP 层 DTO，不含身份）。 */
    public record ImageSubmitCommand(String model, String prompt, String size, Integer seed, String requestId) {
    }

    /**
     * 提交一次图片生成。返回的任务视图可能是 {@code pending/processing/failed/unknown}，
     * 但 HTTP 层始终以 200 返回任务事实（失败原因在 {@code message}），便于任务列表展示。
     */
    public MediaTaskView submitImage(ExecutionPrincipal principal, ImageSubmitCommand command) {
        String requestId = normalizeRequestId(command.requestId());
        Optional<MediaTaskRecord> existing = repository.findByRequestId(
                principal.tenantId(), principal.membershipId(), requestId);
        if (existing.isPresent()) {
            return MediaTaskView.of(existing.get());
        }
        String prompt = requirePrompt(command.prompt());
        String size = requireSize(command.size());
        Integer seed = requireSeed(command.seed());
        String model = requireModel(command.model());
        requireConfigured();
        requireEgress();

        MediaTaskRecord queued = newTask(principal, requestId, model, prompt, size, seed, null, 1);
        if (!insertOrExisting(queued)) {
            return MediaTaskView.of(repository.findByRequestId(
                    principal.tenantId(), principal.membershipId(), requestId).orElseThrow());
        }
        return dispatch(principal, queued);
    }

    /**
     * 显式重试：只允许 FAILED/UNKNOWN。新 attempt 关联前一任务（previous_task_id），
     * 保留 UNKNOWN 的预算预占（不退还、不复用）。
     */
    public MediaTaskView retry(ExecutionPrincipal principal, String taskId, String requestId) {
        MediaTaskRecord previous = requireOwned(principal, taskId);
        if (!previous.status().retryable()) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "当前任务状态不允许重试");
        }
        String requestIdValue = normalizeRequestId(requestId);
        Optional<MediaTaskRecord> existing = repository.findByRequestId(
                principal.tenantId(), principal.membershipId(), requestIdValue);
        if (existing.isPresent()) {
            return MediaTaskView.of(existing.get());
        }
        requireConfigured();
        requireEgress();
        MediaTaskRecord next = newTask(principal, requestIdValue, previous.model(), previous.prompt(),
                previous.size(), previous.seed(), previous.taskId(), previous.attempt() + 1);
        if (!insertOrExisting(next)) {
            return MediaTaskView.of(repository.findByRequestId(
                    principal.tenantId(), principal.membershipId(), requestIdValue).orElseThrow());
        }
        return dispatch(principal, next);
    }

    // ------------------------------------------------------------------ query / list

    /** 查询任务；RUNNING 时顺带轮询 provider，成功结果私有化后落 SUCCEEDED。 */
    public MediaTaskView query(ExecutionPrincipal principal, String taskId) {
        MediaTaskRecord task = requireOwned(principal, taskId);
        if (task.status().terminal()) {
            return MediaTaskView.of(task);
        }
        if (task.status() == MediaTaskStatus.QUEUED && task.providerTaskId() == null) {
            // 落库后、provider 回执前崩溃：结果未知，不自动重发。
            repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_OUTCOME_UNKNOWN", UNKNOWN_MESSAGE);
            return viewAfterWrite(principal, task.taskId());
        }
        MediaImagePoll poll;
        try {
            poll = provider.poll(task.providerTaskId());
        } catch (MediaProviderRejectedException e) {
            // provider 明确不认这个任务（不存在/被拒）：未知，不自动重发。
            repository.markUnknown(task.tenantId(), task.taskId(), e.errorCode(), sanitize(e.getMessage()));
            return viewAfterWrite(principal, task.taskId());
        } catch (MediaProviderUnavailableException e) {
            // 查询暂时不可用：保持 RUNNING，下一次查询继续，不能伪造成终态。
            return MediaTaskView.of(task, "查询暂时失败，任务仍在生成；请稍后重试。");
        }
        return applyPoll(principal, task, poll);
    }

    /**


     * 属主限定列表。刷新后任务仍在（服务端权威），不依赖前端内存。
     */
    public MediaTaskView.Page list(ExecutionPrincipal principal, int limit) {
        List<MediaTaskView> items = repository.listOwned(
                        principal.tenantId(), principal.membershipId(), limit)
                .stream().map(MediaTaskView::of).toList();
        return new MediaTaskView.Page(items);
    }

    // ------------------------------------------------------------------ content

    /** 私有结果字节。调用方（控制器）负责 Range/响应头。 */
    public record MediaContent(byte[] bytes, String mimeType) {
    }

    public MediaContent content(ExecutionPrincipal principal, String taskId) {
        MediaTaskRecord task = requireOwned(principal, taskId);
        if (task.status() != MediaTaskStatus.SUCCEEDED || task.resultRef() == null) {
            throw new RunApiException(RunErrorCode.RUN_STATE_CONFLICT, "媒体结果尚未生成");
        }
        byte[] bytes = contentStore.read(task.resultRef());
        // 读取后复核：删除/状态变化/对象引用变化都不得继续释放字节。
        MediaTaskRecord current = repository.findOwned(
                        principal.tenantId(), principal.membershipId(), taskId)
                .orElseThrow(() -> new RunApiException(
                        RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "媒体结果不可用"));
        if (current.status() != MediaTaskStatus.SUCCEEDED || !task.resultRef().equals(current.resultRef())) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "媒体结果不可用");
        }
        return new MediaContent(bytes, task.resultMime());
    }

    // ------------------------------------------------------------------ internals

    private MediaTaskView dispatch(ExecutionPrincipal principal, MediaTaskRecord queued) {
        MediaUsageMeter.Reserved reserved;
        try {
            reserved = usage.reserve(queued.model(), 1);
        } catch (RunApiException e) {
            repository.markFailed(queued.tenantId(), queued.taskId(), e.errorCode().name(),
                    "额度或并发超限，未发起生成");
            return viewAfterWrite(principal, queued.taskId());
        }
        repository.attachSpendCall(queued.tenantId(), queued.taskId(), reserved.callId(), reserved.reservedCny());
        try {
            String providerTaskId = provider.submit(
                    new MediaImageRequest(queued.model(), queued.prompt(), queued.size(), queued.seed()));
            if (!repository.markRunning(queued.tenantId(), queued.taskId(), providerTaskId)) {
                // 状态已被并发推进（例如另一个查询把它落成 UNKNOWN）：不覆盖终态。
                log.warn("media task state changed during submit: task={}", queued.taskId());
            }
        } catch (MediaProviderRejectedException e) {
            repository.markFailed(queued.tenantId(), queued.taskId(), e.errorCode(), sanitize(e.getMessage()));
        } catch (MediaProviderUnavailableException e) {
            repository.markUnknown(queued.tenantId(), queued.taskId(), "PROVIDER_OUTCOME_UNKNOWN", UNKNOWN_MESSAGE);
        }
        return viewAfterWrite(principal, queued.taskId());
    }

    private MediaTaskView applyPoll(ExecutionPrincipal principal, MediaTaskRecord task, MediaImagePoll poll) {
        String status = poll.status() == null ? "UNKNOWN" : poll.status();
        switch (status) {
            case "PENDING", "RUNNING" -> {
                // 仍在生成：保持 RUNNING，不做任何写操作。
                return MediaTaskView.of(task, "任务已提交，正在等待生成结果。");
            }
            case "SUCCEEDED" -> {
                return complete(principal, task, poll);
            }
            case "FAILED", "CANCELED" -> repository.markFailed(task.tenantId(), task.taskId(),
                    poll.errorCode() == null ? "PROVIDER_FAILED" : poll.errorCode(),
                    sanitize(poll.message()));
            default -> repository.markUnknown(task.tenantId(), task.taskId(),
                    "PROVIDER_STATUS_UNKNOWN", UNKNOWN_MESSAGE);
        }
        return viewAfterWrite(principal, task.taskId());
    }

    private MediaTaskView complete(ExecutionPrincipal principal, MediaTaskRecord task, MediaImagePoll poll) {
        if (poll.imageUrl() == null) {
            repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_RESULT_MISSING", UNKNOWN_MESSAGE);
            return viewAfterWrite(principal, task.taskId());
        }
        byte[] bytes;
        String mime;
        MediaContentStore.StoredMedia stored;
        try {
            bytes = provider.download(poll.imageUrl(), properties.getImage().getMaxBytes());
            mime = sniffImageMime(bytes);
            try {
                stored = contentStore.store(task.tenantId(), task.taskId(), task.ownerMemberId(), bytes, mime);
            } catch (RunApiException e) {
                // 结果已生成但私有化存储暂时失败：保持 RUNNING，下一次查询重试（不重复计费）。
                return MediaTaskView.of(task, "结果已生成，私有化存储暂未完成；请稍后重新查询。");
            }
            long units = unitsOf(poll.usage());
            try {
                usage.received(spendReservation(task), poll.providerRequestId(), poll.usage());
            } catch (RuntimeException e) {
                // 结果已经私有化：不能因为台账更新失败丢掉付费结果。信封保持 STARTED（保守）。
                log.warn("media spend receipt update failed: task={} code={}",
                        task.taskId(), e instanceof RunApiException re ? re.errorCode() : "UNKNOWN");
            }
            repository.markSucceeded(task.tenantId(), task.taskId(), stored.objectKey(), stored.mimeType(),
                    stored.sha256(), stored.sizeBytes(), usageJson(poll.usage()), units, task.reservedCny());
            return viewAfterWrite(principal, task.taskId());
        } catch (MediaProviderUnavailableException e) {
            // 结果已生成但下载暂时失败：保持 RUNNING，下一次查询重试私有化（不重复计费）。
            return MediaTaskView.of(task, "结果已生成，私有化存储暂未完成；请稍后重新查询。");
        } catch (MediaProviderRejectedException e) {
            repository.markFailed(task.tenantId(), task.taskId(), e.errorCode(), sanitize(e.getMessage()));
            return viewAfterWrite(principal, task.taskId());
        }
    }

    private MediaUsageMeter.Reserved spendReservation(MediaTaskRecord task) {
        return new MediaUsageMeter.Reserved(task.spendCallId(), task.reservedCny(), 1);
    }

    private static long unitsOf(Map<String, Object> usage) {
        Long units = UsageLedgerService.mediaUnits(usage);
        // 已成功返回一张图：provider 未给 image_count 时按请求张数记 1（单位是张，不是 token）。
        return units == null ? 1L : units;
    }

    private MediaTaskView viewAfterWrite(ExecutionPrincipal principal, String taskId) {
        MediaTaskRecord current = repository.findOwned(principal.tenantId(), principal.membershipId(), taskId)
                .orElseThrow(() -> new RunApiException(
                        RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "任务不存在或无权访问"));
        return MediaTaskView.of(current);
    }

    private MediaTaskRecord requireOwned(ExecutionPrincipal principal, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "任务 ID 不能为空");
        }
        return repository.findOwned(principal.tenantId(), principal.membershipId(), taskId)
                .orElseThrow(() -> new RunApiException(
                        RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "任务不存在或无权访问"));
    }

    private boolean insertOrExisting(MediaTaskRecord task) {
        try {
            repository.insert(task);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private MediaTaskRecord newTask(ExecutionPrincipal principal, String requestId, String model,
                                    String prompt, String size, Integer seed,
                                    String previousTaskId, int attempt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return new MediaTaskRecord(
                principal.tenantId(),
                "mt-" + UUID.randomUUID().toString().replace("-", ""),
                principal.membershipId(),
                principal.userId(),
                KIND_IMAGE,
                provider.providerName(),
                model,
                requestId,
                previousTaskId,
                attempt,
                MediaTaskStatus.QUEUED,
                null,
                prompt,
                size,
                seed,
                null, null, null, null,
                null, null,
                null, null, null, null,
                now, now);
    }

    private void requireConfigured() {
        MediaProperties.Image image = properties.getImage();
        if (!properties.isEnabled() || !image.isEnabled()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "MEDIA_DISABLED: 媒体面未启用");
        }
        if (image.getModelName() == null || image.getModelName().isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_PROVIDER_NOT_CONFIGURED: 未配置图片模型");
        }
        if (image.getApiKey() == null || image.getApiKey().isBlank()) {
            // 只说明未配置，绝不回显 key。
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_PROVIDER_NOT_CONFIGURED: 图片提供方凭据未注入");
        }
    }

    private void requireEgress() {
        if (egress == null) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_EGRESS_UNAVAILABLE: 外发闸门未装配");
        }
        egress.requireAllowed(properties.getImage().getBillingProvider());
    }

    private String requireModel(String requested) {
        String configured = properties.getImage().getModelName();
        String model = requested == null || requested.isBlank() ? configured : requested.trim();
        if (!model.equals(configured)) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST,
                    "MEDIA_MODEL_UNSUPPORTED: 模型目录未启用，仅支持已配置模型");
        }
        return model;
    }

    private String requirePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.isEmpty()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        if (value.length() > properties.getImage().getMaxPromptLength()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "提示词超过长度上限");
        }
        return value;
    }

    private String requireSize(String requested) {
        String size = requested == null || requested.isBlank()
                ? properties.getImage().getDefaultSize() : requested.trim();
        var matcher = SIZE.matcher(size);
        if (!matcher.matches()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_SIZE_INVALID: 尺寸格式应为 宽*高");
        }
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        if (width < 256 || width > 4096 || height < 256 || height > 4096) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_SIZE_INVALID: 尺寸超出允许范围");
        }
        return size;
    }

    private static Integer requireSeed(Integer seed) {
        if (seed == null) {
            return null;
        }
        if (seed < 0) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_SEED_INVALID: 随机种子不能小于 0");
        }
        return seed;
    }

    private static String normalizeRequestId(String requestId) {
        String value = requestId == null || requestId.isBlank()
                ? "req-" + UUID.randomUUID().toString().replace("-", "") : requestId.trim();
        if (!REQUEST_ID.matcher(value).matches()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "幂等键格式不合法");
        }
        return value;
    }

    private String usageJson(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(usage);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String sniffImageMime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        throw new MediaProviderRejectedException("PROVIDER_RESULT_INVALID", "结果内容不是可识别的图片");
    }

    private static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String clean = message.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() > 240 ? clean.substring(0, 240) : clean;
    }
}
