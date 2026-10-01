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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.nageoffer.ai.ragent.rag.core.graph.LightRagClient;
import com.nageoffer.ai.ragent.rag.core.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 知识库删除清理 MQ 消费者
 * 负责异步回收知识库独占的底层物理资源：向量数据、bucket、ES 关键词索引、知识图谱数据
 * <p>
 * 各清理项 best-effort 互不影响，存在失败项则抛异常触发重试；所有操作均幂等，重试安全
 *
 * <p>P1.2a：本消费者属于<b>未批准旧能力</b>。装配层由
 * {@code ai.integration.legacy-listeners-enabled=true} 才注册（产品配置出现该属性会让启动失败），
 * 消息入口第一行再做一次关闭判定，因此人工直接调用不会 drop 向量空间、删除存储目录、
 * 操作 ES 或连接图谱。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "knowledge-base-cleanup_topic${unique-name:}",
        consumerGroup = "knowledge-base-cleanup_cg${unique-name:}"
)
public class KnowledgeBaseCleanupConsumer implements RocketMQListener<MessageWrapper<KnowledgeBaseCleanupEvent>> {

    private final VectorStoreAdmin vectorStoreAdmin;
    private final FileStorageService fileStorageService;
    /**
     * 关键词索引实现惰性解析：rag.keyword.type=none 时无该 bean，getIfAvailable() 返回 null 即跳过 ES 清理
     */
    private final ObjectProvider<KeywordIndexService> keywordIndexServiceProvider;
    /**
     * 图谱客户端惰性解析：rag.graph.type=none 时无该 bean，getIfAvailable() 返回 null 即跳过图谱清理
     */
    private final ObjectProvider<LightRagClient> lightRagClientProvider;
    /**
     * 旧能力关闭判定；缺席按关闭处理，绝不默认放行。
     */
    private final ObjectProvider<SaasCapabilityBoundary> capabilityBoundary;

    @Override
    public void onMessage(MessageWrapper<KnowledgeBaseCleanupEvent> message) {
        SaasCapabilityBoundary.requireOpenOrClosed(capabilityBoundary,
                SaasCapabilityBoundary.LegacyCapability.KB_CLEANUP_CONSUMER);
        KnowledgeBaseCleanupEvent event = message.getBody();
        String collectionName = event.getCollectionName();
        // 异步清理作用在共享索引/共享 collection 上，没有租户就不能安全执行：
        // 事件缺租户说明投递侧漏传（或投递的是本改动之前入队的旧事件），
        // 此时明确失败并留证，而不是按"没有租户"继续清理。
        String tenantId = event.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ClientException("知识库清理事件缺少 tenantId，拒绝执行：kbId=" + event.getKbId()
                    + "（共享索引上的清理必须限定租户）");
        }

        log.info("[消费者] 开始清理知识库物理资源，kbId={}, collectionName={}", event.getKbId(), collectionName);

        boolean allSucceeded = true;

        try {
            vectorStoreAdmin.dropVectorSpace(tenantId, collectionName);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("清理向量空间失败，collectionName={}", collectionName, e);
        }

        try {
            fileStorageService.deleteKnowledgeSpace(collectionName);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("删除知识库存储目录失败，namespace={}", collectionName, e);
        }

        KeywordIndexService keywordIndexService = keywordIndexServiceProvider.getIfAvailable();
        if (keywordIndexService != null) {
            try {
                keywordIndexService.deleteByCollection(tenantId, collectionName);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除 ES 关键词索引失败，tenant={}, collectionName={}", tenantId, collectionName, e);
            }
        }

        LightRagClient lightRagClient = lightRagClientProvider.getIfAvailable();
        if (lightRagClient != null) {
            try {
                lightRagClient.deleteByCollection(collectionName);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("删除 LightRAG 图谱数据失败，collectionName={}", collectionName, e);
            }
        }

        if (!allSucceeded) {
            throw new ServiceException("知识库物理资源清理存在失败项，触发重试");
        }
    }
}
