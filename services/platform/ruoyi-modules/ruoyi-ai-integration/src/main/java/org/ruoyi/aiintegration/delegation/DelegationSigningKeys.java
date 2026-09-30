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
 * 另持有一把<b>不受信</b>的第二密钥，仅用于构造 {@code FOREIGN_KEY} 负例。
 */
public final class DelegationSigningKeys {

    private static final int KEY_SIZE = 3072;

    private final String kid;
    private final KeyPair trusted;
    private final KeyPair untrusted;

    private DelegationSigningKeys(String kid, KeyPair trusted, KeyPair untrusted) {
        this.kid = kid;
        this.trusted = trusted;
        this.untrusted = untrusted;
    }

    public static DelegationSigningKeys generate(String kid) {
        return new DelegationSigningKeys(kid, generatePair(), generatePair());
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

    /** 不受信私钥：用于构造签名校验必然失败的负例。 */
    public PrivateKey untrustedPrivateKey() {
        return untrusted.getPrivate();
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
