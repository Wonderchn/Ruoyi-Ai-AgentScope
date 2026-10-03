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

package com.nageoffer.ai.ragent.runtime.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.dao.RunLedgerDao;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import com.nageoffer.ai.ragent.runtime.model.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 事件流：数据库连续游标回放 + 实时追赶 + 有界缓冲 + 当前授权复核。
 *
 * <p>契约（P0.3 §3.3 / P2 U03）：回放覆盖 (afterSeq, H] 每个连续 seq；实时期间发现
 * 空洞回到回放补齐；保留期外 410 + 快照；缓冲超限只断订阅（≠410）；鉴权失效立即
 * 停止后续受限输出；订阅断开不改变运行。
 */
@Service
public class RunEventStreamService {

    private static final Logger log = LoggerFactory.getLogger(RunEventStreamService.class);

    private final RunLedgerDao dao;
    private final RunLifecycleService lifecycle;
    private final NotificationBus bus;
    private final P2RuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization;
    private final com.nageoffer.ai.ragent.runtime.web.DeliveryPermits permits;

    public RunEventStreamService(RunLedgerDao dao, RunLifecycleService lifecycle, NotificationBus bus,
                                 P2RuntimeProperties properties, ObjectMapper objectMapper,
                                 ObjectProvider<com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService> authorization,
                                 com.nageoffer.ai.ragent.runtime.web.DeliveryPermits permits) {
        this.dao = dao;
        this.lifecycle = lifecycle;
        this.bus = bus;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.permits = permits;
    }

