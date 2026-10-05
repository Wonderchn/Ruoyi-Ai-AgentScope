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

package com.nageoffer.ai.ragent.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 专属测试环境的故障注入点（默认全部未布置；仅 {@code p2.test-control.enabled=true} 时可布置）。
 *
 * <p>布置后命中一次即抛出 {@link InjectedFault}，用于验收 A02（受理四步逐处失败）、
 * A11（阶段提交前后退出）、A16（relay 各窗口退出）。生产部署不得开启 test-control。
 */
@Component
public class P2FaultInjector {

    private final Map<String, AtomicInteger> armed = new ConcurrentHashMap<>();
    private final Map<String,Integer> pauses=new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public static class InjectedFault extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InjectedFault(String hook) {
            super("injected fault at " + hook);
        }
    }

    /** 布置一次性故障（下一次命中生效）。 */
    public void arm(String hook, int times) {
        armed.put(hook, new AtomicInteger(Math.max(1, times)));
        hits.computeIfAbsent(hook, key -> new AtomicInteger(0));
    }

    public void armPause(String hook,int times,int pauseMillis) {
        if(pauseMillis<0 || pauseMillis>30000) throw new IllegalArgumentException("pause outside bounded test window");
        pauses.put(hook,pauseMillis);arm(hook,times);
    }

    public void clear(String hook) {
        pauses.remove(hook);
        armed.remove(hook);
    }

    public void clearAll() {
        armed.clear();
        hits.clear();
        pauses.clear();
    }

    public Map<String, Integer> snapshot() {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        armed.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }

    /** 命中检查：armed 且仍有剩余次数 → 消耗一次并抛出。 */
    public void checkpoint(String hook) {
        AtomicInteger remaining = armed.get(hook);
        if (remaining == null) {
            return;
        }
        int value = remaining.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (value > 0) {
            hits.computeIfAbsent(hook, key -> new AtomicInteger(0)).incrementAndGet();
            if (remaining.get() == 0) {
                armed.remove(hook);
            }
            int pause=pauses.getOrDefault(hook,0);
            if(pause>0) {try{Thread.sleep(pause);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            throw new InjectedFault(hook);
        }
    }

    /** 是否已布置（不消耗）。 */
    public boolean isArmed(String hook) {
        AtomicInteger remaining = armed.get(hook);
        return remaining != null && remaining.get() > 0;
    }

    /** Bounded test rendezvous before an IO boundary; the caller must reauthorize afterward. */
    public void checkpointPause(String hook) {
        try { checkpoint(hook); } catch (InjectedFault observed) { /* Pause only, no crash. */ }
    }

    public int hits(String hook) {
        AtomicInteger counter = hits.get(hook);
        return counter == null ? 0 : counter.get();
    }
    public Map<String,Integer> hitSnapshot() {
        Map<String,Integer> result=new java.util.LinkedHashMap<>();
        hits.forEach((key,value)->result.put(key,value.get()));return Map.copyOf(result);
    }

    /** 故障点常量（与验收 runner 共享字符串）。 */
    public static final String ADMISSION_AFTER_RUN = "admission.afterRun";
    public static final String ADMISSION_AFTER_EVENT = "admission.afterEvent";
    public static final String ADMISSION_AFTER_RESERVE = "admission.afterReserve";
    public static final String ADMISSION_AFTER_OUTBOX = "admission.afterOutbox";
    public static final String ADMISSION_BEFORE_COMMIT = "admission.beforeCommit";
    public static final String OUTBOX_AFTER_CLAIM = "outbox.afterClaim";
    public static final String OUTBOX_AFTER_PUBLISH = "outbox.afterPublish";
    public static final String OUTBOX_BEFORE_MARK = "outbox.beforeMark";
    public static final String WORKER_AFTER_CLAIM = "worker.afterClaim";
    public static final String WORKER_BEFORE_STEP_COMMIT = "worker.beforeStepCommit";
    public static final String WORKER_AFTER_STEP_COMMIT = "worker.afterStepCommit";
    public static final String WORKER_BEFORE_TERMINAL = "worker.beforeTerminal";
    public static final String WORKER_ABANDON_RUN = "worker.abandonRun";
    public static final String MINERU_BEFORE_JOB = "mineru.beforeJob";
    public static final String MINERU_AFTER_JOB_CREATE = "mineru.afterJobCreate";
    public static final String EMBEDDING_AFTER_STAGING_CHUNK = "embedding.afterStagingChunk";
    public static final String PUBLISH_BEFORE_SWAP = "publish.beforeSwap";
    public static final String PUBLISH_AFTER_SWAP = "publish.afterSwap";
    public static final String CHAT_BEFORE_PROVIDER = "chat.beforeProvider";
    public static final String CHAT_AFTER_PROVIDER = "chat.afterProvider";
    public static final String UPLOAD_AFTER_OBJECT_STORE = "upload.afterObjectStore";
    public static final String UPLOAD_AFTER_DB = "upload.afterDb";
}
