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
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.ingest.PrivateObjectStore;
import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F04：私有附件内容读取必须对**已绑定消息**复验会话/消息仍可读。
 *
 * <p>红基线针对旧树：旧 {@code content()} 只验上传者+OBJECT 状态/hash/version，
 * 绑定所在会话被删或归属变化后仍能取到字节 ⇒ 负例断言失败（行为红）。
 * 修正后语义：
 * <ol>
 *   <li>绑定了消息的附件，预览读取时必须逐条复验 {@code conversation_id}/{@code message_id}
 *       在当前主体下仍可读（与会话读取、绑定写入同一谓词；归属变化"等同不存在"）；</li>
 *   <li>未绑定的上传（发送前预览）仍可读——复验只约束"已绑定"这一事实；</li>
 *   <li>复验发生在释放字节之前，失败与"不存在"同形（404 语义，不泄露存在性）。</li>
 * </ol>
 */
@Tag("dev")
class ChatAttachmentContentBindingTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";
    private static final String USER = "2101";
    private static final String ATTACHMENT = "att-0123456789abcdef0123456789abcdef";
    private static final byte[] BYTES = "note".getBytes(StandardCharsets.UTF_8);

    private final ExecutionPrincipal principal = new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1,
            Set.of("document.read"), "jti", "platform", 0, Long.MAX_VALUE);

    private JdbcTemplate jdbc;
    private PrivateObjectStore store;
    private ChatAttachmentService service;

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** bound=true 时该附件在 RAG 会话 conv-1 的 user 消息 msg-1 上有一条绑定。 */
    private void given(boolean conversationReadable, boolean messageReadable, boolean bound) {
        jdbc = mock(JdbcTemplate.class);
        store = mock(PrivateObjectStore.class);
        when(store.exists(anyString())).thenReturn(true);
        when(store.get(anyString())).thenReturn(BYTES);

        when(jdbc.query(contains("FROM platform.ai_chat_message_attachment"), any(RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
            if (!bound) {
                return List.of();
            }
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("RAG");
            when(rs.getString(2)).thenReturn("conv-1");
            when(rs.getString(3)).thenReturn("msg-1");
            return List.of(mapper.mapRow(rs, 0));
        });
        when(jdbc.query(contains("FROM platform.ai_chat_attachment a JOIN"), any(RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("note.txt");
            when(rs.getString(2)).thenReturn("text/plain");
            when(rs.getLong(3)).thenReturn((long) BYTES.length);
            when(rs.getString(4)).thenReturn(sha256(BYTES));
            when(rs.getString(5)).thenReturn(TENANT + "/attachments/segment");
            when(rs.getLong(6)).thenReturn(1L);
            return List.of(mapper.mapRow(rs, 0));
        });
        when(jdbc.queryForObject(contains("FROM platform.ai_conversation WHERE"), eq(Boolean.class),
                any(), any(), any(), any())).thenReturn(conversationReadable);
        when(jdbc.queryForObject(contains("FROM platform.ai_message WHERE"), eq(Boolean.class),
                any(), any(), any(), any(), any())).thenReturn(messageReadable);

        service = new ChatAttachmentService(jdbc, store, mock(TenantObjectReferenceRepository.class),
                mock(AiResourceAuthorizationService.class), mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("负例：绑定会话已删除/归属变化 ⇒ 读取拒绝（与会话读取同外显）")
    void boundAttachmentInAGoneConversationIsRefused() {
        given(false, true, true);
        assertThatThrownBy(() -> service.content(principal, ATTACHMENT, 1))
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode())
                        .isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
    }

    @Test
    @DisplayName("负例：绑定消息不可读（删除/归属变化）⇒ 读取拒绝")
    void boundAttachmentOnAGoneMessageIsRefused() {
        given(true, false, true);
        assertThatThrownBy(() -> service.content(principal, ATTACHMENT, 1))
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode())
                        .isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
    }

    @Test
    @DisplayName("正例：绑定会话与消息仍可读 ⇒ 正常返回内容")
    void readableBindingStillReleasesTheBytes() {
        given(true, true, true);
        var content = service.content(principal, ATTACHMENT, 1);
        assertThat(content.mime()).isEqualTo("text/plain");
        assertThat(content.bytes()).isEqualTo(BYTES);
    }

    @Test
    @DisplayName("正例：未绑定的上传（发送前预览）不因复验被拒")
    void unboundUploadRemainsPreviewable() {
        given(false, false, false);
        var content = service.content(principal, ATTACHMENT, 1);
        assertThat(content.bytes()).isEqualTo(BYTES);
    }
}
