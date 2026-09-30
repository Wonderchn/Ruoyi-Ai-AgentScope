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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * 实验期运行生成的非对称密钥材料（Spec §7.2：platform 只持签发私钥）。
 *
 * <p>密钥在进程启动时生成、只存在于内存，不落盘、不写证据。密钥长度固定 RSA 3072。
 *
 * <p><b>只持有受信密钥对</b>（阻断修复 Spec §2.3）：构造"外来私钥签发"负例所需的第二把
 * 不受信密钥属于测试侧能力，已移出主源集。
 *
 * <p>本类默认<b>不</b>作为 bean 装配；它由同样受 {@code p04.enabled} 约束的配置类创建。
 */
public final class DelegationSigningKeys {

    private static final int KEY_SIZE = 3072;

    private final String kid;
    private final KeyPair trusted;

    private DelegationSigningKeys(String kid, KeyPair trusted) {
        this.kid = kid;
        this.trusted = trusted;
    }

    public static DelegationSigningKeys generate(String kid) {
        return new DelegationSigningKeys(kid, generatePair());
    }

    /** 供测试侧铸造负例时自行生成不受信密钥使用；主源集只暴露生成能力，不持有该密钥。 */
    public static KeyPair generateUntrustedPairForTests() {
        return generatePair();
    }

    private static KeyPair generatePair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key pair generation unavailable", e);
        }
    }

    public String kid() {
        return kid;
    }

    public PrivateKey privateKey() {
        return trusted.getPrivate();
    }

    public PublicKey publicKey() {
        return trusted.getPublic();
    }

    /** 受信公钥的 PEM 文本，供 AI 侧仅持公钥地验签。 */
    public String publicKeyPem() {
        return toPem("PUBLIC KEY", trusted.getPublic().getEncoded());
    }

    private static String toPem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
