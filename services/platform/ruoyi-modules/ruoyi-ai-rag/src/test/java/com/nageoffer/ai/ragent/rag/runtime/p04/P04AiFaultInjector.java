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

package com.nageoffer.ai.ragent.rag.runtime.p04;

import com.nageoffer.ai.ragent.rag.runtime.AcceptanceFaultHook;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;

/**
 * <b>测试专用</b>的受理故障注入实现（Spec §7.4 的 F01、§8.6）。
 *
 * <p>只存在于测试源集：主源集只有接口与调用点，未部署本实现时故障注入自动失效。
 *
 * <p>请求头 {@code X-P04-Fault} 取值（实际实现，逐字核对）：
 * <ul>
 *   <li>{@code before-commit} —— {@code JdbcRunStore.accept} 四类记录写入后、提交前抛出，
 *       整体回滚（F01，等价于在最后写入点注入的记录：run/event/reserve/outbox）；</li>
 *   <li>{@code before-commit:run|event|reserve|outbox} —— 在对应那一条 INSERT 之后、
 *       仍处于同一事务内时抛出（F01 的四个分阶段注入点）；</li>
 *   <li>{@code after-commit} —— 提交成功后抛出。这是<b>遗留的响应丢失近似</b>：
 *       现行 F02 判据已改为真实 TCP 代理在 202 提交后直接断开客户端连接
 *       （{@code tools/p04-contract/run.ps1} 的 {@code Invoke-DroppedResponse}），
 *       集成运行不再使用该取值；保留仅为单元层可能的对照用例。</li>
 * </ul>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
@Tag("dev")
public class P04AiFaultInjector implements AcceptanceFaultHook {

    private volatile DecisionBarrier barrier;

    public record BarrierStatus(String position, String key, int expected, int arrived, boolean released) {
    }

    private static final class DecisionBarrier {
        final String key;
        final String position;
        final int expected;
        final AtomicInteger arrived = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);

        DecisionBarrier(String position, String key, int expected) {
            this.position = position;
            this.key = key;
            this.expected = expected;
        }
    }

    public synchronized BarrierStatus armBarrier(String position, String key, int expected) {
        if (!("jti".equals(position) || "idempotency".equals(position))
                || key == null || key.isBlank() || expected < 2 || expected > 40) {
            throw new IllegalArgumentException("barrier requires a key and 2..40 participants");
        }
        if (barrier != null && barrier.release.getCount() != 0) {
            throw new IllegalStateException("previous decision barrier is still armed");
        }
        barrier = new DecisionBarrier(position, key, expected);
        return barrierStatus();
    }

    public BarrierStatus barrierStatus() {
        DecisionBarrier current = barrier;
        return current == null ? new BarrierStatus("", "", 0, 0, true)
                : new BarrierStatus(current.position, current.key, current.expected, current.arrived.get(),
                        current.release.getCount() == 0);
    }

    public synchronized BarrierStatus releaseBarrier() {
        DecisionBarrier current = barrier;
        if (current == null || current.arrived.get() != current.expected) {
            throw new IllegalStateException("all participants must reach the decision barrier before release");
        }
        current.release.countDown();
        return barrierStatus();
    }

    @Override
    public void beforeIdempotencyDecision(String idempotencyKey) {
        awaitDecision("idempotency", idempotencyKey);
    }

    @Override
    public void beforeJtiDecision(String jti) {
        awaitDecision("jti", jti);
    }

    private void awaitDecision(String position, String key) {
        DecisionBarrier current = barrier;
        if (current == null || !current.position.equals(position) || !current.key.equals(key)) {
            return;
        }
        int count = current.arrived.incrementAndGet();
        if (count > current.expected) {
            throw new IllegalStateException("too many decision barrier participants");
        }
        try {
            if (!current.release.await(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("decision barrier timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("decision barrier interrupted", e);
        }
    }

    /** 故障注入请求头。 */
    public static final String FAULT_HEADER = "X-P04-Fault";

    private static final String BEFORE_COMMIT = "before-commit";

    private static final String AFTER_COMMIT = "after-commit";

    @Override
    public void afterWrite(String stage, String runId) {
        if ((BEFORE_COMMIT + ":" + stage).equals(currentFault())) {
            throw new InjectedFault("injected fault after " + stage + " write before commit");
        }
    }

    @Override
    public void beforeCommit(String runId) {
        if (BEFORE_COMMIT.equals(currentFault())) {
            throw new InjectedFault("injected fault before commit");
        }
    }

    @Override
    public void afterCommit(String runId) {
        if (AFTER_COMMIT.equals(currentFault())) {
            throw new InjectedFault("injected fault after commit (response dropped)");
        }
    }

    private static String currentFault() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String value = request.getHeader(FAULT_HEADER);
        return value == null ? null : value.trim();
    }

    /** 注入的故障；由全局 advice 映射为非 2xx。 */
    public static final class InjectedFault extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InjectedFault(String message) {
            super(message);
        }
    }
}
