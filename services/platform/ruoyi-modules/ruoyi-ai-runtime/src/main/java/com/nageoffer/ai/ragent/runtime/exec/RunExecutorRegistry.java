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

package com.nageoffer.ai.ragent.runtime.exec;

import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动作执行器注册表。
 *
 * <p>{@code p2.executor.mode=synthetic} 时只使用显式假执行器（专属测试模式），
 * 与真实执行器互斥；未知 action 不静默降级。
 */
@Component
public class RunExecutorRegistry {

    private final Map<String, RunExecutor> byAction = new LinkedHashMap<>();
    private final ObjectProvider<SyntheticRunExecutor> synthetic;
    private final boolean syntheticMode;

    public RunExecutorRegistry(List<RunExecutor> executors, ObjectProvider<SyntheticRunExecutor> synthetic,
                               P2RuntimeProperties properties) {
        for (RunExecutor executor : executors) {
            if (executor instanceof SyntheticRunExecutor) {
                continue;
            }
            byAction.put(executor.action(), executor);
        }
        this.synthetic = synthetic;
        this.syntheticMode = "synthetic".equalsIgnoreCase(properties.getExecutor().getMode());
    }

    public boolean isSyntheticMode() {
        return syntheticMode;
    }

    public Optional<RunExecutor> resolve(String action) {
        if (syntheticMode) {
            return Optional.ofNullable(synthetic.getIfAvailable());
        }
        return Optional.ofNullable(byAction.get(action));
    }
}
