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

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.security.AiRequestIdFilter;
import com.nageoffer.ai.ragent.framework.security.AuthorizationChecker;
import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipal;
import com.nageoffer.ai.ragent.framework.security.DelegationVerifier;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 最小原子受理（P0.4 的核心用例）。
 *
 * <p>固定顺序（Spec §7.2；顺序本身是被断言的对象）：
 * <ol>
 *   <li>验签（401）；</li>
 *   <li>请求体结构校验、拒绝伪造身份字段（403）；</li>
 *   <li>租户上下文齐备（403）；</li>
 *   <li>jti 防重放（并发同 jti 只允许一个成功）；</li>
 *   <li><b>在线授权复核——必须在查幂等命中之前</b>，否则撤权后能借重放取回结果；</li>
 *   <li>资源 ACL（404 统一口径）；</li>
 *   <li>规范请求哈希 + 持久幂等裁决（同键同体复用 runId；同键不同体 409）；</li>
 *   <li>唯一事务写 run/event/outbox/ledger 后才返回 202。</li>
 * </ol>
 *
 * <p>不实现在实验中明确排除的东西：Worker、事件回放、MQ 投递、真实额度竞争与结算（属 P2）。
 */
@Service
@ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class RunAcceptanceService {

    /** 受理事件类型。 */
    public static final String EVENT_RUN_ACCEPTED = "run.accepted";

    private final DelegationVerifier verifier;
    private final AuthorizationChecker authorizationClient;
    private final AclProvider aclProvider;
    private final RunStore runStore;
    private final RequestHasher hasher;
    private final ObjectProvider<AcceptanceFaultHook> faultHook;

    public RunAcceptanceService(DelegationVerifier verifier, AuthorizationChecker authorizationClient,
                               AclProvider aclProvider, RunStore runStore, RequestHasher hasher,
                               ObjectProvider<AcceptanceFaultHook> faultHook) {
        this.verifier = verifier;
        this.authorizationClient = authorizationClient;
        this.aclProvider = aclProvider;
        this.runStore = runStore;
        this.hasher = hasher;
        this.faultHook = faultHook;
    }

    /**
     * @param replayed 命中已有幂等记录时为 {@code true}
     */
    public record Outcome(String runId, String requestId, boolean replayed) {
    }

    public Outcome accept(String bearerToken, String idempotencyKey, String rawBody) {
        DelegationVerifier.DelegationClaims claims = verifier.verify(bearerToken);

        // parse 内部已按 §3.1 对"请求体自带身份字段"判 403，此处不再重复判定
        JsonNode body = hasher.parse(rawBody);
        DelegatedPrincipal principal = verifier.requireTenantContext(claims);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new P04AiException(P04AiErrorCode.BAD_REQUEST, "Idempotency-Key is required");
        }

        // 防重放不可用则拒绝，不回退为内存判定
        if (!runStore.recordJti(principal.issuer(), principal.jti())) {
            throw new P04AiException(P04AiErrorCode.DELEGATION_INVALID);
        }

        String action = hasher.action(body);
        List<String> resourceRefs = hasher.resourceRefs(body);
        String firstResource = resourceRefs.isEmpty() ? null : resourceRefs.get(0);

        // 权限检查先行：撤权后不能靠重放拿回原 runId
        authorizationClient.check(principal, action, firstResource);

        for (String ref : resourceRefs) {
            if (!aclProvider.canAccess(principal.tenantId(), principal.membershipId(), action, ref)) {
                throw new P04AiException(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN);
            }
        }

        String requestHash = hasher.hash(body);
        Optional<RunStore.StoredRun> existing = runStore.findByIdempotencyKey(principal.tenantId(),
                principal.membershipId(), action, idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        RunStore.NewRun newRun = new RunStore.NewRun(newId(), newId(), newId(), newId(),
                principal.tenantId(), principal.membershipId(), principal.subject(), action,
                idempotencyKey, requestHash, principal.policyVersion());
        try {
            RunStore.StoredRun stored = runStore.accept(newRun);
            // F02：事务已提交（accept 的 @Transactional 已在返回前提交）；此处抛出用于模拟
            // "提交成功但响应丢失"，客户端看到非 202，而业务记录确实存在。
            faultHook.ifAvailable(hook -> hook.afterCommit(stored.runId()));
            return new Outcome(stored.runId(), currentRequestId(), false);
        } catch (RunStore.IdempotencyConflict conflict) {
            // 并发同键由数据库唯一约束裁决：读取胜出者并按同键规则收场
            Optional<RunStore.StoredRun> winner = runStore.findByIdempotencyKey(principal.tenantId(),
                    principal.membershipId(), action, idempotencyKey);
            if (winner.isEmpty()) {
                throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR);
            }
            return replayOrConflict(winner.get(), requestHash);
        }
    }

    private Outcome replayOrConflict(RunStore.StoredRun stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new P04AiException(P04AiErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new Outcome(stored.runId(), currentRequestId(), true);
    }

    private static String currentRequestId() {
        return AiRequestIdFilter.currentOrEmpty();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
