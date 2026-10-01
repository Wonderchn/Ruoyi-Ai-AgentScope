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

package org.ruoyi.aiintegration.p04;

import jakarta.servlet.http.HttpServletResponse;
import org.ruoyi.aiintegration.authorization.PlatformFaultInjector;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * <b>测试专用</b>的授权端点故障注入（Spec §8.6）。
 *
 * <p>该实现只存在于测试源集；主源集只有接口调用点，通过 {@code ObjectProvider} 查找，
 * 未部署本实现时故障注入自动失效——因此生产路径不存在该开关。
 *
 * <p>支持的取值：
 * <ul>
 *   <li>{@code sleep:<秒>} —— 拖到超出 AI 侧客户端超时；</li>
 *   <li>{@code error503} —— 直接以 503 AUTHORIZATION_UNAVAILABLE 收场；</li>
 *   <li>{@code badresponse} —— 写出畸形 JSON 并提交响应，模拟坏响应。</li>
 * </ul>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class P04PlatformFaultInjector implements PlatformFaultInjector {

    /**
     * 已布防的故障（测试控制面设置）。AI→platform 的调用无法逐请求带故障头，
     * 因此需要一个"布防一段时间"的入口。
     */
    private volatile String armedFault;
    private volatile long armedUntilMillis;

    /**
     * 最近在本端点观察到的 requestId（G4「两侧可串联」的证据）。
     *
     * <p>本方法在<b>每次</b>授权请求上都会被调用（无论是否布防故障），因此这里记录的
     * 就是 AI→platform 这一跳实际带来的 requestId；纯粹测试侧，不改生产契约。
     */
    private final java.util.Deque<String> seenRequestIds =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    private static final int SEEN_LIMIT = 50;

    /** 测试控制面：读取最近观察到的 requestId（新的在前）。 */
    public java.util.List<String> seenRequestIds() {
        return new java.util.ArrayList<>(seenRequestIds);
    }

    /** 测试控制面：清空观察记录。 */
    public void clearSeenRequestIds() {
        seenRequestIds.clear();
    }

    private void recordRequestId() {
        String id = org.ruoyi.aiintegration.web.RequestId.currentOrEmpty();
        if (!id.isEmpty()) {
            seenRequestIds.addFirst(id);
            while (seenRequestIds.size() > SEEN_LIMIT) {
                seenRequestIds.pollLast();
            }
        }
    }

    /** 测试控制面：布防一个故障，{@code seconds} 秒后自动失效。 */
    public void arm(String fault, int seconds) {
        this.armedFault = fault;
        this.armedUntilMillis = System.currentTimeMillis() + Math.max(0, seconds) * 1000L;
    }

    /** 清除布防。 */
    public void disarm() {
        this.armedFault = null;
        this.armedUntilMillis = 0L;
    }

    @Override
    public void beforeAuthorization(String faultHeader) {
        recordRequestId();
        String fault = faultHeader;
        if (fault == null || fault.isBlank()) {
            String armed = this.armedFault;
            fault = (armed != null && System.currentTimeMillis() < armedUntilMillis) ? armed : null;
        }
        if (fault == null || fault.isBlank()) {
            return;
        }
        if (fault.startsWith("sleep:")) {
            long seconds = parseSeconds(fault.substring("sleep:".length()));
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE, "interrupted");
            }
            return;
        }
        if ("error503".equals(fault)) {
            throw new P04Exception(P04ErrorCode.AUTHORIZATION_UNAVAILABLE, "injected failure");
        }
        if ("badresponse".equals(fault)) {
            writeMalformedBodyAndCommit();
            throw new ResponseCommitted();
        }
    }

    private static long parseSeconds(String raw) {
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            throw new P04Exception(P04ErrorCode.BAD_REQUEST, "bad fault argument");
        }
    }

    private void writeMalformedBodyAndCommit() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new P04Exception(P04ErrorCode.INTERNAL_ERROR, "no request context");
        }
        HttpServletResponse response = attributes.getResponse();
        if (response == null) {
            throw new P04Exception(P04ErrorCode.INTERNAL_ERROR, "no servlet response");
        }
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":");
            response.flushBuffer();
        } catch (IOException e) {
            throw new P04Exception(P04ErrorCode.INTERNAL_ERROR, "failed to write malformed body");
        }
    }

    /** 响应已提交，后续异常处理不得再改写响应体。 */
    static final class ResponseCommitted extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
