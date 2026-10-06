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

package com.nageoffer.ai.ragent.authorization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-035B：会话历史读取的列覆盖护栏（构建期，不需要数据库）。
 *
 * <p>WP-035A 把 {@code thinking_content}/{@code thinking_duration}/{@code sources}/
 * {@code recommended_questions}/{@code retrieved_chunks}/{@code reply_to_message_id} 补回了读取路径，
 * 但那条判据在真库上跑——没有 {@code -Dragent.conversation.test.jdbc-url} 时会**跳过**。
 * 于是"完整历史"这件事在默认构建里没有任何保护：删掉一列、写错一个列名、
 * 或者将来给 {@code ai_message} 加了新列而读取路径没跟上，都不会让构建失败。
 *
 * <p>本护栏补上这一层，判三条：
 * <ol>
 *   <li><b>写的列必须存在</b>：{@link TenantConversationReadRepository#MESSAGE_COLUMNS} 里的每一列
 *       都必须在冻结形状里（挡住列名笔误与"改了名没改 SQL"）；</li>
 *   <li><b>要求的列不能少</b>：登记为"历史必需"的列必须在选择列表里；</li>
 *   <li><b>新增列必须做决定</b>：冻结形状的每一列都必须落在"已选"或"显式排除"之一，
 *       两边都登记不到的列会让构建失败——这是防漂移的关键一条，它把"以后加列"变成
 *       一个必须显式回答的问题，而不是一个静默的遗漏。</li>
 * </ol>
 */
@Tag("dev")
class ConversationHistoryColumnGuardTest {

    /**
     * 历史必需的列（F03："历史包含附件、引用、工具、模型/工作流绑定，不只保留 message.content"）。
     * 这些列少一个，前端就拿不到对应信息。
     */
    private static final Set<String> HISTORY_REQUIRED = Set.of(
            "id", "role", "content", "message_status", "create_time",
            "thinking_content", "thinking_duration",
            "sources", "recommended_questions", "retrieved_chunks", "reply_to_message_id",
            // WP-035B：覆盖护栏逼出来的两列——model_name 是 F03 的"模型绑定"，total_tokens 是逐消息用量
            "model_name", "total_tokens");

    /**
     * 显式排除、不进历史读取的列。每条都要有理由——没有理由就该进选择列表。
     *
     * <ul>
     *   <li>{@code tenant_id}/{@code member_id}/{@code conversation_id}：作为**过滤条件**出现，
     *       调用方已经知道它们的值，回传只是冗余；</li>
     *   <li>{@code user_id}：与 {@code member_id} 同源的旧列，归属判定以 member 为准；</li>
     *   <li>{@code deleted}：读取恒为 0（软删行不返回），回传没有信息量；</li>
     *   <li>{@code update_time}：消息内容不可编辑（追加表），编辑时间不构成历史事实；</li>
     *   <li>{@code session_id}：**内部会话主键**（V9 追加的 bigint）。公开面只用
     *       {@code conversation_id}；把主键也回传会让两个 id 混在一起——而 WP-024 正是刻意把
     *       "会话主键"与"公开 conversation_id"分开的；</li>
     *   <li>{@code create_by}/{@code update_by}/{@code create_dept}：平台审计列（bigint）。
     *       审计主体/部门属平台管理面，不是 AI 消息契约的一部分；而且它们在旧行里可能是
     *       {@code ''} 或非数值（WP-026C 实测 {@code getLong} 会抛错），读取路径不碰它们；</li>
     *   <li>{@code remark}：旧平台消息表（已并入 {@code ai_message}）独有列，非 AI 消息契约的一部分。
     *       管理端若需要，走平台侧实体读取。</li>
     * </ul>
     */
    private static final Set<String> HISTORY_EXCLUDED = Set.of(
            "tenant_id", "member_id", "conversation_id", "user_id", "deleted", "update_time",
            "session_id", "create_by", "update_by", "create_dept", "remark");

    @Test
    @DisplayName("选择的每一列都必须存在于冻结形状（挡住列名笔误与改名漏改）")
    void selectedColumnsExistInTheFrozenShape() throws IOException {
        Set<String> frozen = frozenMessageColumns();
        assertThat(frozen)
                .as("锚点：必须真的从冻结迁移读到 ai_message 的列，否则本判据是空跑")
                .hasSizeGreaterThanOrEqualTo(15)
                .contains("id", "conversation_id", "member_id", "retrieved_chunks");

        List<String> unknown = new ArrayList<>();
        for (String selected : selectedColumnNames()) {
            if (!frozen.contains(selected)) {
                unknown.add(selected);
            }
        }
        assertThat(unknown)
                .as("读取路径选了冻结形状里不存在的列")
                .isEmpty();
    }

    @Test
    @DisplayName("历史必需的列一个都不能少")
    void everyRequiredHistoryColumnIsSelected() {
        Set<String> selected = selectedColumnNames();
        assertThat(selected)
                .as("F03 要求历史不只保留 message.content；缺列等于前端拿不到数据")
                .containsAll(HISTORY_REQUIRED);
    }

    @Test
    @DisplayName("冻结形状的每一列都必须落在『已选』或『显式排除』里（新增列必须做决定）")
    void everyFrozenColumnIsEitherSelectedOrExplicitlyExcluded() throws IOException {
        Set<String> frozen = frozenMessageColumns();
        Set<String> selected = selectedColumnNames();

        Set<String> undecided = new TreeSet<>(frozen);
        undecided.removeAll(selected);
        undecided.removeAll(HISTORY_EXCLUDED);

        assertThat(undecided)
                .as("ai_message 新增了列而读取路径既没选它、也没显式排除它。"
                        + "请判断它是否属于 F03 的历史要求：属于就加进 MESSAGE_COLUMNS，"
                        + "不属于就加进 HISTORY_EXCLUDED 并写明理由。")
                .isEmpty();
        assertThat(selected).as("两个登记表不应重叠").doesNotContainAnyElementsOf(HISTORY_EXCLUDED);
    }

    @Test
    @DisplayName("负例实证：覆盖判定器真的能拒绝不存在的列")
    void checkerRejectsAbsentColumns() throws IOException {
        Set<String> frozen = frozenMessageColumns();
        assertThat(frozen).contains("thinking_content");
        assertThat(frozen).doesNotContain("thinking_content_typo");
        assertThat(frozen).doesNotContain("message_status_v2");
        // 反向：登记的必需列必须真的在形状里，否则 HISTORY_REQUIRED 自己就是错的
        assertThat(frozen).containsAll(HISTORY_REQUIRED);
        assertThat(frozen).containsAll(HISTORY_EXCLUDED);
    }

    // ------------------------------------------------------------------ helpers

    /** 从 {@code MESSAGE_COLUMNS} 取出列名：去掉 {@code ::text AS alias} 这类修饰。 */
    private static Set<String> selectedColumnNames() {
        Set<String> out = new TreeSet<>();
        for (String entry : TenantConversationReadRepository.MESSAGE_COLUMNS) {
            String name = entry;
            int as = name.toUpperCase(Locale.ROOT).lastIndexOf(" AS ");
            if (as >= 0) {
                name = name.substring(as + 4).trim();
            } else {
                int cast = name.indexOf("::");
                if (cast >= 0) {
                    name = name.substring(0, cast);
                }
            }
            out.add(name.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 冻结形状里 {@code ai_message} 的列名（建表 + 后续 ADD COLUMN）。 */
    private static Set<String> frozenMessageColumns() throws IOException {
        Set<String> out = new TreeSet<>(FrozenTableDdl.columnNamesOf("ai_message"));
        assertThat(out).as("必须解析到 ai_message 的列").isNotEmpty();
        return out;
    }
}
