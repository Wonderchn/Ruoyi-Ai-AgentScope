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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.media.audio.MediaAudioProvider;
import com.nageoffer.ai.ragent.media.audio.MediaAudioReceipt;
import com.nageoffer.ai.ragent.media.audio.MediaAudioRequest;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import com.nageoffer.ai.ragent.media.delivery.MediaContentStore;
import com.nageoffer.ai.ragent.media.image.MediaImagePoll;
import com.nageoffer.ai.ragent.media.image.MediaImageProvider;
import com.nageoffer.ai.ragent.media.image.MediaImageRequest;
import com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException;
import com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException;
import com.nageoffer.ai.ragent.media.usage.MediaUsageMeter;
import com.nageoffer.ai.ragent.media.video.MediaVideoPoll;
import com.nageoffer.ai.ragent.media.video.MediaVideoProvider;
import com.nageoffer.ai.ragent.media.video.MediaVideoRequest;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import com.nageoffer.ai.ragent.runtime.usage.ProviderSpendEnvelope;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 媒体任务权威（F19:0–F19:5）。
 *
 * <p><b>状态机。</b>{@link MediaTaskStatus}。提交先落 QUEUED 再调用 provider；
 * provider 明确拒绝 → FAILED；结果未知（超时/5xx/缺任务 ID）→ UNKNOWN。
 * UNKNOWN/FAILED 只能由用户显式 {@link #retry} 产生新的 attempt——绝不自动重发付费请求。
 *
 * <p><b>媒体类型。</b>{@code image}/{@code audio}/{@code video} 共用同一任务表与取流面：
 * <ul>
 *   <li>image：DashScope 异步文生图，提交/轮询/私有化；</li>
 *   <li>audio（F19:0）：qwen-tts HTTP 同步，提交即结果；同步端点没有可重复读取的
 *       结果地址，因此下载/落盘失败的终态是 <b>UNKNOWN</b>（不自动重发）；</li>
 *   <li>video（F19:2）：DashScope 异步文生视频，提交/轮询/私有化；</li>
 * </ul>
 *
 * <p><b>幂等。</b>{@code (tenant, member, requestId)} 唯一；同键重复提交返回已有任务，
 * 不产生第二次 provider 调用。
 *
 * <p><b>预算。</b>提交前 {@link MediaUsageMeter#reserve} 预占信封；失败 = 零 provider 调用。
 * 单位：image=张数、audio=输入字符数、video=生成次数；UNKNOWN 不退还预占。
 *
 * <p><b>重试参数。</b>audio/video 的请求参数（音色/格式/时长等）在 QUEUED/FAILED/UNKNOWN
 * 行上存于 {@code usage_raw.request}，显式重试按原参数复现；SUCCEEDED 行存 provider 用量。
 *
 * <p><b>私有结果。</b>provider 成功结果先经 {@link MediaContentStore} 私有化，
 * 视图只暴露任务 ID；取流按 tenant+属主限定并在读取后复核状态/对象引用。
 */
public class MediaTaskService {

    private static final Logger log = LoggerFactory.getLogger(MediaTaskService.class);

    private static final String KIND_IMAGE = ProviderSpendEnvelope.MEDIA_TYPE_IMAGE;
    private static final String KIND_AUDIO = ProviderSpendEnvelope.MEDIA_TYPE_AUDIO;
    private static final String KIND_VIDEO = ProviderSpendEnvelope.MEDIA_TYPE_VIDEO;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern SIZE = Pattern.compile("(\\d{3,4})\\*(\\d{3,4})");
    private static final Pattern VOICE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Set<String> AUDIO_FORMATS = Set.of("mp3", "wav", "opus", "pcm", "flac", "aac");
    /** 同步语音在 RUNNING 状态下等待落盘的宽限期；超过视为崩溃，结果地址已丢失 → UNKNOWN。 */
    private static final long AUDIO_RUNNING_GRACE_MS = 60_000L;
    private static final String UNKNOWN_MESSAGE = "结果未知，未自动重试付费请求；请核对后显式重试。";

    private final MediaTaskRepository repository;
    private final MediaImageProvider imageProvider;
    private final MediaAudioProvider audioProvider;
    private final MediaVideoProvider videoProvider;
    private final MediaContentStore contentStore;
    private final MediaUsageMeter usage;
    private final MediaProperties properties;
    private final EgressPolicy egress;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MediaTaskService(MediaTaskRepository repository, MediaImageProvider imageProvider,
                            MediaAudioProvider audioProvider, MediaVideoProvider videoProvider,
                            MediaContentStore contentStore, MediaUsageMeter usage,
                            MediaProperties properties, EgressPolicy egress,
                            ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.imageProvider = imageProvider;
        this.audioProvider = audioProvider;
        this.videoProvider = videoProvider;
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

    /** F19:0 语音提交命令（文本/音色/格式/朗读要求；speed 记录但协议不下发）。 */
    public record AudioSubmitCommand(String model, String input, String voice, String responseFormat,
                                     Double speed, String instructions, String requestId) {
    }

    /** F19:2 视频提交命令（提示词/尺寸/时长；quality 记录但协议不下发）。 */
    public record VideoSubmitCommand(String model, String prompt, String size, Integer seconds,
                                     String quality, String requestId) {
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
        String model = requireModel(command.model(), properties.getImage().getModelName());
        requireConfigured(properties.getImage());
        requireEgress(properties.getImage().getBillingProvider());

        MediaTaskRecord queued = newTask(principal, KIND_IMAGE, imageProvider.providerName(), requestId,
                model, prompt, size, seed, null, 1, null);
        if (!insertOrExisting(queued)) {
            return MediaTaskView.of(repository.findByRequestId(
                    principal.tenantId(), principal.membershipId(), requestId).orElseThrow());
        }
        return dispatchImage(principal, queued);
    }

    /**
     * F19:0 提交一次语音合成（同步 qwen-tts）。成功时任务已是 {@code completed}，
     * 结果字节已私有化；失败/未知按任务状态如实返回。
     */
    public MediaTaskView submitAudio(ExecutionPrincipal principal, AudioSubmitCommand command) {
        String requestId = normalizeRequestId(command.requestId());
        Optional<MediaTaskRecord> existing = repository.findByRequestId(
                principal.tenantId(), principal.membershipId(), requestId);
        if (existing.isPresent()) {
            return MediaTaskView.of(existing.get());
        }
        MediaAudioRequest request = requireAudioRequest(command);
        requireConfigured(properties.getAudio());
        requireEgress(properties.getAudio().getBillingProvider());

        MediaTaskRecord queued = newTask(principal, KIND_AUDIO, audioProvider.providerName(), requestId,
                request.model(), request.text(), null, null, null, 1, requestFactsJson(audioFacts(request)));
        if (!insertOrExisting(queued)) {
            return MediaTaskView.of(repository.findByRequestId(
                    principal.tenantId(), principal.membershipId(), requestId).orElseThrow());
        }
        return dispatchAudio(principal, queued, request);
    }

    /** F19:2 提交一次视频生成（异步 DashScope 任务）。 */
    public MediaTaskView submitVideo(ExecutionPrincipal principal, VideoSubmitCommand command) {
        String requestId = normalizeRequestId(command.requestId());
        Optional<MediaTaskRecord> existing = repository.findByRequestId(
                principal.tenantId(), principal.membershipId(), requestId);
        if (existing.isPresent()) {
            return MediaTaskView.of(existing.get());
        }
        MediaVideoRequest request = requireVideoRequest(command);
        requireConfigured(properties.getVideo());
        requireEgress(properties.getVideo().getBillingProvider());

        MediaTaskRecord queued = newTask(principal, KIND_VIDEO, videoProvider.providerName(), requestId,
                request.model(), request.prompt(), request.size(), null, null, 1, requestFactsJson(videoFacts(request)));
        if (!insertOrExisting(queued)) {
            return MediaTaskView.of(repository.findByRequestId(
                    principal.tenantId(), principal.membershipId(), requestId).orElseThrow());
        }
        return dispatchVideo(principal, queued, request);
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
        String mediaType = previous.mediaType();
        if (KIND_AUDIO.equals(mediaType)) {
            requireConfigured(properties.getAudio());
            requireEgress(properties.getAudio().getBillingProvider());
        } else if (KIND_VIDEO.equals(mediaType)) {
            requireConfigured(properties.getVideo());
            requireEgress(properties.getVideo().getBillingProvider());
        } else {
            requireConfigured(properties.getImage());
            requireEgress(properties.getImage().getBillingProvider());
        }
        MediaTaskRecord next = switch (mediaType) {
            case KIND_AUDIO -> newTask(principal, KIND_AUDIO, audioProvider.providerName(), requestIdValue,
                    previous.model(), previous.prompt(), null, null, previous.taskId(), previous.attempt() + 1,
                    previous.usageJson());
            case KIND_VIDEO -> newTask(principal, KIND_VIDEO, videoProvider.providerName(), requestIdValue,
                    previous.model(), previous.prompt(), previous.size(), null, previous.taskId(),
                    previous.attempt() + 1, previous.usageJson());
            default -> newTask(principal, KIND_IMAGE, imageProvider.providerName(), requestIdValue,
                    previous.model(), previous.prompt(), previous.size(), previous.seed(), previous.taskId(),
                    previous.attempt() + 1, null);
        };
        if (!insertOrExisting(next)) {
            return MediaTaskView.of(repository.findByRequestId(
                    principal.tenantId(), principal.membershipId(), requestIdValue).orElseThrow());
        }
        return switch (mediaType) {
            case KIND_AUDIO -> dispatchAudio(principal, next, audioRequestFromFacts(previous));
            case KIND_VIDEO -> dispatchVideo(principal, next, videoRequestFromFacts(previous));
            default -> dispatchImage(principal, next);
        };
    }

    // ------------------------------------------------------------------ query / list

    /** 查询任务；异步类型顺带轮询 provider，成功结果私有化后落 SUCCEEDED。 */
    public MediaTaskView query(ExecutionPrincipal principal, String taskId) {
        MediaTaskRecord task = requireOwned(principal, taskId);
        if (task.status().terminal()) {
            return MediaTaskView.of(task);
        }
        if (KIND_AUDIO.equals(task.mediaType())) {
            // 同步语音没有可重复读取的 provider 任务：QUEUED 崩溃或 RUNNING 超过宽限期
            // 说明结果地址已丢失，只能保守落 UNKNOWN（不自动重发付费请求）。
            if (task.status() == MediaTaskStatus.QUEUED && task.providerTaskId() == null) {
                repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_OUTCOME_UNKNOWN", UNKNOWN_MESSAGE);
                return viewAfterWrite(principal, task.taskId());
            }
            if (task.status() == MediaTaskStatus.RUNNING && stale(task)) {
                repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_OUTCOME_UNKNOWN", UNKNOWN_MESSAGE);
                return viewAfterWrite(principal, task.taskId());
            }
            return MediaTaskView.of(task, "正在合成语音，请稍候。");
        }
        if (task.status() == MediaTaskStatus.QUEUED && task.providerTaskId() == null) {
            // 落库后、provider 回执前崩溃：结果未知，不自动重发。
            repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_OUTCOME_UNKNOWN", UNKNOWN_MESSAGE);
            return viewAfterWrite(principal, task.taskId());
        }
        if (KIND_VIDEO.equals(task.mediaType())) {
            MediaVideoPoll poll;
            try {
                poll = videoProvider.poll(task.providerTaskId());
            } catch (MediaProviderRejectedException e) {
                repository.markUnknown(task.tenantId(), task.taskId(), e.errorCode(), sanitize(e.getMessage()));
                return viewAfterWrite(principal, task.taskId());
            } catch (MediaProviderUnavailableException e) {
                return MediaTaskView.of(task, "查询暂时失败，任务仍在生成；请稍后重试。");
            }
            return applyVideoPoll(principal, task, poll);
        }
        MediaImagePoll poll;
        try {
            poll = imageProvider.poll(task.providerTaskId());
        } catch (MediaProviderRejectedException e) {
            // provider 明确不认这个任务（不存在/被拒）：未知，不自动重发。
            repository.markUnknown(task.tenantId(), task.taskId(), e.errorCode(), sanitize(e.getMessage()));
            return viewAfterWrite(principal, task.taskId());
        } catch (MediaProviderUnavailableException e) {
            // 查询暂时不可用：保持 RUNNING，下一次查询继续，不能伪造成终态。
            return MediaTaskView.of(task, "查询暂时失败，任务仍在生成；请稍后重试。");
        }
        return applyImagePoll(principal, task, poll);
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

    // ------------------------------------------------------------------ dispatch

    private MediaTaskView dispatchImage(ExecutionPrincipal principal, MediaTaskRecord queued) {
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
            String providerTaskId = imageProvider.submit(
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

    private MediaTaskView dispatchAudio(ExecutionPrincipal principal, MediaTaskRecord queued,
                                        MediaAudioRequest request) {
        MediaUsageMeter.Reserved reserved;
        try {
            // 音频按输入字符数预占；失败 = 零 provider 调用。
            reserved = usage.reserve(queued.model(), KIND_AUDIO, request.text().length());
        } catch (RunApiException e) {
            repository.markFailed(queued.tenantId(), queued.taskId(), e.errorCode().name(),
                    "额度或并发超限，未发起生成");
            return viewAfterWrite(principal, queued.taskId());
        }
        repository.attachSpendCall(queued.tenantId(), queued.taskId(), reserved.callId(), reserved.reservedCny());
        try {
            MediaAudioReceipt receipt = audioProvider.submit(request);
            if (!repository.markRunning(queued.tenantId(), queued.taskId(), receipt.providerRequestId())) {
                log.warn("media task state changed during audio submit: task={}", queued.taskId());
                return viewAfterWrite(principal, queued.taskId());
            }
            MediaTaskRecord running = repository.findOwned(
                            principal.tenantId(), principal.membershipId(), queued.taskId())
                    .orElse(queued);
            return completeAudio(principal, running, request, receipt);
        } catch (MediaProviderRejectedException e) {
            repository.markFailed(queued.tenantId(), queued.taskId(), e.errorCode(), sanitize(e.getMessage()));
        } catch (MediaProviderUnavailableException e) {
            repository.markUnknown(queued.tenantId(), queued.taskId(), "PROVIDER_OUTCOME_UNKNOWN", UNKNOWN_MESSAGE);
        }
        return viewAfterWrite(principal, queued.taskId());
    }

    private MediaTaskView dispatchVideo(ExecutionPrincipal principal, MediaTaskRecord queued,
                                        MediaVideoRequest request) {
        MediaUsageMeter.Reserved reserved;
        try {
            // 视频按生成次数预占（本切片 1 次 = 1 条视频）。
            reserved = usage.reserve(queued.model(), KIND_VIDEO, 1);
        } catch (RunApiException e) {
            repository.markFailed(queued.tenantId(), queued.taskId(), e.errorCode().name(),
                    "额度或并发超限，未发起生成");
            return viewAfterWrite(principal, queued.taskId());
        }
        repository.attachSpendCall(queued.tenantId(), queued.taskId(), reserved.callId(), reserved.reservedCny());
        try {
            String providerTaskId = videoProvider.submit(request);
            if (!repository.markRunning(queued.tenantId(), queued.taskId(), providerTaskId)) {
                log.warn("media task state changed during video submit: task={}", queued.taskId());
            }
        } catch (MediaProviderRejectedException e) {
            repository.markFailed(queued.tenantId(), queued.taskId(), e.errorCode(), sanitize(e.getMessage()));
        } catch (MediaProviderUnavailableException e) {
            repository.markUnknown(queued.tenantId(), queued.taskId(), "PROVIDER_OUTCOME_UNKNOWN", UNKNOWN_MESSAGE);
        }
        return viewAfterWrite(principal, queued.taskId());
    }

    // ------------------------------------------------------------------ poll / complete

    private MediaTaskView applyImagePoll(ExecutionPrincipal principal, MediaTaskRecord task, MediaImagePoll poll) {
        String status = poll.status() == null ? "UNKNOWN" : poll.status();
        switch (status) {
            case "PENDING", "RUNNING" -> {
                // 仍在生成：保持 RUNNING，不做任何写操作。
                return MediaTaskView.of(task, "任务已提交，正在等待生成结果。");
            }
            case "SUCCEEDED" -> {
                return completeImage(principal, task, poll);
            }
            case "FAILED", "CANCELED" -> repository.markFailed(task.tenantId(), task.taskId(),
                    poll.errorCode() == null ? "PROVIDER_FAILED" : poll.errorCode(),
                    sanitize(poll.message()));
            default -> repository.markUnknown(task.tenantId(), task.taskId(),
                    "PROVIDER_STATUS_UNKNOWN", UNKNOWN_MESSAGE);
        }
        return viewAfterWrite(principal, task.taskId());
    }

    private MediaTaskView applyVideoPoll(ExecutionPrincipal principal, MediaTaskRecord task, MediaVideoPoll poll) {
        String status = poll.status() == null ? "UNKNOWN" : poll.status();
        switch (status) {
            case "PENDING", "RUNNING" -> {
                return MediaTaskView.of(task, "任务已提交，正在等待生成结果。");
            }
            case "SUCCEEDED" -> {
                return completeVideo(principal, task, poll);
            }
            case "FAILED", "CANCELED" -> repository.markFailed(task.tenantId(), task.taskId(),
                    poll.errorCode() == null ? "PROVIDER_FAILED" : poll.errorCode(),
                    sanitize(poll.message()));
            default -> repository.markUnknown(task.tenantId(), task.taskId(),
                    "PROVIDER_STATUS_UNKNOWN", UNKNOWN_MESSAGE);
        }
        return viewAfterWrite(principal, task.taskId());
    }

    private MediaTaskView completeImage(ExecutionPrincipal principal, MediaTaskRecord task, MediaImagePoll poll) {
        if (poll.imageUrl() == null) {
            repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_RESULT_MISSING", UNKNOWN_MESSAGE);
            return viewAfterWrite(principal, task.taskId());
        }
        try {
            byte[] bytes = imageProvider.download(poll.imageUrl(), properties.getImage().getMaxBytes());
            String mime = sniffImageMime(bytes);
            MediaContentStore.StoredMedia stored;
            try {
                stored = contentStore.store(task.tenantId(), task.taskId(), task.ownerMemberId(), bytes, mime);
            } catch (RunApiException e) {
                // 结果已生成但私有化存储暂时失败：保持 RUNNING，下一次查询重试（不重复计费）。
                return MediaTaskView.of(task, "结果已生成，私有化存储暂未完成；请稍后重新查询。");
            }
            long units = unitsOf(KIND_IMAGE, poll.usage(), 1L);
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

    private MediaTaskView completeAudio(ExecutionPrincipal principal, MediaTaskRecord task,
                                        MediaAudioRequest request, MediaAudioReceipt receipt) {
        String format = receipt.declaredFormat() == null || receipt.declaredFormat().isBlank()
                ? request.format() : receipt.declaredFormat();
        byte[] bytes;
        String mime;
        try {
            if (receipt.audioBase64() != null && !receipt.audioBase64().isBlank()) {
                bytes = Base64.getDecoder().decode(receipt.audioBase64());
            } else {
                bytes = audioProvider.download(receipt.audioUrl(), properties.getAudio().getMaxBytes(), format);
            }
            mime = audioProvider.sniffMime(bytes, format);
        } catch (IllegalArgumentException e) {
            repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_RESULT_INVALID", UNKNOWN_MESSAGE);
            return viewAfterWrite(principal, task.taskId());
        } catch (MediaProviderRejectedException e) {
            repository.markFailed(task.tenantId(), task.taskId(), e.errorCode(), sanitize(e.getMessage()));
            return viewAfterWrite(principal, task.taskId());
        } catch (MediaProviderUnavailableException e) {
            // 同步端点没有可重复读取的结果地址：下载失败即结果不可恢复，保守落 UNKNOWN。
            repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_RESULT_DOWNLOAD_FAILED", UNKNOWN_MESSAGE);
            return viewAfterWrite(principal, task.taskId());
        }
        MediaContentStore.StoredMedia stored;
        try {
            stored = contentStore.store(task.tenantId(), task.taskId(), task.ownerMemberId(), bytes, mime);
        } catch (RunApiException e) {
            // 同上下载失败：结果地址只存在于本次调用，无法在下一次查询重试。
            repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_RESULT_STORAGE_FAILED", UNKNOWN_MESSAGE);
            return viewAfterWrite(principal, task.taskId());
        }
        long units = unitsOf(KIND_AUDIO, receipt.usage(), request.text().length());
        try {
            usage.received(spendReservation(task), receipt.providerRequestId(), receipt.usage());
        } catch (RuntimeException e) {
            log.warn("media spend receipt update failed: task={} code={}",
                    task.taskId(), e instanceof RunApiException re ? re.errorCode() : "UNKNOWN");
        }
        repository.markSucceeded(task.tenantId(), task.taskId(), stored.objectKey(), stored.mimeType(),
                stored.sha256(), stored.sizeBytes(), usageJson(receipt.usage()), units, task.reservedCny());
        return viewAfterWrite(principal, task.taskId());
    }

    private MediaTaskView completeVideo(ExecutionPrincipal principal, MediaTaskRecord task, MediaVideoPoll poll) {
        if (poll.videoUrl() == null) {
            repository.markUnknown(task.tenantId(), task.taskId(), "PROVIDER_RESULT_MISSING", UNKNOWN_MESSAGE);
            return viewAfterWrite(principal, task.taskId());
        }
        try {
            byte[] bytes = videoProvider.download(poll.videoUrl(), properties.getVideo().getMaxBytes());
            String mime = videoProvider.sniffMime(bytes);
            MediaContentStore.StoredMedia stored;
            try {
                stored = contentStore.store(task.tenantId(), task.taskId(), task.ownerMemberId(), bytes, mime);
            } catch (RunApiException e) {
                return MediaTaskView.of(task, "结果已生成，私有化存储暂未完成；请稍后重新查询。");
            }
            long units = unitsOf(KIND_VIDEO, poll.usage(), 1L);
            try {
                usage.received(spendReservation(task), poll.providerRequestId(), poll.usage());
            } catch (RuntimeException e) {
                log.warn("media spend receipt update failed: task={} code={}",
                        task.taskId(), e instanceof RunApiException re ? re.errorCode() : "UNKNOWN");
            }
            repository.markSucceeded(task.tenantId(), task.taskId(), stored.objectKey(), stored.mimeType(),
                    stored.sha256(), stored.sizeBytes(), usageJson(poll.usage()), units, task.reservedCny());
            return viewAfterWrite(principal, task.taskId());
        } catch (MediaProviderUnavailableException e) {
            // 视频结果地址可在下一次轮询再次取得：保持 RUNNING，不重复计费。
            return MediaTaskView.of(task, "结果已生成，私有化存储暂未完成；请稍后重新查询。");
        } catch (MediaProviderRejectedException e) {
            repository.markFailed(task.tenantId(), task.taskId(), e.errorCode(), sanitize(e.getMessage()));
            return viewAfterWrite(principal, task.taskId());
        }
    }

    // ------------------------------------------------------------------ internals

    private MediaUsageMeter.Reserved spendReservation(MediaTaskRecord task) {
        return new MediaUsageMeter.Reserved(task.spendCallId(), task.reservedCny(), 1);
    }

    private static long unitsOf(String mediaType, Map<String, Object> usage, long fallback) {
        Long units = UsageLedgerService.mediaUnits(usage, mediaType);
        // provider 未给用量时用请求事实回退：image=1 张、audio=输入字符数、video=1 条。
        return units == null ? fallback : units;
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

    private MediaTaskRecord newTask(ExecutionPrincipal principal, String mediaType, String providerName,
                                    String requestId, String model, String prompt, String size, Integer seed,
                                    String previousTaskId, int attempt, String usageJson) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return new MediaTaskRecord(
                principal.tenantId(),
                "mt-" + UUID.randomUUID().toString().replace("-", ""),
                principal.membershipId(),
                principal.userId(),
                mediaType,
                providerName,
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
                usageJson,
                null, null,
                null,
                now, now);
    }

    private boolean stale(MediaTaskRecord task) {
        Instant updated = task.updatedAt();
        return updated == null || updated.plusMillis(AUDIO_RUNNING_GRACE_MS).isBefore(clock.instant());
    }

    private void requireConfigured(MediaProperties.Image image) {
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

    private void requireConfigured(MediaProperties.Audio audio) {
        if (!properties.isEnabled() || !audio.isEnabled()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "MEDIA_DISABLED: 媒体面未启用");
        }
        if (audio.getModelName() == null || audio.getModelName().isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_PROVIDER_NOT_CONFIGURED: 未配置语音模型");
        }
        if (audio.getApiKey() == null || audio.getApiKey().isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_PROVIDER_NOT_CONFIGURED: 语音提供方凭据未注入");
        }
    }

    private void requireConfigured(MediaProperties.Video video) {
        if (!properties.isEnabled() || !video.isEnabled()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "MEDIA_DISABLED: 媒体面未启用");
        }
        if (video.getModelName() == null || video.getModelName().isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_PROVIDER_NOT_CONFIGURED: 未配置视频模型");
        }
        if (video.getApiKey() == null || video.getApiKey().isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_PROVIDER_NOT_CONFIGURED: 视频提供方凭据未注入");
        }
    }

    private void requireEgress(String provider) {
        if (egress == null) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_EGRESS_UNAVAILABLE: 外发闸门未装配");
        }
        egress.requireAllowed(provider);
    }

    private MediaAudioRequest requireAudioRequest(AudioSubmitCommand command) {
        MediaProperties.Audio audio = properties.getAudio();
        String model = requireModel(command.model(), audio.getModelName());
        String text = command.input() == null ? "" : command.input().trim();
        if (text.isEmpty()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "语音文本不能为空");
        }
        if (text.length() > audio.getMaxTextLength()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST,
                    "MEDIA_AUDIO_TEXT_TOO_LONG: 文本超过长度上限");
        }
        String voice = command.voice() == null || command.voice().isBlank()
                ? audio.getDefaultVoice() : command.voice().trim();
        if (voice == null || !VOICE.matcher(voice).matches()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_AUDIO_VOICE_INVALID: 音色不合法");
        }
        String format = command.responseFormat() == null || command.responseFormat().isBlank()
                ? audio.getDefaultFormat() : command.responseFormat().trim().toLowerCase(Locale.ROOT);
        if (!AUDIO_FORMATS.contains(format)) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_AUDIO_FORMAT_INVALID: 不支持的音频格式");
        }
        if (command.speed() != null && (command.speed() < 0.1 || command.speed() > 4.0)) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_AUDIO_SPEED_INVALID: 语速超出范围");
        }
        String instructions = command.instructions() == null ? null : command.instructions().trim();
        if (instructions != null && instructions.isEmpty()) {
            instructions = null;
        }
        if (instructions != null && instructions.length() > audio.getMaxInstructionsLength()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST,
                    "MEDIA_AUDIO_INSTRUCTIONS_TOO_LONG: 朗读要求超过长度上限");
        }
        return new MediaAudioRequest(model, text, voice, format, command.speed(), instructions);
    }

    private MediaVideoRequest requireVideoRequest(VideoSubmitCommand command) {
        MediaProperties.Video video = properties.getVideo();
        String model = requireModel(command.model(), video.getModelName());
        String prompt = command.prompt() == null ? "" : command.prompt().trim();
        if (prompt.isEmpty()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        if (prompt.length() > video.getMaxPromptLength()) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "提示词超过长度上限");
        }
        String size = command.size() == null || command.size().isBlank()
                ? video.getDefaultSize() : command.size().trim();
        if (!allowedValues(video.getAllowedSizes()).contains(size)) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_SIZE_INVALID: 视频尺寸不在允许范围");
        }
        int seconds = command.seconds() == null ? video.getDefaultDurationSeconds() : command.seconds();
        if (!allowedDurations(video.getAllowedDurations()).contains(seconds)) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_DURATION_INVALID: 视频时长不在允许范围");
        }
        String quality = command.quality() == null ? null : command.quality().trim();
        if (quality != null && quality.isEmpty()) {
            quality = null;
        }
        if (quality != null && quality.length() > 64) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "MEDIA_QUALITY_INVALID: 画质说明过长");
        }
        return new MediaVideoRequest(model, prompt, size, seconds, quality);
    }

    private String requireModel(String requested, String configured) {
        if (configured == null || configured.isBlank()) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,
                    "MEDIA_PROVIDER_NOT_CONFIGURED: 未配置模型");
        }
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

    private String requestFactsJson(Map<String, Object> facts) {
        try {
            return mapper.writeValueAsString(Map.of("request", facts));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, Object> requestFacts(MediaTaskRecord task) {
        if (task.usageJson() == null || task.usageJson().isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = mapper.readTree(task.usageJson());
            JsonNode request = root.path("request");
            if (!request.isObject()) {
                return Map.of();
            }
            return mapper.convertValue(request, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, Object> audioFacts(MediaAudioRequest request) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("voice", request.voice());
        facts.put("format", request.format());
        if (request.speed() != null) {
            facts.put("speed", request.speed());
        }
        if (request.instructions() != null) {
            facts.put("instructions", request.instructions());
        }
        return facts;
    }

    private static Map<String, Object> videoFacts(MediaVideoRequest request) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("size", request.size());
        facts.put("seconds", request.seconds());
        if (request.quality() != null) {
            facts.put("quality", request.quality());
        }
        return facts;
    }

    private MediaAudioRequest audioRequestFromFacts(MediaTaskRecord previous) {
        Map<String, Object> facts = requestFacts(previous);
        return new MediaAudioRequest(previous.model(), previous.prompt(),
                stringFact(facts, "voice"), stringFact(facts, "format"),
                doubleFact(facts, "speed"), stringFact(facts, "instructions"));
    }

    private MediaVideoRequest videoRequestFromFacts(MediaTaskRecord previous) {
        Map<String, Object> facts = requestFacts(previous);
        Integer seconds = intFact(facts, "seconds");
        return new MediaVideoRequest(previous.model(), previous.prompt(),
                stringFact(facts, "size"), seconds, stringFact(facts, "quality"));
    }

    private static String stringFact(Map<String, Object> facts, String key) {
        Object value = facts.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Double doubleFact(Map<String, Object> facts, String key) {
        Object value = facts.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer intFact(Map<String, Object> facts, String key) {
        Object value = facts.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Set<String> allowedValues(String csv) {
        Set<String> values = new java.util.LinkedHashSet<>();
        if (csv == null) {
            return values;
        }
        for (String raw : csv.split(",")) {
            String value = raw.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static Set<Integer> allowedDurations(String csv) {
        Set<Integer> values = new java.util.LinkedHashSet<>();
        if (csv == null) {
            return values;
        }
        for (String raw : csv.split(",")) {
            String value = raw.trim();
            if (!value.isEmpty()) {
                try {
                    values.add(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    // 配置错误按"不允许"处理，由提交时拒绝暴露。
                }
            }
        }
        return values;
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
