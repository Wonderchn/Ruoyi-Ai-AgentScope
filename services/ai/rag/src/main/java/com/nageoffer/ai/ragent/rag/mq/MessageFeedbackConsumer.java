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

package com.nageoffer.ai.ragent.rag.mq;

import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消息反馈 MQ 消费者，负责将点赞/点踩事件异步持久化到数据库
 *
 * <p>P1.2a：本消费者属于<b>未批准旧能力</b>。装配层由
 * {@code ai.integration.legacy-listeners-enabled=true} 才注册（产品配置出现该属性会让启动失败），
 * 消息入口第一行再做一次关闭判定，因此直接调用不会写反馈业务行、不会恢复旧用户上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "message-feedback_topic${unique-name:}",
        consumerGroup = "message-feedback_cg${unique-name:}"
)
public class MessageFeedbackConsumer implements RocketMQListener<MessageWrapper<MessageFeedbackEvent>> {

    private final MessageFeedbackService feedbackService;
    private final ObjectProvider<SaasCapabilityBoundary> capabilityBoundary;

    @Override
    public void onMessage(MessageWrapper<MessageFeedbackEvent> message) {
        // 第一行：关闭判定必须早于日志正文（含 messageId/userId）与任何 service 调用
        SaasCapabilityBoundary.requireOpenOrClosed(capabilityBoundary,
                SaasCapabilityBoundary.LegacyCapability.MESSAGE_FEEDBACK_CONSUMER);

        MessageFeedbackEvent event = message.getBody();

        log.info("[消费者] 开始处理反馈事件，messageId: {}, userId: {}, vote: {}, cancelled: {}, keys: {}",
                event.getMessageId(), event.getUserId(), event.getVote(), event.isCancelled(), message.getKeys());
        feedbackService.submitFeedbackByEvent(event);
    }
}