    /** 入口：鉴权 → 保留期检查 → 流式输出。返回 false 表示已写错误响应。 */
    public boolean stream(ExecutionPrincipal principal, String runId, long afterSeq, HttpServletResponse response)
            throws IOException {
        RunRecord run = dao.findRun(principal.tenantId(), runId).orElse(null);
        if (run == null) {
            writeJson(response, 404, errorBody(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "run not found"));
            return false;
        }
        if (!authorize(principal, runId)) {
            writeJson(response, 403, errorBody(RunErrorCode.FORBIDDEN, "stream not authorized"));
            return false;
        }
        long min = dao.minSeq(principal.tenantId(), runId);
        Instant oldest = dao.oldestEventAt(principal.tenantId(), runId);
        boolean retentionExpired = oldest != null
                && Duration.between(oldest, Instant.now()).toHours() >= properties.getEvents().getRetentionHours();
        if (afterSeq > 0 && min > 0 && afterSeq < min - 1) {
            writeCursorExpired(response, principal, runId, afterSeq, run);
            return false;
        }
        if (afterSeq == 0 && min > 1) {
            writeCursorExpired(response, principal, runId, 0, run);
            return false;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        try {
            response.setBufferSize(8192);
        } catch (IllegalStateException ignored) {
            // 容器已提交时不调整
        }

        BoundedSink sink = new BoundedSink(response, properties.getEvents().getBufferMaxFrames(),
                properties.getEvents().getBufferMaxBytes(),
                Duration.ofSeconds(properties.getEvents().getHeartbeatSeconds()).toMillis(), content -> {
                    if (content.startsWith(": ping")) return content;
                    if (!authorize(principal, runId)) throw new RunApiException(RunErrorCode.FORBIDDEN, "stream revoked");
                    var permit = permits.enter(principal, "run.stream", "run:" + runId);
                    // Only the final gateway can confirm application delivery has ended.
                    return ": ai-delivery " + permit.permitId() + " " + permit.operationId() + "\n" + content;
                });
        AtomicBoolean closed = new AtomicBoolean(false);
        Thread writer = sink.startWriter(closed);
        AutoCloseable subscription = bus.subscribe(principal.tenantId(), runId, seq -> sink.wake());
        try {
            runStreamLoop(principal, run, afterSeq, sink, closed);
        } catch (RuntimeException e) {
            closed.set(true);
            throw e;
        } finally {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // 忽略
            }
            sink.finish();
            try {
                writer.join(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.close();
            writer.interrupt();
        }
        return true;
    }

    private void runStreamLoop(ExecutionPrincipal principal, RunRecord run, long afterSeq,
                               BoundedSink sink, AtomicBoolean closed) {
        String tenantId = run.tenantId();
        String runId = run.runId();
        long cursor = afterSeq;
        while (!closed.get() && !sink.isClosed()) {
            long high = dao.maxSeq(tenantId, runId);
            if (high > cursor) {
                List<RunLedgerDao.EventRow> batch = dao.listEvents(tenantId, runId, cursor,
                        properties.getEvents().getReplayBatch());
                long expected = cursor + 1;
                for (RunLedgerDao.EventRow row : batch) {
                    if (row.seq() != expected) {
                        // 持久层出现空洞（保留期删除/异常）：明确告知客户端游标过期，不伪造连续
                        sink.enqueue(frame("stream.cursor_expired", cursor,
                                envelope(tenantId, runId, cursor, "e-stream-cursor-expired", "stream.cursor_expired",
                                        Map.of("lastSeq", cursor, "snapshot", snapshot(run)))));
                        sink.finish();
                        return;
                    }
                    expected++;
                }
                for (RunLedgerDao.EventRow row : batch) {
                    if (closed.get()) {
                        return;
                    }
                    String type = row.type();
                    Map<String, Object> envelope = envelope(tenantId, runId, row.seq(), row.eventId(), type,
                            parsePayload(row.payloadJson(), row.seq(), type, run));
                    if (!sink.enqueue(frame(type, row.seq(), envelope))) {
                        // 缓冲超限：断订阅（客户端从最后连续游标回补；不是 410）
                        closed.set(true);
                        return;
                    }
                    cursor = row.seq();
                    if ("run.terminal".equals(type)) {
                        sink.finish();
                        return;
                    }
                }
                continue;
            }
            // 无新事件：心跳 + 短暂等待（通知只是加速，轮询是扫描补偿）
            if (!authorize(principal, runId) || !sink.heartbeatIfIdle()) {
                closed.set(true);
                return;
            }
            sink.awaitWake(properties.getEvents().getPollIntervalMs());
        }
    }

    private boolean authorize(ExecutionPrincipal principal, String runId) {
        var service = authorization.getIfAvailable();
        if (service == null) {
            return false;
        }
        try {
            lifecycle.get(principal,runId);
            return service.check(principal, "run.stream", "run:" + runId)
                    == com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict.GRANT;
        } catch (RuntimeException e) {
            // 授权源故障：拒绝而不是放行
            return false;
        }
    }

    private Map<String, Object> parsePayload(String payloadJson, long seq, String type, RunRecord run) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "persisted event payload is invalid at seq " + seq);
        }
    }

    private Map<String, Object> envelope(String tenantId, String runId, long seq, String eventId, String type,
                                         Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("eventId", eventId);
        envelope.put("tenantId", tenantId);
        envelope.put("runId", runId);
        envelope.put("seq", seq);
        envelope.put("type", type);
        envelope.put("at", Instant.now());
        envelope.put("payload", payload);
        return envelope;
    }

    private Map<String, Object> snapshot(RunRecord run) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runId", run.runId());
        snapshot.put("status", run.status());
        snapshot.put("nextSeq", run.nextSeq());
        snapshot.put("terminalResult", run.terminalResultJson());
        return snapshot;
    }

    private void writeCursorExpired(HttpServletResponse response, ExecutionPrincipal principal,
                                    String runId, long afterSeq, RunRecord run) throws IOException {
        var permit = permits.enter(principal, "run.stream", "run:" + runId);
        response.setHeader("X-AI-Delivery-Permit", permit.permitId());
        response.setHeader("X-AI-Delivery-Operation", permit.operationId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", RunErrorCode.CURSOR_EXPIRED.name());
        data.put("retryable", false);
        data.put("lastSeq", afterSeq);
        data.put("snapshot", snapshot(run));
        writeJson(response, 410, Map.of("code", 410, "msg", RunErrorCode.CURSOR_EXPIRED.message(), "data", data));
    }

    private Map<String, Object> errorBody(RunErrorCode code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code.name());
        data.put("retryable", code.retryable());
        return Map.of("code", code.status().value(), "msg", message, "data", data);
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private String frame(String type, long seq, Object data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new RunApiException(RunErrorCode.INTERNAL_ERROR, "frame serialization failed");
        }
        return "id: " + seq + "\nevent: " + type + "\ndata: " + json + "\n\n";
    }

    /**
     * 有界缓冲输出：帧/字节双上限；超限即断订阅（不静默丢弃、不伪造 410）。
     * 写线程与流循环解耦，保证慢订阅者不会无限缓存，也不阻塞 Worker。
     */
    static final class BoundedSink {
        private final HttpServletResponse response;
        private final int maxFrames;
        private final long maxBytes;
        private final long heartbeatMs;
        private final BlockingQueue<String> queue;
        private final AtomicLong queuedBytes = new AtomicLong();
        private final AtomicBoolean overflow = new AtomicBoolean(false);
        private final Object wakeLock = new Object();
        private final AtomicLong lastWriteMs = new AtomicLong(System.currentTimeMillis());
        private volatile boolean closed = false;
        private volatile boolean finished = false;
        private final java.util.function.Function<String,String> protect;

        BoundedSink(HttpServletResponse response, int maxFrames, long maxBytes, long heartbeatMs,
                    java.util.function.Function<String,String> protect) {
            this.response = response;
            this.maxFrames = Math.max(1, maxFrames);
            this.maxBytes = Math.max(1024, maxBytes);
            this.heartbeatMs = Math.max(1000, heartbeatMs);
            this.queue = new ArrayBlockingQueue<>(this.maxFrames);
            this.protect = protect;
        }

        Thread startWriter(AtomicBoolean closedFlag) {
            Thread writer = new Thread(() -> {
                try {
                    var out = response.getOutputStream();
                    while (!closed && !closedFlag.get()) {
                        String frame = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (frame == null) {
                            if (finished && queue.isEmpty()) break;
                            continue;
                        }
                        queuedBytes.addAndGet(-frame.getBytes(StandardCharsets.UTF_8).length);
                        String protectedFrame = protect.apply(frame);
                        if (closed || closedFlag.get()) break;
                        out.write(protectedFrame.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        lastWriteMs.set(System.currentTimeMillis());
                    }
                } catch (IOException | RuntimeException e) {
                    // 客户端断开：只断订阅
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    closed = true;
                    closedFlag.set(true);
                    queue.clear();
                    wake();
                }
            }, "p2-sse-writer");
            writer.setDaemon(true);
            writer.start();
            return writer;
        }

        boolean enqueue(String frame) {
            int bytes = frame.getBytes(StandardCharsets.UTF_8).length;
            if (closed || finished || overflow.get()) {
                return false;
            }
            if (bytes > maxBytes) {
                overflow.set(true);
                return false;
            }
            if (queuedBytes.addAndGet(bytes) > maxBytes) {
                queuedBytes.addAndGet(-bytes);
                overflow.set(true);
                return false;
            }
            if (!queue.offer(frame)) {
                queuedBytes.addAndGet(-bytes);
                overflow.set(true);
                return false;
            }
            wake();
            return true;
        }

        void wake() {
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
        }

        void awaitWake(long millis) {
            synchronized (wakeLock) {
                try {
                    wakeLock.wait(Math.max(10, millis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** 空闲心跳（注释帧）；返回 false 表示已关闭。 */
        boolean heartbeatIfIdle() {
            if (closed || overflow.get()) {
                return false;
            }
            long idleMs = System.currentTimeMillis() - lastWriteMs.get();
            return idleMs < heartbeatMs || !queue.isEmpty() || enqueue(": ping\n\n");
        }

        void finish() { finished = true; wake(); }
        boolean isClosed() { return closed; }

        void close() {
            closed = true;
            wake();
        }
    }
}
