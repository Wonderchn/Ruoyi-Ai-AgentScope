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

import com.nageoffer.ai.ragent.runtime.model.RunRecord;

/** 执行上下文：executor 通过 guard 提交事实，不直接写库。 */
public record RunExecution(RunRecord run, RunExecutionGuard guard) {

    public String tenantId() {
        return run.tenantId();
    }

    public String runId() {
        return run.runId();
    }

    public int attempt() {
        return guard.attempt();
    }

    public long fence() {
        return guard.fence();
    }
}
