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
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 生产签发密钥源（U06/P1.2c）：从配置路径加载 PKCS#8 PEM 私钥并按冻结契约签发委托。
 *
 * <p>为什么不是直接用 {@link DelegationIssuer} bean：它被 {@code p04.enabled} 门控，
 * 且其密钥容器 {@link DelegationSigningKeys} 只能<b>进程内随机生成</b>（私有构造、
 * 无 PEM 工厂）——生产口径明确禁止内存随机密钥（重启漂移、无法与 AI 侧落盘公钥对齐）。
 * 本类按与 {@link DelegationIssuer#issue} 完全相同的 claim 契约签发
 * （复用其 {@code ISSUER}/{@code AUDIENCE}/{@code TTL} 常量），私钥来自静态 PEM 文件，
 * kid 固定为 {@link #KID}（与 AI 侧 {@code ai.integration.security.kid} 默认值镜像）。
 *
 * <p>fail-fast：构造时材料缺失（路径空白/文件不可读/不是 PKCS#8 RSA 私钥）直接抛
 * {@link IllegalStateException}，{@code enabled=true} 的装配在启动期即失败，
 * 绝不允许"无钥降级运行"。
 */
public class ProductionSigningKeySource {

    /** 生产签发的固定密钥标识（AI 侧验签白名单默认值与此镜像）。 */
    public static final String KID = "platform-prod-k1";

    private final PrivateKey privateKey;
    private final Clock clock;

    public ProductionSigningKeySource(String privateKeyPath, Clock clock) {
        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            throw new IllegalStateException(
                    "ai.integration.delegation.private-key-path is required when ai.integration.enabled=true");
        }
        this.privateKey = loadPkcs8Pem(privateKeyPath);
        this.clock = clock;
    }

    /**
     * 签发结果（与 {@link DelegationIssuer.Issued} 同形状）。
     *
     * @param token 紧凑 JWS
     * @param jti   一次性标识（重放账本按 (issuer, jti) 消费）
     * @param kid   密钥标识（固定 {@link #KID}）
     */
    public record Issued(String token, String jti, String kid) {
    }

    /**
     * 签发一份合法委托；claim 契约与 {@link DelegationIssuer#issue} 逐项一致：
     * RS256、固定 typ/kid、iss/aud 白名单、iat/nbf/exp、tid/mid/pv/scope，
     * 每次 jti 全新，TTL 限制在 [1, 60] 秒。
     *
     * @throws P04Exception 身份声明缺失 → {@code FORBIDDEN}（403，网关把它当作
     *                      "无法为该成员签发委托"的拒绝，不外泄细节）
     */
    public Issued issue(String tenantId, String subject, String membershipId, List<String> scopes,
                        Integer policyVersion, Integer ttlSeconds) {
        if (tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank()
                || membershipId == null || membershipId.isBlank()) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }
        if (policyVersion == null) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }
        int ttl = ttlSeconds == null ? DelegationIssuer.MAX_TTL_SECONDS : ttlSeconds;
        if (ttl < DelegationIssuer.MIN_TTL_SECONDS || ttl > DelegationIssuer.MAX_TTL_SECONDS) {
            throw new P04Exception(P04ErrorCode.FORBIDDEN);
        }

        Instant now = clock.instant();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .header().keyId(KID).type("JWT").and()
                .issuer(DelegationIssuer.ISSUER)
                .audience().add(DelegationIssuer.AUDIENCE).and()
                .subject(subject)
                .id(jti)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttl)))
                .claim("tid", tenantId)
                .claim("mid", membershipId)
                .claim("pv", policyVersion)
                .claim("scope", scopes)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        return new Issued(token, jti, KID);
    }

    /** PKCS#8 PEM → RSA 私钥；任何问题都是启动失败（fail-fast）。 */
    private static PrivateKey loadPkcs8Pem(String path) {
        try {
            String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            String base64 = pem.replace("-----BEGIN " + "PRIVATE KEY-----", "")
                    .replace("-----END " + "PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read production delegation private key: " + path, e);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("cannot parse production delegation private key: " + path, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("production delegation private key is not valid base64: " + path, e);
        }
    }
}
