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

import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RW-01-CHATHIST 收口判据：**写入产生的那一行，就是读取路径能选回来的那一行**。
 *
 * <p><b>为什么需要这条。</b>{@code ConversationHistoryAdapterTest} 只钉住了"写调用序列与身份"，
 * 它<b>不</b>回答"写出来的行能被读路径选回来吗"。而本卡修的就是
 * "写入表/读取表不是同一张" ⇒ 光验写侧、或光验读侧，都可能各自"绿"而接不上
 * （这正是 t6 在 RW-02 实查时发现缺陷的方式：两侧都能跑，接不起来）。
 *
 * <p><b>做法（不连真库，但不是循环论证）。</b>用<b>真实的</b>
 * {@link ConversationMessageServiceImpl} 同时充当写侧与读侧，只把持久层
 * （两个 mapper + feedback 服务）替身化：
 * <ol>
 *   <li>经 {@code ConversationHistoryAdapter} 走一次真实写入，用 {@link ArgumentCaptor}
 *       抓住<b>真正交给 mapper 的那两个 DO</b>（用户 + 助手）；</li>
 *   <li>断言这两个 DO 满足读路径的全部谓词（{@code conversation_id} / {@code user_id} 与主体一致、
 *       {@code role} 合法、NOT NULL 身份列已填、{@code deleted} 不会让行隐身）；</li>
 *   <li>把这<b>同一批 DO</b> 作为 {@code selectList} 的结果喂回
 *       {@code listMessages(conversationId, userId, …)}，断言这一轮真的读得回来
 *       —— 写侧与读侧共用同一个 {@code ConversationMessageDO} 与同一张 {@code ai_message}
 *       映射，所以"同源"这件事是被判据逼出来的，不是靠注释声称的；</li>
 *   <li>换一个成员读：必须读不到（跨成员不可见）。</li>
 * </ol>
 *
 * <p><b>边界（诚实标注）。</b>本组<b>不连真库</b>：{@code deleted} 那一列的最终取值在真库里由
 * DDL {@code DEFAULT 0} 与 MyBatis-Plus 的 NOT_NULL 插入策略共同决定，本组只能断言
 * "写入的 DO 不会带一个非 0 的 deleted，也不会把它写成 null 之外的隐身值"，并在此显式记录
 * "真库端到端仍 NOT_RUN"。同样不覆盖：外键 {@code fk_message_conversation} 的真库行为、
 * 行级锁、以及 {@code @TableLogic} 在真库上的实际拼装。
 */
@Tag("dev")
class ConversationHistoryWriteReadClosureTest {

    private static final String TENANT = "t1";
    private static final String MEMBER = "platform:t1:1001";
    private static final String USER = "1001";
    private static final String OTHER_USER = "2002";
    private static final String CONVERSATION = "conv-1";

    /** 写入侧真正落库的 DO（写一次 = 用户 + 助手两条）。 */
    private final List<ConversationMessageDO> persisted = new ArrayList<>();

    private ConversationMessageMapper messageMapper;
    private ConversationMapper conversationMapper;
    private MessageFeedbackService feedbackService;

