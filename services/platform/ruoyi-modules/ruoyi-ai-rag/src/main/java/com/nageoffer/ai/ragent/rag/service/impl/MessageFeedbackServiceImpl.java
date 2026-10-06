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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.authorization.AiDomainWriteIdentity;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.rag.controller.request.MessageFeedbackRequest;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.MessageFeedbackMapper;
import com.nageoffer.ai.ragent.rag.mq.event.MessageFeedbackEvent;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageFeedbackServiceImpl implements MessageFeedbackService {

    private final MessageFeedbackMapper feedbackMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final MessageQueueProducer messageQueueProducer;

    @Value("message-feedback_topic${unique-name:}")
    private String feedbackTopic;

    @Override
    public void submitFeedbackAsync(String messageId, MessageFeedbackRequest request) {
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));
        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(messageId)
                .userId(userId)
                .vote(vote)
                .reason(request.getReason())
                .comment(request.getComment())
                .submitTime(System.currentTimeMillis())
                .build();
        messageQueueProducer.send(feedbackTopic, userId + ":" + messageId, "消息反馈", event);
    }

    @Override
    public void cancelFeedbackAsync(String messageId) {
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));

        MessageFeedbackEvent event = MessageFeedbackEvent.builder()
                .messageId(messageId)
                .userId(userId)
                .cancelled(true)
                .submitTime(System.currentTimeMillis())
                .build();
        messageQueueProducer.send(feedbackTopic, userId + ":" + messageId, "取消消息反馈", event);
    }

    @Override
    public void submitFeedback(String messageId, MessageFeedbackRequest request) {
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));

        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        doUpsertFeedback(message, userId,
                vote, request.getReason(), request.getComment(), System.currentTimeMillis());
    }

    @Override
    public Map<String, Integer> getUserVotes(String userId, List<String> messageIds) {
        if (StrUtil.isBlank(userId) || CollUtil.isEmpty(messageIds)) {
            return Collections.emptyMap();
        }
        List<MessageFeedbackDO> records = feedbackMapper.selectList(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
                        .in(MessageFeedbackDO::getMessageId, messageIds)
        );
        if (CollUtil.isEmpty(records)) {
            return Collections.emptyMap();
        }
        return records.stream()
                .collect(Collectors.toMap(
                        MessageFeedbackDO::getMessageId,
                        MessageFeedbackDO::getVote,
                        (first, second) -> first
                ));
    }

    private ConversationMessageDO loadAssistantMessage(String messageId, String userId) {
        ConversationMessageDO message = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, messageId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        Assert.notNull(message, () -> new ClientException("消息不存在"));
        Assert.isTrue("assistant".equalsIgnoreCase(message.getRole()), () -> new ClientException("仅支持对助手消息反馈"));
        return message;
    }

    /**
     * 反馈行的身份取自被反馈的<b>已持久化</b>消息行（受理事实），不取自事件载荷。
     *
     * <p>异步消费者没有 {@code PrincipalContext}；即使有，也必须与消息行一致——否则就是拿别的
     * 成员的消息写自己的反馈。消息行缺身份（走过未适配的写路径）时拒绝，不造默认值。
     *
     * <p>审计列必须显式给值：统一链的 {@code ai_message_feedback.create_time/update_time} 是
     * {@code NOT NULL} 且<b>没有</b>列默认值，而 {@code @TableField(fill = INSERT)} 只对
     * MyBatis-Plus 自己注入的语句生效，不会作用在注解 {@code @Insert} 上——靠它等于靠空值。
     */
    private static MessageFeedbackDO feedbackOf(ConversationMessageDO message, String userId,
                                                Integer vote, String reason, String comment,
                                                long submitTime) {
        Date stamp = new Date(submitTime);
        MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                .messageId(message.getId())
                .conversationId(message.getConversationId())
                .userId(userId)
                .vote(vote)
                .reason(reason)
                .comment(comment)
                .createTime(stamp)
                .updateTime(stamp)
                .build();
        AiDomainWriteIdentity.applyFromPersistedFact(feedback, message.getTenantId(), message.getMemberId());
        return feedback;
    }

    private void doUpsertFeedback(ConversationMessageDO message, String userId,
                                  Integer vote, String reason, String comment, long submitTime) {
        String messageId = message.getId();
        MessageFeedbackDO existing = feedbackMapper.selectOne(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getMessageId, messageId)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
        );

        if (existing == null) {
            feedbackMapper.upsertActiveFeedback(
                    feedbackOf(message, userId, vote, reason, comment, submitTime));
        } else {
            // 仅当本次提交时间晚于记录最后更新时间时才覆盖，避免多节点并行消费乱序
            feedbackMapper.update(
                    MessageFeedbackDO.builder()
                            .vote(vote)
                            .reason(reason)
                            .comment(comment)
                            .build(),
                    Wrappers.lambdaUpdate(MessageFeedbackDO.class)
                            .eq(MessageFeedbackDO::getId, existing.getId())
                            .lt(MessageFeedbackDO::getUpdateTime, new Date(submitTime))
            );
        }
    }

    @Override
    public void submitFeedbackByEvent(MessageFeedbackEvent event) {
        String messageId = event.getMessageId();
        String userId = event.getUserId();
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notBlank(userId, () -> new ClientException("用户ID不能为空"));
        ConversationMessageDO message = loadAssistantMessage(messageId, userId);
        if (event.isCancelled()) {
            feedbackMapper.upsertCancelledFeedback(
                    feedbackOf(message, userId, null, null, null, event.getSubmitTime()));
            return;
        }

        Assert.notNull(event.getVote(), () -> new ClientException("反馈值不能为空"));
        doUpsertFeedback(
                message,
                userId,
                event.getVote(),
                event.getReason(),
                event.getComment(),
                event.getSubmitTime())
        ;
    }
}
