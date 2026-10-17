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

package com.nageoffer.ai.ragent.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.TenantObjectReferenceRepository;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import com.nageoffer.ai.ragent.runtime.model.RunRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F04 brief8：Agent 附件回合的**绑定身份判据**——绑定的消息 ID 必须是同回合真正插入的
 * user 行 ID（稳定 {@code cam-} 派生 ID），绝不是 runId，也不允许盲绑。
 *
 * <p>缺陷面（REVIEW-PLANS 明示）：Agent 历史绑定最容易伪装成功的方式是"把 runId 当
 * messageId 写进绑定表"——绑定表有行、历史读面拼不出附件，或绑到错误的消息上。
 * {@code persistAgentTurn} 的写入参数是本类唯一的观测面（捕获真实 JDBC 参数），
 * 并有三个同场判据：</p>
 * <ol>
 *   <li><b>身份</b>：绑定行的 {@code message_id} == 本次插入的 user 消息 id，且 != runId；</li>
 *   <li><b>重放</b>：同一 run 再跑一次得到同一 user id 与同一绑定参数，配合
 *       {@code ON CONFLICT DO NOTHING} 主键不产生第二组绑定；</li>
 *   <li><b>fail-closed</b>：无活动消息事务时拒绑（零写入）；refs 解析不到的附件必须让
 *       整个回合响亮失败，而不是绑一半或盲绑；无附件载荷零写入。</li>
 * </ol>
 */
