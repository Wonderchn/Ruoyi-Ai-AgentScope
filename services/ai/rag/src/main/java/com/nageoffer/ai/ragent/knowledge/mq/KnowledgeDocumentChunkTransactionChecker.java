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

import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.framework.mq.producer.TransactionChecker;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 文档分块事务消息回查器
 * <p>
 * 按 topic 注册，Broker 回查时可路由到任意实例，通过查询 DB 中文档状态判断本地事务是否已提交
 *
 * <p>P1.2a：本回查器按"未批准旧能力"处理。{@link #init(SaasCapabilityBoundary)} 在
 * <b>注册之前</b>先判定并直接拒绝，因此不会向 {@link DelegatingTransactionListener}
 * 注册任何旧 topic；{@link #check(MessageWrapper)} 也在读取消息体之前先判定，
 * 人工直接调用不会触发裸 doc 查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
public class KnowledgeDocumentChunkTransactionChecker implements TransactionChecker<KnowledgeDocumentChunkEvent> {

    private final KnowledgeDocumentMapper documentMapper;
    private final DelegatingTransactionListener transactionListener;

    /**
     * 事务回查的关闭判定依赖，同时被生命周期方法与直接调用复用。
     *
     * <p>刻意<b>不</b>放进构造器：关闭判定必须能在不装配该 checker 的轻量测试上下文里
     * 被单独构造并断言（构造器只保留既有协作者，不因边界改造而改变实例化契约）。
     */
    @Autowired(required = false)
    private SaasCapabilityBoundary capabilityBoundary;

    @Value("knowledge-document-chunk_topic${unique-name:}")
    private String chunkTopic;

    @PostConstruct
    public void init() {
        init(capabilityBoundary);
    }

    /** 显式边界版本：关闭时抛受控异常，注册次数为 0。 */
    void init(SaasCapabilityBoundary boundary) {
        if (boundary == null) {
            throw new SaasCapabilityBoundary.ClosedCapabilityException(
                    SaasCapabilityBoundary.LegacyCapability.KB_DOCUMENT_CHUNK_CHECKER);
        }
        boundary.requireOpen(SaasCapabilityBoundary.LegacyCapability.KB_DOCUMENT_CHUNK_CHECKER);
        transactionListener.registerChecker(chunkTopic, this);
    }

    @Override
    public Class<KnowledgeDocumentChunkEvent> bodyType() {
        return KnowledgeDocumentChunkEvent.class;
    }

    @Override
    public boolean check(MessageWrapper<KnowledgeDocumentChunkEvent> message) {
        if (capabilityBoundary == null) {
            throw new SaasCapabilityBoundary.ClosedCapabilityException(
                    SaasCapabilityBoundary.LegacyCapability.KB_DOCUMENT_CHUNK_CHECKER);
        }
        capabilityBoundary.requireOpen(SaasCapabilityBoundary.LegacyCapability.KB_DOCUMENT_CHUNK_CHECKER);

        log.info("[事务回查] 文档分块，消息体：{}", JSONUtil.toJsonStr(message));

        KnowledgeDocumentChunkEvent event = message.getBody();
        String docId = event.getDocId();
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);

        return documentDO != null
                && DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus());
    }
}
