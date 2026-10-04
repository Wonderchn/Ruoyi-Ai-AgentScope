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

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.runtime.P2RuntimeProperties;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 私有对象存储（首期 fs 实现：专属目录、服务端生成 key、根目录约束）。
 *
 * <p>对象名由服务端按 tenant/资源生成，绝不信任客户端输入；读写都做 key 形状校验，
 * 拒绝绝对路径与 {@code ..}。s3/minio 属部署决定（未实现即失败，不静默回退）。
 */
@Component
@ConditionalOnProperty(name = "p2.object-store.type", havingValue = "fs", matchIfMissing = true)
public class FsPrivateObjectStore implements PrivateObjectStore {

    private final Path root;

    public FsPrivateObjectStore(P2RuntimeProperties properties) {
        String configured = properties.getObjectStore().getRoot();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("p2.object-store.root is required for fs object store");
        }
        this.root = Path.of(configured).toAbsolutePath().normalize();
    }

    @Override
    public String type() {
        return "fs";
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("..")
                || objectKey.startsWith("/") || objectKey.startsWith("\\") || objectKey.contains("\\")) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "invalid object key");
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new RunApiException(RunErrorCode.BAD_REQUEST, "object key escapes the private root");
        }
        return resolved;
    }

    @Override
    public String put(String objectKey, byte[] content) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return sha256(content);
        } catch (IOException e) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "object store write failed");
        }
    }

    @Override
    public StoredObject putStream(String objectKey, InputStream input, long maxBytes) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (DigestInputStream in = new DigestInputStream(input, digest);
                 OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    total += read;
                    if (total > maxBytes) {
                        out.close();
                        Files.deleteIfExists(tmp);
                        throw new RunApiException(RunErrorCode.BAD_REQUEST, "upload exceeds the configured limit");
                    }
                    out.write(buffer, 0, read);
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new StoredObject(objectKey, total, HexFormat.of().formatHex(digest.digest()));
        } catch (RunApiException e) {
            throw e;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "object store write failed");
        }
    }

    @Override
    public byte[] get(String objectKey) {
        Path target = resolve(objectKey);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "object not found");
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "object store delete failed");
        }
    }

    @Override
    public String sha256Of(String objectKey) {
        return sha256(get(objectKey));
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
