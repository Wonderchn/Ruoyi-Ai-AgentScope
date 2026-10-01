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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.rag.core.storage.ObjectStorageClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 对象存储桶启动初始化器
 * <p>
 * 系统启动时自动创建两个全局桶，免去运维手动建桶/配权限：
 * <ul>
 *   <li>知识库桶 {@code rag.storage.kb-bucket}：私有，承载所有知识库文档，按 collectionName 目录隔离</li>
 *   <li>资产桶 {@code rag.storage.asset-bucket}：公共读，PDF 抽出的图片等需被浏览器匿名直连预览</li>
 * </ul>
 * 集群环境下用 Redisson 分布式锁保证只建一次：先判断是否存在 → 拿锁 → 双重检查 → 建桶
 *
 * <p>P1.2a：本初始化器属于<b>未批准旧能力</b>。它在 {@link #initBuckets()} 的
 * <b>第一次远端调用之前</b>短路关闭，因此默认启动不会建桶、不会下发资产桶公共读，
 * 也不会触碰 Redis 锁。Bean 保留（其他 service 构造器仍可解析依赖），
 * 索引/桶结构由未来专门能力批准后恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageInitializer {

    private static final String LOCK_KEY_PREFIX = "ragent:storage:bucket:init:";
    private static final long LOCK_WAIT_SECONDS = 30;

    private final ObjectStorageClient objectStorageClient;
    private final RedissonClient redissonClient;
    private final RagStorageProperties properties;
    /**
     * 旧能力关闭判定；缺席按关闭处理（不默认放行）。
     */
    private final ObjectProvider<SaasCapabilityBoundary> capabilityBoundary;

    @PostConstruct
    public void initBuckets() {
        initBuckets(capabilityBoundary == null ? null : capabilityBoundary.getIfAvailable());
    }

    /** 显式边界版本：关闭时抛受控异常，且不发生任何对象存储/Redis 调用。 */
    void initBuckets(SaasCapabilityBoundary boundary) {
        if (boundary == null) {
            throw new SaasCapabilityBoundary.ClosedCapabilityException(
                    SaasCapabilityBoundary.LegacyCapability.STORAGE_INITIALIZER);
        }
        boundary.requireOpen(SaasCapabilityBoundary.LegacyCapability.STORAGE_INITIALIZER);

        ensureBucket(properties.getKbBucket(), false);
        ensureBucket(properties.getAssetBucket(), true);
    }

    private void ensureBucket(String bucket, boolean publicRead) {
        ensureBucketExists(bucket);
        if (publicRead) {
            // 幂等下发公共读：无论新建还是已存在，都保证桶内对象可被浏览器匿名直连预览
            objectStorageClient.setBucketPublicRead(bucket);
            log.info("对象存储桶就绪（公共读）bucket={}", bucket);
        } else {
            log.info("对象存储桶就绪 bucket={}", bucket);
        }
    }

    private void ensureBucketExists(String bucket) {
        if (objectStorageClient.bucketExists(bucket)) {
            return;
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + bucket);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("对象存储桶初始化获取分布式锁被中断 bucket=" + bucket);
        }
        if (!locked) {
            throw new ServiceException("对象存储桶初始化获取分布式锁超时 bucket=" + bucket);
        }

        try {
            if (objectStorageClient.bucketExists(bucket)) {
                return;
            }
            objectStorageClient.createBucket(bucket);
            log.info("对象存储桶创建成功 bucket={}", bucket);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
