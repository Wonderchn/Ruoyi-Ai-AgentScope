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

package com.nageoffer.ai.ragent.media.delivery;

import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * F19:4 私有媒体对象：provider 结果只落 {@link PrivateObjectStore}，前端只拿任务 ID。
 *
 * <p>对象 key 由服务端生成（{@code {tenant}/media/{uuid}.{ext}}），不接受客户端输入；
 * 结果字节与哈希在落盘时复核。对象登记是可选的撤权传播面。
 */
public class MediaContentStore {

    /** 对象登记父类型：媒体任务（{@code ai_resource.parent_type} 无枚举约束）。 */
    public static final String PARENT_TYPE = "MEDIA_TASK";

    private final PrivateObjectStore store;
    private final MediaObjectRegistrar registrar;

    public MediaContentStore(PrivateObjectStore store, MediaObjectRegistrar registrar) {
        this.store = store;
        this.registrar = registrar;
    }

    /** 落盘结果。 */
    public record StoredMedia(String objectKey, String objectSegment, String mimeType,
                              String sha256, long sizeBytes) {
    }

    /**
     * 私有化 provider 结果字节。
     *
     * @throws RunApiException 存储失败（依赖不可用），调用方保持 RUNNING 以便下次轮询重试
     */
    public StoredMedia store(String tenantId, String taskId, String memberId, byte[] content, String mimeType) {
        if (content == null || content.length == 0) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "媒体结果为空");
        }
        String mime = normalizeMime(mimeType);
        String segment = UUID.randomUUID().toString().replace("-", "");
        String key = tenantId + "/media/" + segment + "." + extensionOf(mime);
        String sha = sha256(content);
        String stored;
        try {
            stored = store.put(key, content);
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "媒体结果存储失败");
        }
        if (!sha.equalsIgnoreCase(stored)) {
            deleteQuietly(key);
            throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "媒体结果哈希校验失败");
        }
        if (registrar != null) {
            try {
                registrar.register(tenantId, segment, PARENT_TYPE, taskId, memberId, memberId);
            } catch (RuntimeException e) {
                deleteQuietly(key);
                throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "媒体对象登记失败");
            }
        }
        return new StoredMedia(key, segment, mime, sha, content.length);
    }

    /** 读取私有对象字节（调用方必须先完成任务属主/状态校验）。 */
    public byte[] read(String objectKey) {
        try {
            return store.get(objectKey);
        } catch (RunApiException e) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "媒体结果不可用");
        } catch (RuntimeException e) {
            throw new RunApiException(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN, "媒体结果不可用");
        }
    }

    /** 删除对象（登记失败回滚用）。 */
    public Optional<String> deleteQuietly(String objectKey) {
        try {
            store.delete(objectKey);
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of("媒体对象清理失败");
        }
    }

    static String normalizeMime(String mimeType) {
        String mime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/") && mime.length() <= 64) {
            return mime;
        }
        throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE, "媒体结果不是图片");
    }

    static String extensionOf(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            default -> "bin";
        };
    }

    public static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
