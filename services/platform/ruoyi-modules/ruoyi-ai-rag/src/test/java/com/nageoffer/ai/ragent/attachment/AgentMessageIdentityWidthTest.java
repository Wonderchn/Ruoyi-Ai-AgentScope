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

import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.TenantObjectReferenceRepository;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F04：Agent 附件回合写入 {@code ai_agent_message} 的消息 ID，必须匹配冻结形态
 * V7 的 {@code id VARCHAR(20) NOT NULL PRIMARY KEY} / {@code reply_to_message_id VARCHAR(20)}。
 *
 * <p>红基线针对旧树：旧实现生成 {@code cam-}+40hex=44 字符，直接超出 VARCHAR(20)；
 * 本类在真实写入参数上捕获（不是读源码字符串），并附带 V7 DDL 形态护栏：
 * <ol>
 *   <li>两条消息行（user/assistant）的 id 参数 ≤20 且形态稳定可复现（重放同 run 得到同一组 ID，
 *       与 {@code ON CONFLICT(id) DO NOTHING} 的幂等语义一起成立）；</li>
 *   <li>assistant 的 {@code reply_to_message_id} 指向同一 run 的 user 行、同样 ≤20；</li>
 *   <li>冻结 DDL 若改变这两列宽度，护栏必须报警（两者只能一起动）。</li>
 * </ol>
 */
@Tag("dev")
class AgentMessageIdentityWidthTest {

    private static final String TENANT = "T1";
    private static final String RUN = "run-fixed-message-id";
    private static final String ATTACHMENT = "att-0123456789abcdef0123456789abcdef";

    private JdbcTemplate jdbc;
    private ChatAttachmentService service;

    private void givenService() {
        jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ChatAttachmentService(jdbc, mock(PrivateObjectStore.class),
                mock(TenantObjectReferenceRepository.class), mock(AiResourceAuthorizationService.class), txManager);

        // 归属会话、绑定消息都存在（形态判据只关心写参数）。
        when(jdbc.queryForObject(contains("FROM platform.ai_agent_conversation"), eq(Boolean.class),
                any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        when(jdbc.queryForObject(contains("FROM platform.ai_agent_message"), eq(Boolean.class),
                any(), any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        // row() 的真实映射路径：用受控 ResultSet 让私有 Row 由实现自身的 mapper 构造。
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
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

    private RunRecord run() {
        String input = "{\"conversationId\":\"conv-1\",\"attachments\":[{\"id\":\"" + ATTACHMENT
                + "\",\"version\":1}],\"text\":\"hello\"}";
        return new RunRecord(TENANT, RUN, "platform:T1:2101", "2101", "agent.run", "COMPLETED",
                null, null, input, null, "v1", 1, 1, "[]", 1, 2, 0, 1,
                null, null, null, null, null, null, Instant.now(), null, null);
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

    private List<String> capturedMessageIds(List<Object[]> argumentSets, List<String> sqls) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < sqls.size(); i++) {
            if (sqls.get(i).startsWith("INSERT INTO platform.ai_agent_message")) {
                ids.add(String.valueOf(argumentSets.get(i)[0]));
            }
        }
        return ids;
    }

    private List<String> messageIdsWrittenByOnePersist() {
        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        var argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.atLeast(1)).update(sqlCaptor.capture(), argsCaptor.capture());
        return capturedMessageIds(argsCaptor.getAllValues(), sqlCaptor.getAllValues());
    }

    @Test
    @DisplayName("写入参数实测：user/assistant id ≤20、稳定、互异；reply_to 指向同 run 的 user 行")
    void messageIdsFitTheFrozenVarchar20Columns() {
        givenService();
        persist(run());

        List<String> ids = messageIdsWrittenByOnePersist();
        assertThat(ids).as("一个附件回合必须恰好写 user+assistant 两条消息").hasSize(2);
        for (String id : ids) {
            assertThat(id.length()).as("V7 ai_agent_message.id 是 VARCHAR(20)，实际: " + id)
                    .isLessThanOrEqualTo(20);
        }
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        var argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.atLeast(1)).update(sqlCaptor.capture(), argsCaptor.capture());
        String replyTo = null;
        for (int i = 0; i < sqlCaptor.getAllValues().size(); i++) {
            if (sqlCaptor.getAllValues().get(i).startsWith("INSERT INTO platform.ai_agent_message")
                    && "assistant".equals(argsCaptor.getAllValues().get(i)[5])) {
                replyTo = String.valueOf(argsCaptor.getAllValues().get(i)[7]);
            }
        }
        assertThat(replyTo).as("assistant 必须回复同一 run 的 user 行，且不超过 VARCHAR(20)").isEqualTo(ids.get(0));
    }

    @Test
    @DisplayName("重放同一 run：生成的 ID 不变（ON CONFLICT(id) DO NOTHING 因此不产生第二组绑定）")
    void replayingTheSameRunReusesTheSameStableIds() {
        givenService();
        persist(run());
        List<String> first = messageIdsWrittenByOnePersist();

        givenService();
        persist(run());
        List<String> replay = messageIdsWrittenByOnePersist();

        assertThat(replay).as("重放必须是同一组稳定 ID（幂等），而不是新随机 ID").isEqualTo(first);
    }

    @Test
    @DisplayName("冻结 V7 DDL 护栏：两列仍是 VARCHAR(20)（宽度变化必须与 ID 生成一起过审）")
    void theFrozenV7DdlStillBoundsBothColumnsToTwenty() throws Exception {
        String sql = Files.readString(migrationFile(), StandardCharsets.UTF_8);
        Matcher table = Pattern.compile(
                "CREATE TABLE IF NOT EXISTS platform\\.ai_agent_message\\s*\\((.*?)\\);",
                Pattern.DOTALL).matcher(sql);
        assertThat(table.find()).as("V7 必须仍定义 platform.ai_agent_message").isTrue();
        String block = table.group(1);
        assertThat(Pattern.compile("id\\s+VARCHAR\\(20\\)\\s+NOT NULL PRIMARY KEY").matcher(block).find())
                .as("ai_agent_message.id 必须是 VARCHAR(20) NOT NULL PRIMARY KEY").isTrue();
        assertThat(Pattern.compile("reply_to_message_id\\s+VARCHAR\\(20\\)").matcher(block).find())
                .as("ai_agent_message.reply_to_message_id 必须是 VARCHAR(20)").isTrue();
    }

    private static Path migrationFile() {
        String relative = "services/platform/docs/script/sql/postgres/V7__unified_ai_domain.sql";
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("找不到 " + relative);
    }
}
