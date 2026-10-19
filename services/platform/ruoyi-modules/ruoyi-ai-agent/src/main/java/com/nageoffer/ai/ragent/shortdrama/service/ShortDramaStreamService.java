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

package com.nageoffer.ai.ragent.shortdrama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaDtos;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.llm.LlmOutputParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F21:0 从创意创建（SSE 流式）。
 *
 * <p>一次模型调用完成"大纲元信息 + 剧本正文"，随后才落库：
 * <ol>
 *   <li>模型增量到达时，遇到 {@code ===SCRIPT===} 之前的 JSON 元信息用于提示大纲完成；
 *       之后的正文逐段推送为 {@code stream} 帧；</li>
 *   <li>模型完成后解析全文并校验；失败则推 {@code error}，<b>不落库</b>；</li>
 *   <li>成功则通过 {@link ShortDramaWriteService} 在短事务内插入项目与脚本；</li>
 *   <li>客户端断开时取消上游流并释放幂等登记，不继续消耗预算。</li>
 * </ol>
 *
 * <p><b>落库线程没有 SaToken 上下文。</b>持久化通过 {@link ShortDramaWriteService} 的显式
 * 参数完成（actor 的 tenant/user 与仓库 SQL 谓词），不依赖线程上下文中的登录态；
 * 这正是选 {@code JdbcTemplate} 而不是 MyBatis 租户拦截器的原因。
 */
@Service
public class ShortDramaStreamService {

    private static final Logger log = LoggerFactory.getLogger(ShortDramaStreamService.class);

    private static final String SCRIPT_DELIMITER = "===SCRIPT===";
    private static final String DEFAULT_PROJECT_NAME = "短剧项目";
    private static final String DEFAULT_SCRIPT_NAME = "剧本";
    private static final String DEFAULT_TONE = "短剧";
    private static final String DEFAULT_ART_STYLE = "realistic";
    private static final String DEFAULT_ASPECT_RATIO = "9:16";
    private static final String DEFAULT_STATUS = "draft";
    private static final String SOURCE_AI = "ai";

