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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nageoffer.ai.ragent.framework.security.AuthorizationChecker;
import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipal;
import com.nageoffer.ai.ragent.framework.security.DelegationVerifier;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.framework.security.P04SecurityProperties;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小受理的无外部服务单测（Spec §7.1 第一层 / §8.6 Unit 模式必跑类之一）。
 *
 * <p>用内存存储替身验证编排与判定，<b>不</b>触碰 PG/Redis/MQ/模型；真实 PG 唯一约束与事务
 * 原子性由集成层的 P01/I01–I06/F01–F03 覆盖。其中"权限检查必须先于幂等命中"是被显式断言的
 * 顺序约束——否则撤权后可借重放取回结果。
 *
 * <p>测试类带 {@code dev} 标签（用户长期要求）。
 */
@Tag("dev")
class P04RunAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private static final String KID = "p04-platform-k1";

    private static final String LEGAL_BODY = "{\"schemaVersion\":1,\"action\":\"rag.chat\","
            + "\"input\":{\"text\":\"synthetic-p04\"},\"resourceRefs\":[\"KB-A\"]}";

    private ObjectMapper objectMapper;
    private RequestHasher hasher;
    private InMemoryRunStore store;
    private RecordingChecker checker;
    private RunAcceptanceService service;
    private KeyPair trusted;

    /** 内存存储替身：记录调用顺序，便于断言编排顺序。 */
    static final class InMemoryRunStore implements RunStore {

        final List<String> calls = new ArrayList<>();
        final Map<String, StoredRun> byKey = new LinkedHashMap<>();
        final Map<String, String> keyToHash = new LinkedHashMap<>();
        final java.util.Set<String> jtis = new java.util.LinkedHashSet<>();
        int runs;
        int events;
        int outbox;
        int ledger;

        @Override
        public StoredRun accept(NewRun run) {
            calls.add("accept");
            runs++;
            events++;
            outbox++;
            ledger++;
            String composite = run.tenantId() + "|" + run.membershipId() + "|" + run.action() + "|"
                    + run.idempotencyKey();
            byKey.put(composite, new StoredRun(run.runId(), run.requestHash()));
            keyToHash.put(composite, run.requestHash());
            return new StoredRun(run.runId(), run.requestHash());
        }

        @Override
        public Optional<StoredRun> findByIdempotencyKey(String tenantId, String membershipId, String action,
                                                        String idempotencyKey) {
            calls.add("lookup");
            String composite = tenantId + "|" + membershipId + "|" + action + "|" + idempotencyKey;
            return Optional.ofNullable(byKey.get(composite));
        }

        @Override
        public boolean recordJti(String issuer, String jti) {
            calls.add("jti");
            return jtis.add(issuer + "|" + jti);
        }
    }

    /** 授权复核替身：记录调用顺序，并可注入失败。 */
    static final class RecordingChecker implements AuthorizationChecker {

        final List<String> calls;
        P04AiErrorCode failure;

        RecordingChecker(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public AuthorizeResult check(DelegatedPrincipal principal, String action, String resourceRef) {
            calls.add("authz");
            if (failure != null) {
                throw new P04AiException(failure);
            }
            return new AuthorizeResult(true, principal.policyVersion());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        hasher = new RequestHasher(objectMapper);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        trusted = generator.generateKeyPair();

        Path dir = Path.of("target", "p04-test");
        Files.createDirectories(dir);
        Path pem = dir.resolve("platform-public.pem");
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(trusted.getPublic().getEncoded());
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n",
                StandardCharsets.UTF_8);

        P04SecurityProperties properties = new P04SecurityProperties();
        properties.getDelegation().setPublicKeyPath(pem.toAbsolutePath().toString());
        DelegationVerifier verifier = new DelegationVerifier(properties, Clock.fixed(NOW, ZoneOffset.UTC));

        store = new InMemoryRunStore();
        checker = new RecordingChecker(store.calls);
        service = new RunAcceptanceService(verifier, checker,
                (tenantId, membershipId, action, resourceRef) -> "KB-A".equals(resourceRef), store, hasher,
                noFaultHook());
    }

    /** 单测层不注入故障：显式给一个"永远没有实现"的提供者，与 Spring 装配语义保持一致。 */
    private static ObjectProvider<AcceptanceFaultHook> noFaultHook() {
        return new ObjectProvider<>() {
            @Override
            public AcceptanceFaultHook getObject() {
                throw new NoSuchBeanDefinitionException(AcceptanceFaultHook.class);
            }

            @Override
            public AcceptanceFaultHook getObject(Object... args) {
                throw new NoSuchBeanDefinitionException(AcceptanceFaultHook.class);
            }

            @Override
            public void ifAvailable(java.util.function.Consumer<AcceptanceFaultHook> consumer) {
                // 无实现即不注入
            }
        };
    }

    private String token(String tenantId, String membershipId, int policyVersion, String action) {
        return Jwts.builder()
                .header().keyId(KID).type("JWT").and()
                .issuer("platform")
                .audience().add("ai").and()
                .subject("sub-u1")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(NOW))
                .notBefore(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .claim("tid", tenantId)
                .claim("mid", membershipId)
                .claim("pv", policyVersion)
                .claim("scope", List.of("rag.chat.submit"))
                .signWith(trusted.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static P04AiErrorCode codeOf(Executable executable) {
        P04AiException ex = assertThrows(P04AiException.class, executable::run);
        return ex.errorCode();
    }

    private interface Executable {
        void run() throws Throwable;
    }

    @Test
    @DisplayName("合法委托被受理：一次 run/event/outbox/ledger，replayed=false")
    void legalAcceptWritesFourRecords() {
        RunAcceptanceService.Outcome outcome =
                service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY);

        assertFalse(outcome.replayed());
        assertFalse(outcome.runId().isBlank());
        assertEquals(1, store.runs);
        assertEquals(1, store.events);
        assertEquals(1, store.outbox);
        assertEquals(1, store.ledger);
    }

    @Test
    @DisplayName("同键同体重放返回同一 runId，且四类业务记录不增加")
    void sameKeySameBodyReplays() {
        RunAcceptanceService.Outcome first =
                service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY);
        RunAcceptanceService.Outcome second =
                service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY);

        assertEquals(first.runId(), second.runId());
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(1, store.runs);
        assertEquals(1, store.events);
        assertEquals(1, store.outbox);
        assertEquals(1, store.ledger);
    }

    @Test
    @DisplayName("同键不同体是 409 IDEMPOTENCY_KEY_REUSED，且不返回原 runId")
    void sameKeyDifferentBodyConflicts() {
        service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY);
        String changed = LEGAL_BODY.replace("synthetic-p04", "synthetic-p04-changed");

        assertEquals(P04AiErrorCode.IDEMPOTENCY_KEY_REUSED,
                codeOf(() -> service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", changed)));
        assertEquals(1, store.runs);
    }

    @Test
    @DisplayName("规范化哈希与对象字段顺序无关，但保留数组顺序")
    void canonicalHashIgnoresObjectOrderButNotArrayOrder() throws Exception {
        String reordered = "{\"action\":\"rag.chat\",\"schemaVersion\":1,"
                + "\"input\":{\"text\":\"synthetic-p04\"},\"resourceRefs\":[\"KB-A\"]}";
        assertEquals(hasher.hash(hasher.parse(LEGAL_BODY)), hasher.hash(hasher.parse(reordered)));

        String swapped = LEGAL_BODY.replace("[\"KB-A\"]", "[\"KB-B\",\"KB-A\"]");
        String swappedBack = LEGAL_BODY.replace("[\"KB-A\"]", "[\"KB-A\",\"KB-B\"]");
        assertNotEquals(hasher.hash(hasher.parse(swapped)), hasher.hash(hasher.parse(swappedBack)));
    }

    @Test
    @DisplayName("字段顺序不同但语义相同的重放命中同一 run")
    void reorderedBodyStillReplays() {
        RunAcceptanceService.Outcome first =
                service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY);
        String reordered = "{\"action\":\"rag.chat\",\"schemaVersion\":1,"
                + "\"input\":{\"text\":\"synthetic-p04\"},\"resourceRefs\":[\"KB-A\"]}";
        RunAcceptanceService.Outcome second =
                service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", reordered);

        assertEquals(first.runId(), second.runId());
        assertTrue(second.replayed());
        assertEquals(1, store.runs);
    }

    @Test
    @DisplayName("请求体携带身份字段是 403 TENANT_CONTEXT_MISSING，且无业务写入")
    void identityInBodyIsForbidden() {
        String forged = "{\"schemaVersion\":1,\"action\":\"rag.chat\",\"tenantId\":\"T2\","
                + "\"input\":{\"text\":\"synthetic-p04\"},\"resourceRefs\":[\"KB-A\"]}";

        assertEquals(P04AiErrorCode.TENANT_CONTEXT_MISSING,
                codeOf(() -> service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", forged)));
        assertEquals(0, store.runs);
        assertEquals(0, store.events);
        assertEquals(0, store.outbox);
        assertEquals(0, store.ledger);
    }

    @Test
    @DisplayName("未知字段是 400 BAD_REQUEST")
    void unknownFieldIsBadRequest() {
        String extra = "{\"schemaVersion\":1,\"action\":\"rag.chat\",\"surprise\":1,"
                + "\"input\":{\"text\":\"synthetic-p04\"},\"resourceRefs\":[\"KB-A\"]}";
        assertEquals(P04AiErrorCode.BAD_REQUEST,
                codeOf(() -> service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", extra)));
    }

    @Test
    @DisplayName("缺 Idempotency-Key 是 400 BAD_REQUEST")
    void missingIdempotencyKeyIsBadRequest() {
        assertEquals(P04AiErrorCode.BAD_REQUEST,
                codeOf(() -> service.accept(token("T1", "M1", 1, "rag.chat"), "  ", LEGAL_BODY)));
    }

    @Test
    @DisplayName("同一 token 的 jti 被重放是 401 DELEGATION_INVALID（F2：重试必须换 jti）")
    void replayedJtiIsRejected() {
        String replayed = token("T1", "M1", 1, "rag.chat");
        service.accept(replayed, "idem-1", LEGAL_BODY);

        // 同一个 token（jti 未变）再次提交必须被防重放拒绝，即使换了幂等键
        assertEquals(P04AiErrorCode.DELEGATION_INVALID,
                codeOf(() -> service.accept(replayed, "idem-2", LEGAL_BODY)));
        assertEquals(1, store.runs);
    }

    @Test
    @DisplayName("权限检查先于幂等查询（撤权后不能借重放取回结果）")
    void authorizationPrecedesIdempotencyLookup() {
        service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY);

        store.calls.clear();
        RunAcceptanceService.Outcome second =
                service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY);
        assertTrue(second.replayed());

        int authzIndex = store.calls.indexOf("authz");
        int lookupIndex = store.calls.indexOf("lookup");
        assertTrue(authzIndex >= 0, "授权复核必须被调用");
        assertTrue(lookupIndex >= 0, "幂等查询必须被调用");
        assertTrue(authzIndex < lookupIndex, "授权复核必须早于幂等查询");
    }

    @Test
    @DisplayName("平台侧拒绝（无权限）会向上抛出且不产生业务写入")
    void platformDenialPropagatesWithoutWrites() {
        checker.failure = P04AiErrorCode.FORBIDDEN;
        assertEquals(P04AiErrorCode.FORBIDDEN,
                codeOf(() -> service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY)));
        assertEquals(0, store.runs);
    }

    @Test
    @DisplayName("授权服务不可用是 503 AUTHORIZATION_UNAVAILABLE，且不放行")
    void authorizationUnavailableDoesNotFallThrough() {
        checker.failure = P04AiErrorCode.AUTHORIZATION_UNAVAILABLE;
        assertEquals(P04AiErrorCode.AUTHORIZATION_UNAVAILABLE,
                codeOf(() -> service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", LEGAL_BODY)));
        assertEquals(0, store.runs);
    }

    @Test
    @DisplayName("资源无 ACL 是 404 RESOURCE_NOT_FOUND_OR_FORBIDDEN，且无业务写入")
    void aclDenialIsUnifiedNotFound() {
        String otherResource = LEGAL_BODY.replace("KB-A", "KB-B");
        assertEquals(P04AiErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN,
                codeOf(() -> service.accept(token("T1", "M1", 1, "rag.chat"), "idem-1", otherResource)));
        assertEquals(0, store.runs);
    }

    @Test
    @DisplayName("不同租户使用相同幂等键互不复用")
    void sameKeyAcrossTenantsDoesNotCollide() {
        RunAcceptanceService.Outcome t1 =
                service.accept(token("T1", "M1", 1, "rag.chat"), "shared-key", LEGAL_BODY);
        RunAcceptanceService.Outcome t2 =
                service.accept(token("T2", "M1T2", 1, "rag.chat"), "shared-key", LEGAL_BODY);

        assertNotEquals(t1.runId(), t2.runId());
        assertEquals(2, store.runs);
    }
}
