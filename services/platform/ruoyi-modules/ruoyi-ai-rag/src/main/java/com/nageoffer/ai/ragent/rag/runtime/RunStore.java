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

package com.nageoffer.ai.ragent.rag.runtime;

import java.util.Optional;

/**
 * 受理账本的存储抽象。
 *
 * <p>拆出接口的原因（Spec §7.1 的两层证据）：单测层要在<b>无 PG</b> 的条件下验证
 * 幂等判定与错误映射，集成层才用真实 PG 验证唯一约束与事务原子性。因此单测用内存实现，
 * 集成用 {@link JdbcRunStore}。
 */
public interface RunStore {

    /**
     * 原子受理：同一事务写 run + event(seq=1) + outbox + ledger(reserve)。
     *
     * @return 已落库的 run
     * @throws IdempotencyConflict 唯一键冲突（并发同键）；由调用方重新读取既有 run
     */
    StoredRun accept(NewRun run);

    Optional<StoredRun> findByIdempotencyKey(String tenantId, String membershipId, String action,
                                             String idempotencyKey);

    /**
     * 记录一次性 jti。
     *
     * @return {@code false} 表示该 (issuer, jti) 已存在，即重放
     */
    boolean recordJti(String issuer, String jti);

    /** 已落库的 run。 */
    record StoredRun(String runId, String requestHash) {
    }

    /** 待落库的受理记录。 */
    record NewRun(String runId, String eventId, String outboxId, String ledgerId, String tenantId,
                  String membershipId, String subject, String action, String idempotencyKey,
                  String requestHash, int policyVersion, int aclVersion) {
    }

    /** 并发同键导致唯一键冲突。 */
    class IdempotencyConflict extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public IdempotencyConflict(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
