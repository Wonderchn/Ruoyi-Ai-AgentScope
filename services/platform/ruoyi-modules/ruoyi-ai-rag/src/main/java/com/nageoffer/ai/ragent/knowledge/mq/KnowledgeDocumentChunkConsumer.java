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
import com.nageoffer.ai.ragent.framework.context.AsyncPrincipalReference;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.integration.TenantScopeBinder;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 文档分块任务 MQ 消费者
 * 负责异步执行耗时的文本提取、分块、向量嵌入及写库操作
 *
 * <p>P1.2a：本消费者属于<b>未批准旧能力</b>。装配层由
 * {@code ai.integration.legacy-listeners-enabled=true} 才注册（产品配置出现该属性会让启动失败），
 * 消息入口第一行再做一次关闭判定，因此人工直接调用 {@code onMessage} 也<b>不会</b>产生
 * 数据库、MQ、对象或模型副作用——判定在日志正文、{@code UserContext.set} 与任何 service 调用之前。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "knowledge-document-chunk_topic${unique-name:}",
        consumerGroup = "knowledge-document-chunk_cg${unique-name:}"
)
public class KnowledgeDocumentChunkConsumer implements RocketMQListener<MessageWrapper<KnowledgeDocumentChunkEvent>> {

    private final KnowledgeDocumentService documentService;
    private final ObjectProvider<SaasCapabilityBoundary> capabilityBoundary;

    /**
     * 租户作用域绑定端口（F22:2 真机第四缺口）：platform 侧存在 MyBatis 租户行拦截器，
     * 消费线程没有登录主体，必须在任何 DB 访问之前把事件携带的租户绑定到当前线程；
     * 独立应用没有拦截器时端口缺席，按直接执行（与 {@code MessageFeedbackConsumer} 同款）。
     */
    private final ObjectProvider<TenantScopeBinder> tenantScopeBinder;

    @Override
    public void onMessage(MessageWrapper<KnowledgeDocumentChunkEvent> message) {
        // 第一行：关闭判定必须早于日志正文、UserContext.set 与任何 service 调用
        SaasCapabilityBoundary.requireOpenOrClosed(capabilityBoundary,
                SaasCapabilityBoundary.LegacyCapability.KB_DOCUMENT_CHUNK_CONSUMER);

        KnowledgeDocumentChunkEvent event = message.getBody();

        // F22:2 真机第五缺口：chunk 执行链（VectorTargetResolver.resolve）要求 AI 侧
        // PrincipalContext；事件必须携带受理线程降格出的身份引用。缺失/租户不一致一律
        // fail-closed，不落默认主体（05 §4.3：异步只传身份引用，引用不是授权证据）。
        AsyncPrincipalReference principalRef = event.getPrincipalRef();
        if (principalRef == null) {
            throw new ClientException("文档分块事件缺少 principalRef，拒绝执行：docId=" + event.getDocId()
                    + "（异步执行必须携带身份引用，不落默认主体）");
        }
        if (StrUtil.isBlank(event.getTenantId()) || !event.getTenantId().equals(principalRef.tenantId())) {
            throw new ClientException("文档分块事件的 tenantId 与 principalRef 不一致，拒绝执行：docId="
                    + event.getDocId() + ", tenantId=" + event.getTenantId()
                    + ", principalTenant=" + principalRef.tenantId());
        }

        log.info("[消费者] 开始消费文档分块任务，docId={}, tenantId={}, keys={}",
                event.getDocId(), event.getTenantId(), message.getKeys());

        Runnable execute = () -> {
            ExecutionPrincipal previous = PrincipalContext.set(principalRef.toExecutionPrincipal());
            try {
                UserContext.set(LoginUser.builder().username(event.getOperator()).build());
                documentService.executeChunk(event.getDocId());
            } finally {
                // 清理顺序：先恢复主体，再清旧用户上下文；平台租户由 binder 在
                // runnable 返回后清理（TenantHelper.dynamic 的 finally）。
                PrincipalContext.restore(previous);
                UserContext.clear();
            }
        };

        TenantScopeBinder binder = tenantScopeBinder == null ? null : tenantScopeBinder.getIfAvailable();
        if (binder == null) {
            // 独立应用：没有租户行拦截器，但身份引用仍必须恢复（执行链需要 PrincipalContext）。
            execute.run();
            return;
        }
        binder.runWithTenant(event.getTenantId(), execute);
    }
}
