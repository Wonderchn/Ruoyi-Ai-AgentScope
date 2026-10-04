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

package com.nageoffer.ai.ragent.agent.state;

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryControlDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryControlMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryCommit;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryDecision;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Tag;

/**
 * U09 / P1.3d（03 C09a）：state 与长期记忆的隔离护栏。
 *
 * <p><b>为什么盯住复合键本身。</b>t_agent_state 这类表的主键是
 * {@code (tenant_id, member_id, session_id, state_key)}——租户与成员不是
 * 表上"多出来的一列"，而是键的本体：两个租户的同名用户、同 session、同 key
 * 在物理上就是两行，读、写、删都不可能越过键碰到对方。因此本测试断言的是
 * <b>SQL 文本</b>而不是行为推断：
 * <ul>
 *   <li>{@link AgentStateMapper} 全部语句恒含
 *       {@code tenant_id = #{tenantId} AND member_id = #{memberId}}，upsert 的
 *       冲突目标是完整复合键，{@code user_id}（展示/legacy 引用）不进任何
 *       WHERE 与键；</li>
 *   <li>{@link PgAgentStateStore} 是 SQL 之外唯一的入口：无主体 fail-closed
 *       且零 DAO 交互；有主体时 DAO 收到的前两个参数恒为 (tenantId, membershipId)；</li>
 *   <li>memory 三个 mapper 的自定义语句同样逐条核对；控制面的行锁与版本号
 *       按 (tenant, member) 定位——同 userId 跨租户互不排队、互不合并；</li>
 *   <li>{@link AgentMemoryRepository} 无主体拒绝，commit 路径先按
 *       (tenant, member) 取行锁。</li>
 * </ul>
 *
 * <p>SQL 文本断言的价值：行为测试只能证明"给这个桩这些参数时路径正确"，
 * 证明不了"不存在另一条漏了租户条件的语句"。文本断言逐条核对源文件里
 * 全部语句，新增一条无租户语句就会让本测试失败。
 */
@Tag("dev")
class P1StateAndMemoryIsolationTest {