@Tag("dev")
class AttachmentBindingIdentityTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";
    private static final String USER = "2101";
    private static final String RUN = "run-binding-identity";
    private static final String CONVERSATION = "conv-1";
    private static final String ATTACHMENT = "att-0123456789abcdef0123456789abcdef";
    private static final String BIND_TABLE = "INSERT INTO platform.ai_chat_message_attachment";
    private static final String MESSAGE_TABLE = "INSERT INTO platform.ai_agent_message";

    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ChatAttachmentService service;

    private void givenService() {
        jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ChatAttachmentService(jdbc, mock(PrivateObjectStore.class),
                mock(TenantObjectReferenceRepository.class), mock(AiResourceAuthorizationService.class), txManager);

        when(jdbc.queryForObject(contains("FROM platform.ai_agent_conversation"), eq(Boolean.class),
                any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        when(jdbc.queryForObject(contains("FROM platform.ai_agent_message"), eq(Boolean.class),
                any(), any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        when(jdbc.query(contains("FROM platform.ai_chat_attachment a JOIN"), any(RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("note.txt");
            when(rs.getString(2)).thenReturn("text/plain");
            when(rs.getLong(3)).thenReturn(4L);
            when(rs.getString(4)).thenReturn("cafe");
            when(rs.getString(5)).thenReturn(TENANT + "/attachments/segment");
            when(rs.getLong(6)).thenReturn(1L);
            return List.of(mapper.mapRow(rs, 0));
        });
    }

    private RunRecord run(String inputJson) {
        return new RunRecord(TENANT, RUN, MEMBER, USER, "agent.run", "COMPLETED",
                null, null, inputJson, null, "v1", 1, 1, "[]", 1, 2, 0, 1,
                null, null, null, null, null, null, Instant.now(), null, null);
    }

    private RunRecord attachedRun() {
        return run("{\"conversationId\":\"" + CONVERSATION + "\",\"attachments\":[{\"id\":\"" + ATTACHMENT
                + "\",\"version\":1}],\"text\":\"hello\"}");
    }

    /** 事务管理器是 mock，TransactionTemplate 不会替我们激活事务同步；绑定门要求真实活动事务标记。 */
    private void persist(RunRecord run) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.persistAgentTurn(run, "hello", "world");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private static final class TurnWrites {
        String userId;
        String assistantId;
        String assistantReplyTo;
        final List<Object[]> binds = new ArrayList<>();
        final List<String> bindSql = new ArrayList<>();
    }

    private TurnWrites captureTurn() {
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), args.capture());
        TurnWrites writes = new TurnWrites();
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            String statement = sql.getAllValues().get(i);
            Object[] parameters = args.getAllValues().get(i);
            if (statement.startsWith(MESSAGE_TABLE)) {
                if ("user".equals(parameters[5])) {
                    writes.userId = String.valueOf(parameters[0]);
                } else {
                    writes.assistantId = String.valueOf(parameters[0]);
                    writes.assistantReplyTo = String.valueOf(parameters[7]);
                }
            } else if (statement.startsWith(BIND_TABLE)) {
                writes.binds.add(parameters);
                writes.bindSql.add(statement);
            }
        }
        return writes;
    }

    private static final String BIND_COLUMNS = "tenant_id,member_id,user_id,message_kind,conversation_id,message_id,"
            + "attachment_id,object_version";

    @Test
    @DisplayName("绑定身份：message_id == 本回合插入的 user 行 id，绝不是 runId")
    void bindTargetsTheInsertedUserMessageIdNeverTheRunId() throws Exception {
        givenService();
        persist(attachedRun());

        TurnWrites writes = captureTurn();
        assertThat(writes.userId).as("必须恰好插入一条 user 消息").isNotNull();
        assertThat(writes.assistantId).as("必须恰好插入一条 assistant 消息").isNotNull();
        assertThat(writes.assistantReplyTo).isEqualTo(writes.userId);
        assertThat(writes.binds).as("一个附件回合恰好一组绑定").hasSize(1);

        Object[] bind = writes.binds.get(0);
        assertThat(bind).hasSize(8);
        assertThat(String.valueOf(bind[0])).isEqualTo(TENANT);
        assertThat(String.valueOf(bind[1])).isEqualTo(MEMBER);
        assertThat(String.valueOf(bind[2])).isEqualTo(USER);
        assertThat(String.valueOf(bind[3])).isEqualTo("AGENT");
        assertThat(String.valueOf(bind[4])).isEqualTo(CONVERSATION);
        assertThat(String.valueOf(bind[5]))
                .as("绑定的消息 ID 必须是真正插入的那条 user 行").isEqualTo(writes.userId);
        assertThat(String.valueOf(bind[5])).as("runId 不是 messageId").isNotEqualTo(RUN);
        assertThat(String.valueOf(bind[6])).isEqualTo(ATTACHMENT);
        assertThat(String.valueOf(bind[7])).isEqualTo("1");
        assertThat(writes.bindSql.get(0))
                .as("绑定写入必须保留 ON CONFLICT DO NOTHING：" + BIND_COLUMNS)
                .contains("ON CONFLICT DO NOTHING");
    }

    @Test
    @DisplayName("重放同一 run：同一 user id、同一绑定参数，不产生第二组绑定")
    void replayIsIdempotentAndCannotCreateASecondBindingSet() throws Exception {
        givenService();
        persist(attachedRun());
        TurnWrites first = captureTurn();

        givenService();
        persist(attachedRun());
        TurnWrites replay = captureTurn();

        assertThat(replay.userId).as("重放必须落到同一稳定 user id").isEqualTo(first.userId);
        assertThat(replay.binds).hasSize(1);
        assertThat(replay.binds.get(0))
                .as("重放的绑定参数必须与首次逐字相同（主键冲突被 DO NOTHING 吃掉，第二组绑定不成立）")
                .containsExactly(first.binds.get(0));
        assertThat(String.valueOf(replay.binds.get(0)[5])).isNotEqualTo(RUN);
    }

    @Test
    @DisplayName("fail-closed：无活动消息事务时拒绑，零写入")
    void bindingOutsideTheMessageTransactionIsRefusedWithZeroWrites() throws Exception {
        givenService();
        var refs = json.readTree("[{\"id\":\"" + ATTACHMENT + "\",\"version\":1}]");

        assertThatThrownBy(() -> service.bind(TENANT, MEMBER, USER, "AGENT", CONVERSATION, "cam-0000000000000000", refs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("无附件载荷：不写任何 Agent 消息、不产生绑定")
    void turnWithoutAttachmentRefsWritesNoRowsAtAll() {
        givenService();
        persist(run("{\"conversationId\":\"" + CONVERSATION + "\",\"text\":\"hello\"}"));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("解析不到的附件引用：整个回合响亮失败，不盲绑、不落半组绑定")
    void unresolvableAttachmentRefFailsTheTurnInsteadOfBindingBlindly() throws Exception {
        givenService();
        when(jdbc.query(contains("FROM platform.ai_chat_attachment a JOIN"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> persist(attachedRun()))
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode())
                        .isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));

        verify(jdbc, never()).update(contains(BIND_TABLE), any(Object[].class));
    }
}
