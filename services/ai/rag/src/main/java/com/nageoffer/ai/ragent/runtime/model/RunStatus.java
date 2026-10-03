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

package com.nageoffer.ai.ragent.runtime.model;

import java.util.Set;

/** 运行状态机常量与终态判定（P0.3 §4 契约）。 */
public final class RunStatus {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String RETRY_WAIT = "RETRY_WAIT";
    public static final String RECOVERING = "RECOVERING";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    /** 终态：一旦写入不可被晚到消息覆盖；不可 resume。 */
    public static final Set<String> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED);

    /** 可 resume 的非终态白名单（P2 首期）。 */
    public static final Set<String> RESUMABLE = Set.of(RECOVERING, RETRY_WAIT, CANCEL_REQUESTED);

    /** Worker 可认领的状态。 */
    public static final Set<String> CLAIMABLE = Set.of(QUEUED, RETRY_WAIT);

    private RunStatus() {
    }

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }
}
