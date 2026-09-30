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

/**
 * <b>测试专用</b>的受理故障注入实现（Spec §7.4 的 F01/F02、§8.6）。
 *
 * <p>只存在于测试源集：主源集只有接口与调用点，未部署本实现时故障注入自动失效。
 *
 * <p>请求头 {@code X-P04-Ai-Fault} 取值：
 * <ul>
 *   <li>{@code before-commit} —— 四类记录写入后、提交前抛出，整体回滚（F01）；</li>
 *   <li>{@code after-commit} —— 提交成功后抛出，模拟"提交成功但响应丢失"（F02）。</li>
 * </ul>
 */
@Component
public class P04AiFaultInjector implements AcceptanceFaultHook {

    /** 故障注入请求头。 */
    public static final String FAULT_HEADER = "X-P04-Ai-Fault";

    private static final String BEFORE_COMMIT = "before-commit";

    private static final String AFTER_COMMIT = "after-commit";

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