    private final DramaLlmClient llmClient;
    private final ShortDramaWriteService writeService;
    private final ShortDramaProjectService projectService;
    private final DramaIdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    public ShortDramaStreamService(DramaLlmClient llmClient,
                                   ShortDramaWriteService writeService,
                                   ShortDramaProjectService projectService,
                                   DramaIdempotencyStore idempotencyStore,
                                   ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.writeService = writeService;
        this.projectService = projectService;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    public void streamCreateFromIdea(DramaActor actor,
                                     ShortDramaDtos.CreateFromIdeaRequest request,
                                     StreamSink sink) {
        if (request == null) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String idea = DramaInput.requireText(request.idea(), "idea", DramaInput.MAX_IDEA);
        String requestedName = DramaInput.optionalText(request.projectName(), "projectName",
                DramaInput.MAX_PROJECT_NAME);
        String artStyle = DramaInput.artStyleOr(request.artStyle(), DEFAULT_ART_STYLE);
        String aspectRatio = DramaInput.aspectRatioOr(request.aspectRatio(), DEFAULT_ASPECT_RATIO);
        String key = DramaInput.idempotencyKey(request.idempotencyKey());

        if (key != null) {
            Optional<Long> completed = idempotencyStore.completed(actor, key);
            if (completed.isPresent()) {
                replay(actor, completed.get(), sink);
                return;
            }
            if (!idempotencyStore.claim(actor, key)) {
                throw new P04AiException(P04AiErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "相同幂等键的请求正在处理中");
            }
        }

        if (sink.isClosed()) {
            release(actor, key);
            return;
        }

        String promptName = DramaInput.firstNonBlank(requestedName, DEFAULT_PROJECT_NAME);
        StringBuilder buffer = new StringBuilder();
        AtomicBoolean delimiterSeen = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        AtomicReference<StreamCancellationHandle> handle = new AtomicReference<>();

        sink.phase("polish", "running", "正在生成故事大纲...");

        StreamCallback callback = new StreamCallback() {
            @Override
            public void onContent(String content) {
                if (content == null || content.isEmpty() || finished.get()) {
                    return;
                }
                if (sink.isClosed()) {
                    cancelUpstream(handle, cancelRequested);
                    if (finished.compareAndSet(false, true)) {
                        release(actor, key);
                    }
                    return;
                }
                buffer.append(content);
                if (!delimiterSeen.get()) {
                    int delimiter = buffer.indexOf(SCRIPT_DELIMITER);
                    if (delimiter >= 0) {
                        delimiterSeen.set(true);
                        sink.phase("outline", "done", "大纲已生成，正在展开完整剧本...");
                        // 分隔符后通常紧跟换行；正文首段去掉前导空白，避免客户端
                        // 在剧本开头渲染一个空行。后续增量原样透传（行间换行有意义）。
                        String tail = buffer.substring(delimiter + SCRIPT_DELIMITER.length())
                                .stripLeading();
                        if (!tail.isBlank()) {
                            sink.stream("script", tail);
                        }
                    }
                } else {
                    sink.stream("script", content);
                }
            }

            @Override
            public void onComplete() {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                if (cancelRequested.get()) {
                    release(actor, key);
                    return;
                }
                try {
                    LlmOutputParser.ScriptEnvelope envelope =
                            llmClient.parseScript(buffer.toString());
                    String projectName = DramaInput.firstNonBlank(
                            requestedName, envelope.projectName(), DEFAULT_PROJECT_NAME);
                    ShortDramaWriteService.ProjectDraft projectDraft =
                            new ShortDramaWriteService.ProjectDraft(projectName,
                                    DramaInput.firstNonBlank(envelope.description()),
                                    DEFAULT_STATUS, artStyle, aspectRatio);
                    ShortDramaWriteService.ScriptDraft scriptDraft =
                            new ShortDramaWriteService.ScriptDraft(
                                    DramaInput.firstNonBlank(envelope.scriptName(),
                                            DEFAULT_SCRIPT_NAME),
                                    envelope.scriptText(),
                                    envelope.outlineText(),
                                    DramaInput.firstNonBlank(envelope.tone(), DEFAULT_TONE),
                                    SOURCE_AI);
                    ShortDramaWriteService.CreatedIds created =
                            writeService.createProjectWithScript(actor, projectDraft, scriptDraft);
                    if (key != null) {
                        idempotencyStore.complete(actor, key, created.projectId());
                    }
                    sink.phase("persist", "done", "项目已保存");
                    sink.complete(completeJson(created.projectId(), created.scriptId()));
                } catch (RuntimeException e) {
                    release(actor, key);
                    log.warn("短剧流式落库失败: type={}", e.getClass().getSimpleName());
                    sink.error(errorJson("生成失败，请稍后重试"));
                }
            }

            @Override
            public void onError(Throwable error) {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                release(actor, key);
                log.warn("短剧流式生成失败: type={}",
                        error == null ? "unknown" : error.getClass().getSimpleName());
                sink.error(errorJson("生成失败，请稍后重试"));
            }
        };

        try {
            StreamCancellationHandle cancellation =
                    llmClient.streamScript(promptName, idea, callback);
            if (cancellation != null) {
                handle.set(cancellation);
            }
            if (cancelRequested.get()) {
                cancelUpstream(handle, cancelRequested);
            }
        } catch (RuntimeException e) {
            release(actor, key);
            throw e;
        }
    }

    private void replay(DramaActor actor, long projectId, StreamSink sink) {
        ShortDramaDtos.ProjectDetailView detail = projectService.getProject(actor, projectId);
        long scriptId = detail.script() == null ? 0L : detail.script().id();
        sink.complete(completeJson(projectId, scriptId));
    }

    private static void cancelUpstream(AtomicReference<StreamCancellationHandle> handle,
                                       AtomicBoolean cancelRequested) {
        cancelRequested.set(true);
        StreamCancellationHandle current = handle.get();
        if (current != null) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {
                // 取消失败不能反向打断请求线程；上游客户端自身有超时。
            }
        }
    }

    private void release(DramaActor actor, String key) {
        if (key != null) {
            idempotencyStore.release(actor, key);
        }
    }

    private String completeJson(long projectId, long scriptId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", projectId);
        data.put("scriptId", scriptId);
        return json(data, "{\"projectId\":" + projectId + "}");
    }

    private String errorJson(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        return json(data, "{\"message\":\"" + message + "\"}");
    }

    private String json(Map<String, Object> data, String fallback) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return fallback;
        }
    }
}
