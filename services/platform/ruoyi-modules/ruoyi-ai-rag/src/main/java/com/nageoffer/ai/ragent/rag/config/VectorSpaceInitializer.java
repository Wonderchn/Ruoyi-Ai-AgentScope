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
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 向量共享空间启动初始化器
 * <p>
 * 与 {@link StorageInitializer} 对称：系统启动时幂等确保共享向量空间存在，免去首次建库/首次入库前的懒加载依赖
 * 后端无关——Milvus 建共享 collection，PG 依赖迁移脚本建表故此处为空操作，均以 {@link VectorStoreAdmin} 抹平差异
 * 集群环境下用 Redisson 分布式锁保证只建一次：先判断是否存在 → 拿锁 → 双重检查 → 创建
 *
 * <p>P1.2a：本初始化器属于<b>未批准旧能力</b>。它在 {@link #initVectorSpace()} 的
 * <b>第一次后端/Redis 调用之前</b>短路关闭，因此默认启动不会建 collection、不会抢锁。
 * 共享向量结构由未来迁移/专门能力批准后恢复；Bean 保留以便其他 service 解析依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorSpaceInitializer {

    private static final String LOCK_KEY_PREFIX = "ragent:vector:space:init:";
    private static final long LOCK_WAIT_SECONDS = 30;

    private final VectorStoreAdmin vectorStoreAdmin;
    private final RAGDefaultProperties ragDefaultProperties;
    private final RedissonClient redissonClient;
    /**
     * 旧能力关闭判定；缺席按关闭处理（不默认放行）。
     */
    private final ObjectProvider<SaasCapabilityBoundary> capabilityBoundary;

    @PostConstruct
    public void initVectorSpace() {
        // 与 StorageInitializer 同因同治：@PostConstruct 里让受控异常逃出去，
        // 等于把"关闭旧能力"变成"应用启动失败"。关闭的要求是不发生任何
        // 向量后端/Redis 调用，而不是拒绝启动；启动阶段也没有请求可拒绝。
        // 显式重载 initVectorSpace(boundary) 仍抛异常，供请求/命令路径使用。
        try {
            runStartupInit(capabilityBoundary == null ? null : capabilityBoundary.getIfAvailable());
        } catch (SaasCapabilityBoundary.ClosedCapabilityException e) {
            log.warn("向量共享空间初始化已跳过：旧能力关闭（capability={}）。"
                    + "本次启动未创建 collection、未获取 Redis 锁。", e.capability());
        }
    }

    /**
     * 启动期专用包装：与显式重载同名会自递归，并会让"显式版本必须抛异常"的契约失效。
     */
    private void runStartupInit(SaasCapabilityBoundary boundary) {
        initVectorSpace(boundary);
    }

    /**
     * 显式边界版本：关闭时抛受控异常，且不发生任何向量后端/Redis 调用。
     *
     * <p>public 而非包内可见：这是请求/命令路径契约，与
     * {@link #initVectorSpace()} 的启动期跳过分离，两者都需跨包断言。
     */
    public void initVectorSpace(SaasCapabilityBoundary boundary) {
        if (boundary == null) {
            throw new SaasCapabilityBoundary.ClosedCapabilityException(
                    SaasCapabilityBoundary.LegacyCapability.VECTOR_SPACE_INITIALIZER);
        }
        boundary.requireOpen(SaasCapabilityBoundary.LegacyCapability.VECTOR_SPACE_INITIALIZER);

        String collectionName = ragDefaultProperties.getCollectionName();
        ensureVectorSpace(collectionName);
        log.info("向量共享空间就绪 collection={}", collectionName);
    }

    private void ensureVectorSpace(String collectionName) {
        VectorSpaceId spaceId = VectorSpaceId.builder().logicalName(collectionName).build();
        if (vectorStoreAdmin.vectorSpaceExists(spaceId)) {
            return;
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + collectionName);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("向量共享空间初始化获取分布式锁被中断 collection=" + collectionName);
        }
        if (!locked) {
            throw new ServiceException("向量共享空间初始化获取分布式锁超时 collection=" + collectionName);
        }

        try {
            if (vectorStoreAdmin.vectorSpaceExists(spaceId)) {
                return;
            }
            vectorStoreAdmin.ensureVectorSpace(VectorSpaceSpec.builder()
                    .spaceId(spaceId)
                    .remark("RAG 向量共享存储")
                    .build());
            log.info("向量共享空间创建成功 collection={}", collectionName);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
