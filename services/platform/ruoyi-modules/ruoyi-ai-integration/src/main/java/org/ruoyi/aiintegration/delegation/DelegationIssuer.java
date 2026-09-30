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

package org.ruoyi.aiintegration.delegation;

import io.jsonwebtoken.Jwts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 委托凭证签发器（紧凑 JWS / JWT，RS256）。
 *
 * <p>冻结口径（Spec §7.2/§8.2）：标准紧凑 JWS、{@code RS256}、RSA 3072、固定
 * {@code typ}/{@code alg}、issuer/audience 白名单；<b>不得</b>自创加密结构。
 * 每次签发都产生新的 {@code jti}（F2：重试要换 jti，业务幂等靠 Idempotency-Key）。
 *
 * <p><b>只签发合法委托</b>（阻断修复 Spec §2.3）：负例变体（错 aud/iss、过期、
 * 未来 nbf、缺声明、未知 kid、外来私钥、{@code alg=none}）的构造能力<b>已移入测试源集</b>，
 * 以免 P1 开启委托功能时连带开启"任意身份 + 负例凭证"铸造面。
 *
 * <p>TTL 在签发侧即被限制在 [1, 60] 秒，与 AI 侧验签的 TTL 上限保持一致。
 */
@Component
@ConditionalOnProperty(name = "p04.enabled", havingValue = "true")
public class DelegationIssuer {

    /** 受信签发方。 */
    public static final String ISSUER = "platform";

    /** 受信受众（AI 服务）。 */
    public static final String AUDIENCE = "ai";

    /** 动作/功能 scope；AI 校验时必须命中。 */
    public static final String SCOPE_RAG_CHAT_SUBMIT = "rag.chat.submit";

    /** TTL 下界（秒）。 */
    public static final int MIN_TTL_SECONDS = 1;

    /** TTL 上界（秒），与 Spec §7.2「TTL 60 秒」一致。 */
    public static final int MAX_TTL_SECONDS = 60;

    private final DelegationSigningKeys keys;
    private final Clock clock;

    public DelegationIssuer(DelegationSigningKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    /**
     * 签发结果。
     *
     * @param token 紧凑 JWS
     * @param jti   一次性标识（用于防重放账本与证据关联，不记录 token 本身）
     */
    public record Issued(String token, String jti, String kid) {
    }

    /**
     * 签发一份合法委托。所有身份声明都必须齐备——缺声明的负例只能由测试侧铸造。
     *
     * @throws IllegalArgumentException 身份声明缺失或 TTL 越界
     */
    public Issued issue(String tenantId, String subject, String membershipId, List<String> scopes,
                        Integer policyVersion, Integer ttlSeconds) {
        requireText(tenantId, "tenantId");
        requireText(subject, "subject");
        requireText(membershipId, "membershipId");
        if (policyVersion == null) {
            throw new IllegalArgumentException("policyVersion is required");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes must not be empty");
        }

        int ttl = ttlSeconds == null ? MAX_TTL_SECONDS : ttlSeconds;
        if (ttl < MIN_TTL_SECONDS || ttl > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("ttlSeconds must be within [" + MIN_TTL_SECONDS + ","
                    + MAX_TTL_SECONDS + "]");
        }

        Instant now = clock.instant();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .header().keyId(keys.kid()).type("JWT").and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(subject)
                .id(jti)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttl)))
                .claim("tid", tenantId)
                .claim("mid", membershipId)
                .claim("pv", policyVersion)
                .claim("scope", scopes)
                .signWith(keys.privateKey(), Jwts.SIG.RS256)
                .compact();
        return new Issued(token, jti, keys.kid());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
