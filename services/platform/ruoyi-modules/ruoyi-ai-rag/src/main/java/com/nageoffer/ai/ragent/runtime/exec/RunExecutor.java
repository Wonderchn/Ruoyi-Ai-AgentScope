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

/** 动作执行器：每个受支持 action 一个实现；未知 action 明确失败，不静默降级。 */
public interface RunExecutor {

    String action();

    /** 执行并返回结果；抛出异常 = 失败（由 Worker 分类为 FAILED/RETRY_WAIT）。 */
    Outcome execute(RunExecution execution) throws Exception;

    record Outcome(String status, Object terminalResult, String errorCode) {

        public static Outcome succeeded(Object terminalResult) {
            return new Outcome("SUCCEEDED", terminalResult, null);
        }

        public static Outcome failed(String errorCode) {
            return new Outcome("FAILED", java.util.Map.of(), errorCode);
        }
    }
}
