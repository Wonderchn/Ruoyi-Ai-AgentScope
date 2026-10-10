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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * {@link ObjectStorageClient} 的<b>本地文件系统</b>实现（内嵌形态专用，S2-F05-A1）。
 *
 * <p><b>为什么需要它。</b>知识管理面（{@code /knowledge-base/**}）的闭包瓶颈之一是
 * {@code DefaultFileStorageService} ← {@code ObjectStorageClient}；内嵌形态里 S3/OSS
 * 两个实现都带 {@code @ConditionalOnProperty(rag.storage.type=s3|oss)} 且<b>需要部署级
 * 凭据</b>（endpoint / access-key / secret-key）——按"零凭据也要能启动"的内嵌纪律，
 * shipped {@code application-embedded.yml} 不能提供这些值，因此内嵌档案需要一个
 * <b>无凭据的本地后端</b>才能把闭包闭合到"启动即可用"。
 *
 * <p><b>与 P2 面同款决策，不是新造形态。</b>{@code FsPrivateObjectStore}
 * （{@code p2.object-store.type=fs} + {@code P2_OBJECT_STORE_ROOT}）已经为 P2 私有对象面
 * 做过同一件事：本地专属目录、服务端生成 key、根目录约束、拒绝绝对路径与 {@code ..}。
 * 本类沿用同一套语义（见 {@code resolve}），只是实现的是 {@code ObjectStorageClient} 这层
 * 桶/裸 key SPI，好让既有的 {@code DefaultFileStorageService}（namespace/key 组装、
 * 桶归属、Tika 探测、DTO 装配）<b>一行不改</b>地跑在内嵌形态里。
 *
 * <p><b>真实 S3/OSS 仍属部署决策（F22 op3）。</b>本类只回答"内嵌档案用什么后端"，
 * 不冒充已接通对象存储：{@code rag.storage.type=s3|oss} 时本 bean 缺席，
 * 那两个后端的内嵌接通与验收由 F22 op3 负责（本切片不声称已验）。
 *
 * <p><b>服务端读取面，不提供浏览器直连。</b>对象只能经 {@code DefaultFileStorageService}
 * 的 {@code openStream}/{@code deleteByUrl} 由服务端按租户前缀读取；
 * {@link #setBucketPublicRead} 因此没有对应语义（本地目录上不存在"公共读策略"），
 * 显式拒绝而不是静默成功——公共读在本后端上若被调用，必须有新的显式决策。
 * {@link #buildPublicUrl} 返回不泄露服务器绝对路径的稳定定位符（{@code fs://bucket/key}）。
 */
public class EmbeddedFsObjectStorageClient implements ObjectStorageClient {

    private final Path root;

    public EmbeddedFsObjectStorageClient(String root) {
        if (root == null || root.isBlank()) {
            // "配置说要但不给值"必须启动期响亮失败，不给运行期静默留门（G-40 同纪律）。
            throw new IllegalStateException("rag.storage.root is required for fs object store");
        }
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /** 对象落点：root/{bucket}/{key}；key 形状在解析前校验，越界即拒绝。 */
    private Path resolve(String bucket, String key) {
        if (bucket == null || bucket.isBlank() || bucket.contains("..")
                || bucket.startsWith("/") || bucket.startsWith("\\") || bucket.contains("\\")) {
            throw new ClientException("非法桶名");
        }
        if (key == null || key.isBlank() || key.contains("..")
                || key.startsWith("/") || key.startsWith("\\") || key.contains("\\")) {
            throw new ClientException("非法对象 key");
        }
        Path target = root.resolve(bucket).resolve(key).normalize();
        if (!target.startsWith(root.resolve(bucket))) {
            throw new ClientException("对象 key 越出存储根");
        }
        return target;
    }

    private Path bucketRoot(String bucket) {
        if (bucket == null || bucket.isBlank() || bucket.contains("..")
                || bucket.startsWith("/") || bucket.startsWith("\\") || bucket.contains("\\")) {
            throw new ClientException("非法桶名");
        }
        return root.resolve(bucket);
    }

    @Override
    public void streamPut(String bucket, String key, InputStream content, long size, String contentType) {
        write(bucket, key, content);
    }

    @Override
    public void reliablePut(String bucket, String key, InputStream content, long size, String contentType) {
        write(bucket, key, content);
    }

    /**
     * 写入：先落同目录临时文件再原子移动，避免读取方看到半个对象
     * （与 {@code FsPrivateObjectStore#put} 同款写序）。
     */
    private void write(String bucket, String key, InputStream content) {
        if (content == null) {
            throw new ClientException("对象内容不能为空");
        }
        Path target = resolve(bucket, key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
            try (OutputStream out = Files.newOutputStream(tmp)) {
                content.transferTo(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ServiceException("本地对象存储写入失败: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public InputStream getObject(String bucket, String key) {
        Path target = resolve(bucket, key);
        if (!Files.isRegularFile(target)) {
            // 与 S3 的 NoSuchKey 同形：读侧以"对象不存在"响亮失败，不返回空流。
            throw new ServiceException("对象不存在");
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new ServiceException("本地对象存储读取失败: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        try {
            Files.deleteIfExists(resolve(bucket, key));
        } catch (IOException e) {
            throw new ServiceException("本地对象存储删除失败: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void deleteByPrefix(String bucket, String prefix) {
        Path base = bucketRoot(bucket);
        if (!Files.isDirectory(base)) {
            return; // 幂等：桶目录不存在即无事可做
        }
        String normalized = prefix == null ? "" : prefix;
        int split = normalized.lastIndexOf('/');
        Path scope = split < 0 ? base : base.resolve(normalized.substring(0, split)).normalize();
        if (!scope.startsWith(base)) {
            throw new ClientException("对象前缀越出桶根");
        }
        try (Stream<Path> stream = Files.walk(scope)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(path -> relative(base, path).startsWith(normalized))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new ServiceException("本地对象存储批量删除失败: " + e.getClass().getSimpleName());
        }
    }

    private static String relative(Path base, Path path) {
        return base.relativize(path).toString().replace('\\', '/');
    }

    @Override
    public boolean objectExists(String bucket, String key) {
        return Files.isRegularFile(resolve(bucket, key));
    }

    @Override
    public boolean bucketExists(String bucket) {
        return Files.isDirectory(bucketRoot(bucket));
    }

    @Override
    public void createBucket(String bucket) {
        try {
            Files.createDirectories(bucketRoot(bucket));
        } catch (IOException e) {
            throw new ServiceException("本地对象存储建桶失败: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 本地文件系统没有"公共读策略"这一层：对象只能经服务端 {@code openStream} 按租户前缀读取。
     * 显式拒绝而不是静默成功——调用方（如桶初始化器）必须知道"本后端不提供浏览器直连"。
     */
    @Override
    public void setBucketPublicRead(String bucket) {
        throw new UnsupportedOperationException(
                "fs 对象存储不提供公共读直连；资产浏览面需显式部署决策（S3/OSS 或专用静态服务）");
    }

    /**
     * 稳定定位符（不泄露服务器绝对路径）：本地后端下它只用于资产元数据的自我描述，
     * 内嵌形态没有"浏览器匿名直连"面（浏览器验收属 B 面 NOT_RUN）。
     */
    @Override
    public String buildPublicUrl(String bucket, String key) {
        return "fs://" + bucket + "/" + key;
    }
}