    private static final String TENANT_A = "T1";
    private static final String TENANT_B = "T2";
    private static final String USER_ID = "2101";
    private static final String MEMBER_A = "platform:" + TENANT_A + ":" + USER_ID;
    private static final String MEMBER_B = "platform:" + TENANT_B + ":" + USER_ID;
    private static final String SESSION_ID = "sess-shared";
    private static final String STATE_KEY = "k1";

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);

    /** 复合键的租户/成员等值谓词：状态与记忆全部语句的公共形状。 */
    private static final String TENANT_MEMBER_EQ =
            "tenant_id = #{tenantId} AND member_id = #{memberId}";

    @BeforeAll
    static void initJsonCodec() {
        // PgAgentStateStore 的 payload 编解码依赖 AgentScope 的静态 codec；
        // 单测 JVM 没人装配它，这里显式重置为默认 Jackson 实现
        JsonUtils.resetToDefault();
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    // ------------------------------------------------------------ AgentStateMapper SQL 文本

    @Test
    @DisplayName("AgentStateMapper：六条语句恒带租户/成员条件，upsert 冲突目标是完整复合键")
    void agentStateMapperSqlAlwaysScopedByCompositeKey() throws IOException {
        List<String> statements = sqlStatements(agentMainSource("dao/mapper/AgentStateMapper.java"));

        assertThat(statements)
                .as("AgentStateMapper 的语句数：upsert/selectPayload/exists/deleteBySession/deleteByKey/selectSessionIds")
                .hasSize(7);

        String upsert = statementContaining(statements, "INSERT INTO t_agent_state");
        // 冲突目标必须是完整复合键：键里少任何一维，两个租户的同名数据就会互相覆盖
        assertThat(upsert)
                .contains("ON CONFLICT (tenant_id, member_id, session_id, state_key)")
                .doesNotContain("ON CONFLICT (session_id, state_key)")
                .contains("INSERT INTO t_agent_state (tenant_id, member_id, user_id, session_id, state_key, payload");
        // user_id 只出现在展示列清单与 VALUES 里，不进冲突目标、不进 DO UPDATE
        String conflictClause = upsert.substring(upsert.indexOf("ON CONFLICT"));
        assertThat(conflictClause).doesNotContain("user_id");

        // 其余五条全部是查询/删除：谓词恒含租户 + 成员，且完全不引用 user_id
        List<String> others = new ArrayList<>(statements);
        others.remove(upsert);
        for (String statement : others) {
            assertThat(statement)
                    .as("语句必须恒含租户/成员等值谓词：%s", statement)
                    .contains(TENANT_MEMBER_EQ)
                    .as("user_id 不参与任何 WHERE/键，只允许以展示引用出现在 upsert 的 VALUES 里：%s", statement)
                    .doesNotContain("user_id");
        }

        // 读写删三类语句各就各位：sess-shared/k1 在 T1/T2 各一行，靠的就是这些谓词
        assertThat(statementContaining(statements, "SELECT payload"))
                .contains("session_id = #{sessionId} AND state_key = #{stateKey}");
        assertThat(statements.stream().filter(s -> s.contains("DELETE FROM t_agent_state")).count())
                .as("删除语句两条：按会话删、按键删")
                .isEqualTo(2);
        assertThat(statementContaining(statements, "SELECT DISTINCT session_id"))
                .contains("ORDER BY session_id");
    }

    // ------------------------------------------------------------ PgAgentStateStore 行为

    @Test
    @DisplayName("PgAgentStateStore：无主体全部入口拒绝且零 mapper 交互")
    void pgAgentStateStoreRejectsEveryEntryWithoutPrincipal() {
        AgentStateMapper mapper = mock(AgentStateMapper.class);
        PgAgentStateStore store = new PgAgentStateStore(mapper);
        State state = new PayloadState("v");

        assertThatThrownBy(() -> store.save(USER_ID, SESSION_ID, STATE_KEY, state))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> store.save(USER_ID, SESSION_ID, STATE_KEY, List.of(state)))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> store.getList(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> store.exists(USER_ID, SESSION_ID)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> store.delete(USER_ID, SESSION_ID)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> store.delete(USER_ID, SESSION_ID, STATE_KEY)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> store.listSessionIds(USER_ID)).isInstanceOf(ClientException.class);

        // fail-closed 的铁证：拒绝发生在任何 DAO 访问之前，匿名流量根本没有落库路径
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("PgAgentStateStore：有主体时 DAO 收到的前两个参数恒为 (tenantId, membershipId)")
    void pgAgentStateStoreBindsTenantAndMemberOnEveryDaoCall() {
        AgentStateMapper mapper = mock(AgentStateMapper.class);
        PgAgentStateStore store = new PgAgentStateStore(mapper);
        asTenant(TENANT_A);

        when(mapper.selectPayload(TENANT_A, MEMBER_A, SESSION_ID, STATE_KEY)).thenReturn(null);

        // 读路径：租户 A 的主体只能读到 T1 的 payload
        assertThat(store.get(USER_ID, SESSION_ID, STATE_KEY, PayloadState.class)).isEqualTo(Optional.empty());
        assertThat(store.exists(USER_ID, SESSION_ID)).isFalse();
        assertThat(store.listSessionIds(USER_ID)).isEmpty();

        store.delete(USER_ID, SESSION_ID);
        store.delete(USER_ID, SESSION_ID, STATE_KEY);
        store.save(USER_ID, SESSION_ID, STATE_KEY, new PayloadState("v"));

        // 逐个核对参数位：tenant/member 是键的前两维，sessionId/key 紧随其后
        verify(mapper).selectPayload(TENANT_A, MEMBER_A, SESSION_ID, STATE_KEY);
        verify(mapper).exists(TENANT_A, MEMBER_A, SESSION_ID);
        verify(mapper).selectSessionIds(TENANT_A, MEMBER_A);
        verify(mapper).deleteBySession(TENANT_A, MEMBER_A, SESSION_ID);
        verify(mapper).deleteByKey(TENANT_A, MEMBER_A, SESSION_ID, STATE_KEY);
        // 第三参数是展示引用 userId，不参与键；换租户主体即换键
        verify(mapper).upsert(eq(TENANT_A), eq(MEMBER_A), eq(USER_ID), eq(SESSION_ID), eq(STATE_KEY), any());

        // 换成租户 B 的主体：同样的 session/key，键完全不同
        asTenant(TENANT_B);
        store.exists(USER_ID, SESSION_ID);
        verify(mapper).exists(TENANT_B, MEMBER_B, SESSION_ID);
    }

    // ------------------------------------------------------------ memory 三个 mapper SQL 文本

    @Test
    @DisplayName("AgentMemoryMapper：supersede/retract/retractAll 恒带租户/成员条件")
    void memoryMapperSqlAlwaysTenantScoped() throws IOException {
        List<String> statements = sqlStatements(agentMainSource("dao/mapper/AgentMemoryMapper.java"));
        assertThat(statements).hasSize(3);
        for (String statement : statements) {
            assertThat(statement)
                    .contains(TENANT_MEMBER_EQ)
                    .contains("UPDATE t_agent_memory")
                    .contains("invalid_at IS NULL");
        }
        // supersede/retract 按条目 id 定位，retractAll 面向全量：三条各自的角色保持不变
        assertThat(statementContaining(statements, "superseded_by")).contains("SET invalid_at = CURRENT_TIMESTAMP, superseded_by = #{newId}");
        assertThat(statements.stream().filter(s -> s.contains("superseded_by")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("AgentMemoryControlMapper：四条语句全部按 (tenant, member) 定位")
    void memoryControlMapperSqlAlwaysTenantScoped() throws IOException {
        List<String> statements = sqlStatements(agentMainSource("dao/mapper/AgentMemoryControlMapper.java"));
        assertThat(statements).hasSize(4);

        // 懒创建的冲突目标就是 (tenant, member) 控制行主键：同 userId 跨租户是两把锁、两个版本号
        String ensure = statementContaining(statements, "INSERT INTO t_agent_memory_control");
        assertThat(ensure).contains("ON CONFLICT (tenant_id, member_id) DO NOTHING")
                .contains("INSERT INTO t_agent_memory_control (tenant_id, member_id, user_id, revision");

        // 行锁与版本号是提交期的串行点，定位谓词必须钉死在租户/成员上；控制行查询不含 user_id 条件
        assertThat(statementContaining(statements, "FOR UPDATE"))
                .contains("WHERE tenant_id = #{tenantId} AND member_id = #{memberId}")
                .doesNotContain("user_id =");
        assertThat(statementContaining(statements, "revision = revision + 1"))
                .contains(TENANT_MEMBER_EQ);
        List<String> controlReads = statements.stream()
                .filter(s -> s.contains("SELECT user_id, tenant_id, member_id"))
                .toList();
        assertThat(controlReads).hasSize(2);
        for (String read : controlReads) {
            assertThat(read).contains(TENANT_MEMBER_EQ).doesNotContain("user_id =");
        }
    }

    @Test
    @DisplayName("AgentMemoryExtractionMapper：水位/覆盖/僵尸回收/尝试次数四条恒带租户/成员，settle 保持仅按行 id")
    void memoryExtractionMapperSqlAlwaysTenantScoped() throws IOException {
        List<String> statements = sqlStatements(agentMainSource("dao/mapper/AgentMemoryExtractionMapper.java"));
        assertThat(statements).hasSize(5);

        // 水位与在飞按用户不按会话：租户/成员是水位互不可见的前提
        assertThat(statementContaining(statements, "SELECT max(to_message_id)")).contains(TENANT_MEMBER_EQ);
        assertThat(statementContaining(statements, "SELECT status")).contains(TENANT_MEMBER_EQ);
        assertThat(statementContaining(statements, "make_interval")).contains(TENANT_MEMBER_EQ);
        assertThat(statementContaining(statements, "SELECT coalesce(max(attempt_count)")).contains(TENANT_MEMBER_EQ);

        // settle 是唯一的例外：只结算本主体刚 claim 到的那一行（行 id 为全局雪花），
        // 若把 WHERE 放宽到别的东西（例如只按 user）就会跨租户结掉在飞 claim——钉死它的形状
        String settle = statementContaining(statements, "SET status = #{status}");
        assertThat(settle)
                .contains("WHERE id = #{id} AND status = 'PROCESSING'")
                .doesNotContain("user_id", "tenant_id");
    }

    // ------------------------------------------------------------ AgentMemoryRepository 行为

    @Test
    @DisplayName("AgentMemoryRepository：无主体全部公共入口拒绝且零 mapper 交互")
    void memoryRepositoryRejectsEveryEntryWithoutPrincipal() {
        AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);
        AgentMemoryExtractionMapper extractionMapper = mock(AgentMemoryExtractionMapper.class);
        AgentMemoryControlMapper controlMapper = mock(AgentMemoryControlMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentMemoryRepository repository = new AgentMemoryRepository(
                memoryMapper, extractionMapper, controlMapper, messageMapper, new AgentMemoryProperties());
        AgentMemoryCommit commit = new AgentMemoryCommit(USER_ID, "e-1", 1, 7L, "1900000000000000001",
                AgentMemorySourceType.FLUSH,
                List.of(AgentMemoryDecision.add("用户住在南京")), List.of());

        assertThatThrownBy(() -> repository.ensureControl(USER_ID)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> repository.listActiveItems(USER_ID)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> repository.currentWatermark(USER_ID)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> repository.settledStatusCovering(USER_ID, "m-1")).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> repository.loadPending(USER_ID, null, null)).isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> repository.claim(USER_ID, "c-1", "m-1", "m-2", AgentMemoryTriggerType.FLUSH))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> repository.commit(commit)).isInstanceOf(ClientException.class);

        // 主体缺失在任何 DAO 访问之前被拒绝：不存在匿名命名空间
        verifyNoInteractions(memoryMapper, extractionMapper, controlMapper, messageMapper);
    }

    @Test
    @DisplayName("AgentMemoryRepository：commit 先按 (tenant, member) 取控制行锁，写入行带归属")
    void memoryRepositoryCommitLocksControlRowByTenantAndMember() {
        AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);
        AgentMemoryExtractionMapper extractionMapper = mock(AgentMemoryExtractionMapper.class);
        AgentMemoryControlMapper controlMapper = mock(AgentMemoryControlMapper.class);
        AgentMemoryRepository repository = new AgentMemoryRepository(
                memoryMapper, extractionMapper, controlMapper, mock(AgentMessageMapper.class),
                new AgentMemoryProperties());
        asTenant(TENANT_A);

        AgentMemoryControlDO control = new AgentMemoryControlDO();
        control.setUserId(USER_ID);
        control.setRevision(7L);
        when(controlMapper.selectForUpdate(TENANT_A, MEMBER_A)).thenReturn(control);
        when(extractionMapper.selectWatermark(TENANT_A, MEMBER_A, USER_ID)).thenReturn("1900000000000000001");
        when(extractionMapper.settle(eq("e-1"), any(), anyInt(), anyInt())).thenReturn(1);
        // 无决策冲突的空记忆集：存量读空即可走完 ADD 提交
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        repository.commit(new AgentMemoryCommit(USER_ID, "e-1", 1, 7L, "1900000000000000001",
                AgentMemorySourceType.FLUSH,
                List.of(AgentMemoryDecision.add("用户住在南京")), List.of()));

        // 提交的第一句就是 (tenant, member) 行锁：T2 的同号用户此刻可以并行提交，互不排队
        verify(controlMapper).selectForUpdate(TENANT_A, MEMBER_A);
        verify(controlMapper).bumpRevision(TENANT_A, MEMBER_A);
    }

    // ------------------------------------------------------------ 工具

    private static void asTenant(String tenantId) {
        PrincipalContext.set(new ExecutionPrincipal(
                tenantId, USER_ID, "platform:" + tenantId + ":" + USER_ID, 7, 3,
                Set.of(), "jti-" + tenantId, "platform", 1_700_000_000L, 1_700_000_060L));
    }

    /**Jackson 可序列化的最小 State 实现，只为走通 payload 编解码。 */
    private record PayloadState(String value) implements State {
    }

    /** 抽取 mapper 源文件里的全部 SQL 文本块（text block 内容）。 */
    private static List<String> sqlStatements(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = TEXT_BLOCK.matcher(source);
        while (m.find()) {
            out.add(m.group(1).strip());
        }
        return out;
    }

    private static String statementContaining(List<String> statements, String marker) {
        return statements.stream()
                .filter(s -> s.toUpperCase(Locale.ROOT).contains(marker.toUpperCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到包含 " + marker + " 的语句"));
    }

    /** agent 模块主源码：services/ai/agent/src/main/java/com/nageoffer/ai/ragent/&lt;subPath&gt;。 */
    private static String agentMainSource(String subPath) throws IOException {
        String cleaned = subPath.replace('\\', '/');
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("services").resolve("ai").resolve("agent")
                    .resolve("src").resolve("main").resolve("java")
                    .resolve("com").resolve("nageoffer").resolve("ai").resolve("ragent")
                    .resolve("agent")
                    .resolve(cleaned.replace('/', File.separatorChar));
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate agent main source: " + subPath);
    }
}
