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

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.TransactionChecker;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库删除清理事务消息回查器
 * 按 topic 注册，Broker 回查时可路由到任意实例，通过查询 DB 中知识库是否已逻辑删除判断本地事务是否已提交
 *
 * <p>P1.2a：本回查器按"未批准旧能力"处理。注册前先判定并直接拒绝，因此不会向
 * {@link DelegatingTransactionListener} 注册旧 topic；{@link #check(MessageWrapper)}
 * 也在读取消息体与裸 KB 查询之前先判定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
public class KnowledgeBaseCleanupTransactionChecker implements TransactionChecker<KnowledgeBaseCleanupEvent> {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DelegatingTransactionListener transactionListener;

    /**
     * 关闭判定依赖；刻意不放进构造器，保证轻量测试可独立构造并断言关闭行为。
     */
    @Autowired(required = false)
    private SaasCapabilityBoundary capabilityBoundary;

    /**
     * 租户作用域绑定端口（F22:2 真机第四缺口）：broker 回查线程没有登录主体，
     * 而 {@code ai_knowledge_base} 是租户业务表，查询前必须绑定事件租户；
     * 独立应用没有租户拦截器时端口缺席，按直接执行。
     */
    @Autowired(required = false)
    private TenantScopeBinder tenantScopeBinder;

    @Value("knowledge-base-cleanup_topic${unique-name:}")
    private String cleanupTopic;

    @PostConstruct
    public void init() {
        init(capabilityBoundary);
    }

    /** 显式边界版本：关闭时抛受控异常，注册次数为 0。 */
    void init(SaasCapabilityBoundary boundary) {
        if (boundary == null) {
            throw new SaasCapabilityBoundary.ClosedCapabilityException(
                    SaasCapabilityBoundary.LegacyCapability.KB_CLEANUP_CHECKER);
        }
        boundary.requireOpen(SaasCapabilityBoundary.LegacyCapability.KB_CLEANUP_CHECKER);
        transactionListener.registerChecker(cleanupTopic, this);
    }

    @Override
    public Class<KnowledgeBaseCleanupEvent> bodyType() {
        return KnowledgeBaseCleanupEvent.class;
    }

    @Override
    public boolean check(MessageWrapper<KnowledgeBaseCleanupEvent> message) {
        if (capabilityBoundary == null) {
            throw new SaasCapabilityBoundary.ClosedCapabilityException(
                    SaasCapabilityBoundary.LegacyCapability.KB_CLEANUP_CHECKER);
        }
        capabilityBoundary.requireOpen(SaasCapabilityBoundary.LegacyCapability.KB_CLEANUP_CHECKER);

        log.info("[事务回查] 知识库删除清理，消息体：{}", JSONUtil.toJsonStr(message));

        KnowledgeBaseCleanupEvent event = message.getBody();
        if (tenantScopeBinder == null) {
            // 独立应用：没有租户行拦截器，保持原查询形态。
            return isDeleted(event.getKbId());
        }
        if (StrUtil.isBlank(event.getTenantId())) {
            throw new ClientException("知识库清理回查事件缺少 tenantId，拒绝执行：kbId=" + event.getKbId()
                    + "（租户业务表查询必须限域，不落默认租户）");
        }
        AtomicBoolean deleted = new AtomicBoolean();
        tenantScopeBinder.runWithTenant(event.getTenantId(), () -> deleted.set(isDeleted(event.getKbId())));
        return deleted.get();
    }

    private boolean isDeleted(String kbId) {
        // 逻辑删除后 selectById 不可见；查不到即视为本地事务（软删知识库）已提交
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        return kbDO == null;
    }
}