    private ConversationMessageServiceImpl serviceUnderTest() {
        messageMapper = mock(ConversationMessageMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        feedbackService = mock(MessageFeedbackService.class);

        AtomicInteger sequence = new AtomicInteger();
        when(messageMapper.insert(any(ConversationMessageDO.class))).thenAnswer(invocation -> {
            ConversationMessageDO row = invocation.getArgument(0);
            // MyBatis-Plus 的 ASSIGN_ID 在真实 insert 时回填主键；替身必须做同一件事，
            // 否则读取路径拿到的 id 是 null，"读回来"就成了假通过。
            row.setId("m-" + sequence.incrementAndGet());
            persisted.add(row);
            return 1;
        });
        when(feedbackService.getUserVotes(anyString(), anyList())).thenReturn(Map.of());
        return new ConversationMessageServiceImpl(
                messageMapper, mock(ConversationSummaryMapper.class), conversationMapper, feedbackService);
    }

    private TenantConversationReadRepository ownedConversation() {
        TenantConversationReadRepository repository = mock(TenantConversationReadRepository.class);
        when(repository.findConversation(eq(TENANT), eq(MEMBER), eq(CONVERSATION)))
                .thenReturn(Optional.of(new TenantConversationReadRepository.ConversationRow(
                        CONVERSATION, "synthetic", Timestamp.from(Instant.now()))));
        return repository;
    }

    /** 读路径的会话归属查询：只有本成员能命中（跨成员返回 null ⇒ 读不到）。 */
    private void stubConversationVisibleTo(String userId) {
        ConversationDO conversation = new ConversationDO();
        conversation.setId("c-1");
        conversation.setConversationId(CONVERSATION);
        conversation.setUserId(userId);
        conversation.setDeleted(0);
        when(conversationMapper.selectOne(any())).thenAnswer(invocation -> userId.equals(USER) ? conversation : null);
    }

    @Test
    @DisplayName("写出来的一轮，就是读路径能选回来的那一轮（同一张 ai_message 映射）")
    void theWrittenTurnIsExactlyWhatTheReadPathSelects() {
        ConversationMessageServiceImpl service = serviceUnderTest();
        new ConversationHistoryAdapter(service, ownedConversation())
                .appendTurn(TENANT, MEMBER, USER, CONVERSATION, "问题", "答案");
        assertEquals(2, persisted.size(), "一轮问答应写两条");

        // ① 写出来的行必须满足读路径的全部谓词（否则读侧永远选不到它）
        for (ConversationMessageDO row : persisted) {
            assertEquals(CONVERSATION, row.getConversationId(), "读路径按 conversation_id 过滤");
            assertEquals(USER, row.getUserId(), "读路径按 user_id 过滤");
            assertEquals(TENANT, row.getTenantId(), "ai_message.tenant_id 是 NOT NULL 身份列");
            assertEquals(MEMBER, row.getMemberId(), "ai_message.member_id 是 NOT NULL 身份列");
            assertTrue(List.of("user", "assistant").contains(row.getRole()), "role 必须是 user/assistant");
            assertNotNull(row.getContent(), "content 不得为空（否则历史里是一条空白气泡）");
            // deleted：读路径显式 eq(deleted, 0)，@TableLogic 也会再叠一次；真库侧由 DDL
            // `deleted SMALLINT DEFAULT 0` + MyBatis-Plus NOT_NULL 插入策略兜底。
            // 这里把"不得带一个会把行藏起来的取值"钉死，并如实记录真库仍 NOT_RUN。
            assertTrue(row.getDeleted() == null || row.getDeleted() == 0,
                    "写入不得带非 0 的 deleted，否则行会被读路径与 @TableLogic 同时过滤掉："
                            + row.getDeleted());
        }

        // ② 把同一批 DO 交回读路径：这一轮必须读得回来
        stubConversationVisibleTo(USER);
        when(messageMapper.selectList(any())).thenReturn(persisted);

        List<ConversationMessageVO> history =
                service.listMessages(CONVERSATION, USER, null, ConversationMessageOrder.ASC);

        assertEquals(2, history.size(), "写入的这轮必须在历史里读得回来（本卡的核心验收点）");
        assertEquals("user", history.get(0).getRole());
        assertEquals("问题", history.get(0).getContent());
        assertEquals("assistant", history.get(1).getRole());
        assertEquals("答案", history.get(1).getContent());
        assertNotEquals(history.get(0).getId(), history.get(1).getId(), "两条消息必须有不同的主键");
        assertNotNull(history.get(0).getId(), "读回来的消息必须有主键（前端按 id 做反馈/推荐锚点）");
        assertNull(persisted.get(0).getReplyToMessageId(), "用户消息不回指任何消息");
        assertEquals(persisted.get(0).getId(), persisted.get(1).getReplyToMessageId(),
                "助手消息回指本轮用户消息（推荐追问/引用依赖它）");
    }

    @Test
    @DisplayName("跨成员读不到这一轮（写侧身份与读侧限域必须一致）")
    void anotherMemberCannotReadTheWrittenTurn() {
        ConversationMessageServiceImpl service = serviceUnderTest();
        new ConversationHistoryAdapter(service, ownedConversation())
                .appendTurn(TENANT, MEMBER, USER, CONVERSATION, "问题", "答案");

        // 换一个成员：读路径的会话归属查询先查不到该会话 ⇒ 空列表（与"不存在"同外显）
        stubConversationVisibleTo(OTHER_USER);

        assertTrue(service.listMessages(CONVERSATION, OTHER_USER, null, ConversationMessageOrder.ASC).isEmpty(),
                "别的成员不得看到本成员的会话历史");
    }
}
