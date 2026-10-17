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
import java.sql.ResultSet;
import java.util.ArrayList;
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
 * F04:4：**属主可见的陈旧版本 → 409**（MAINTAINER-DECISIONS 45-②；契约
 * {@code SPEC.md:92} / {@code HANDOFF-P2 §5D}）。
 *
 * <p>契约：缺失/删除/撤权统一 **404**、陈旧版本 content **409**、未登录 401。
 * 基线行为缺口：{@code ChatAttachmentService.row()} 的严格 JOIN 含请求版本等值，
 * 未命中一律 404 ⇒ 属主拿着旧版本号预览时，分不清"附件不存在"与"版本已陈旧"
 * （探针 D.8：请求 version＝实际+1 ⇒ 期望 409，当前 404）。
 *
 * <p>修正语义（仍在 {@code row()} 内，调用方零签名变化）：
 * <ol>
 *   <li>严格未命中 → 跑一次**属主可见探测**（同一 JOIN 谓词、去掉请求版本等值，
 *       仍要求 {@code a.status='ACTIVE'} 且 {@code r.status='ACTIVE' AND
 *       r.resource_version=a.object_version}）；</li>
 *   <li>恰 1 行且 {@code object_version <> 请求版本} ⇒ {@code VERSION_CONFLICT}（409）；</li>
 *   <li>探测空 / 多行 / 其余异常形态 ⇒ **404** fail-closed（未知 id / 非属主 / 跨租户 /
 *       删除 / 撤权 / 资源非 ACTIVE 一律不可区分，不泄露存在性）；</li>
 *   <li>{@code list()} 的"跳过不可读"语义保持：陈旧 409 与新 404 一并吞掉跳过。</li>
 * </ol>
 *
 * <p>红基线：本类 1/2（陈旧上/下）在旧实现上失败（拿到 404 而非 409）；3/4/5/6 为
 * 绿基线（404 与跳过语义不得被修复破坏）。
 */
@Tag("dev")
class ChatAttachmentStaleVersionTest {

    private static final String TENANT = "T1";
    private static final String MEMBER = "platform:T1:2101";
    private static final String USER = "2101";
    private static final String ATTACHMENT = "att-0123456789abcdef0123456789abcdef";
    private static final byte[] BYTES = "note".getBytes(StandardCharsets.UTF_8);

    /** 严格面（含请求版本等值）独有子串；探测面永不出现（探测只 SELECT 版本、不过滤版本）。 */
    private static final String STRICT_MARKER = "a.object_version=?";
    /** 探测面（属主可见）独有子串：同一 JOIN、无版本等值。 */
    private static final String PROBE_MARKER = "SELECT a.object_version FROM platform.ai_chat_attachment a JOIN";
    /** list() 的绑定行查询独有子串。 */
    private static final String BOUND_MARKER = "SELECT attachment_id,object_version FROM platform.ai_chat_message_attachment";

    private final ExecutionPrincipal principal = new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1,
            Set.of("document.read"), "jti", "platform", 0, Long.MAX_VALUE);

    private JdbcTemplate jdbc;
    private ChatAttachmentService service;

    /**
     * 建模一次 row() 调用：严格查询（请求版本等值）恒未命中；
     * {@code probeRows} 为属主可见探测返回的 {@code object_version} 列
     * （空 = 探测空：未知 id / 非属主 / 跨租户 / 删除 / 撤权 / 资源非 ACTIVE）。
     */
    private void given(List<Long> probeRows, boolean boundRef) {
        jdbc = mock(JdbcTemplate.class);
        PrivateObjectStore store = mock(PrivateObjectStore.class);
        when(store.exists(anyString())).thenReturn(true);
        when(store.get(anyString())).thenReturn(BYTES);
        when(jdbc.query(contains(STRICT_MARKER), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.query(contains(PROBE_MARKER), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    List<Object> mapped = new ArrayList<>();
                    for (Long version : probeRows) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getLong(1)).thenReturn(version);
                        mapped.add(mapper.mapRow(rs, 0));
                    }
                    return mapped;
                });
        when(jdbc.queryForObject(contains("FROM platform.ai_conversation WHERE"), eq(Boolean.class),
                any(), any(), any(), any())).thenReturn(true);
        when(jdbc.query(contains(BOUND_MARKER), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    if (!boundRef) {
                        return List.of();
                    }
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString(1)).thenReturn(ATTACHMENT);
                    when(rs.getLong(2)).thenReturn(2L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        service = new ChatAttachmentService(jdbc, store, mock(TenantObjectReferenceRepository.class),
                mock(AiResourceAuthorizationService.class), mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("陈旧向上：属主可见、请求=当前+1 ⇒ 409 VERSION_CONFLICT（红：现状 404）")
    void staleUpwardIsDistinguishedAsVersionConflict() {
        given(List.of(2L), false);

        assertThatThrownBy(() -> service.content(principal, ATTACHMENT, 3))
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode())
                        .isEqualTo(RunErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("陈旧向下：属主可见、当前=2、请求=1 ⇒ 409 VERSION_CONFLICT（红：现状 404）")
    void staleDownwardIsDistinguishedAsVersionConflict() {
        given(List.of(2L), false);

        assertThatThrownBy(() -> service.content(principal, ATTACHMENT, 1))
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode())
                        .isEqualTo(RunErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("探测空（未知 id / 非属主 / 跨租户）⇒ 仍 404 fail-closed（不泄露存在性）")
    void probeMissStaysNotFound() {
        given(List.of(), false);

        assertThatThrownBy(() -> service.content(principal, ATTACHMENT, 9))
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode())
                        .isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
    }

    @Test
    @DisplayName("撤权/资源非 ACTIVE（探测空）⇒ 仍 404，即使请求版本与当前一致")
    void revokedOwnerStaysNotFound() {
        given(List.of(), false);

        assertThatThrownBy(() -> service.content(principal, ATTACHMENT, 2))
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode())
                        .isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
    }

    @Test
    @DisplayName("其余异常形态（探测多行）⇒ 404 fail-closed，不猜版本")
    void ambiguousProbeStaysNotFound() {
        given(List.of(2L, 3L), false);

        assertThatThrownBy(() -> service.content(principal, ATTACHMENT, 1))
                .isInstanceOf(RunApiException.class)
                .satisfies(ex -> assertThat(((RunApiException) ex).errorCode())
                        .isEqualTo(RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
    }

    @Test
    @DisplayName("list() 绑定版本陈旧 ⇒ 跳过不抛（保持『跳过不可读』语义）")
    void listSkipsStaleBindingsInsteadOfFailing() {
        given(List.of(1L), true);

        // 绑定的 object_version=2，属主可见当前版本=1 ⇒ row() 抛 409；list 必须吞掉并跳过。
        assertThat(service.list(TENANT, MEMBER, USER, "RAG", "conv-1", "msg-1")).isEmpty();
    }
}
