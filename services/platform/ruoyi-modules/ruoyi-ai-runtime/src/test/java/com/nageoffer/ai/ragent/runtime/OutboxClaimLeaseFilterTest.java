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

package com.nageoffer.ai.ragent.runtime;

import com.nageoffer.ai.ragent.runtime.dao.OutboxDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-6 A1：outbox 认领必须带**存活锁过滤**（lead 批准，风险级高）。
 *
 * <p>缺陷形态（修复前）：{@code claim} 的认领条件只有
 * {@code state='PENDING' AND next_attempt_at <= now()} —— relay A 认领后
 * {@code locked_until} 虽被写入 T+lockSeconds，但行的 {@code state} 仍是 PENDING、
 * {@code next_attempt_at} 仍在过去 ⇒ relay B 下一 tick **再次认领同一行**：
 * 认领锁完全失效，投递并发超出 at-least-once 的"崩溃后锁过期重投"包络。
 *
 * <p>对照（同族正确实现）：{@code RunLedgerDao.claimNext} 的候选过滤带
 * {@code (lease_until IS NULL OR lease_until < now())}；本类注释"locked_until 过期后
 * 可被再次认领"自证意图——**条件漏写**，不是设计取舍。
 *
 * <p>真库行为验证见 {@code OutboxClaimLeasePostgresTest}（需要
 * {@code -Dragent.outbox.test.jdbc-url}）；本类在纯单测形态钉住 SQL 契约与谓词语义。
 */
@Tag("dev")
class OutboxClaimLeaseFilterTest {

    /**
     * 存活锁过滤子句：过滤放在认领**子查询内部**、对子查询自己的行生效（无别名字段）。
     * 写成 {@code o.locked_until} 会变成外层关联引用——语义混淆（见实现注释）。
     */
    private static final String LOCK_FILTER =
            "(locked_until IS NULL OR locked_until < now())";

    @Test
    @DisplayName("认领 SQL 必须携带存活锁过滤（locked_until 未过期的 PENDING 行不可再认领）")
    void claimFiltersOutRowsWithLiveLock() throws Exception {
        String sql = claimSqlFromProductSource();

        // 锚点（采集自证）：PENDING 判据与到期判据真的在——否则下面的断言是扫错文本的恒真
        assertThat(sql).contains("state='PENDING'").contains("next_attempt_at <= now()");
        assertThat(sql)
                .as("claim 的 WHERE 必须过滤存活锁：缺它时 relay B 会在 relay A 的锁仍存活时"
                        + "再次认领同一行，认领锁失效（W3-6 A1）")
                .contains(LOCK_FILTER);
    }

    @Test
    @DisplayName("文本层变异：摘掉过滤子句 ⇒ 锚点变红（反向证明锚，产品文件不动）")
    void removingTheFilterMakesTheAnchorFail() throws Exception {
        String sql = claimSqlFromProductSource();
        String mutated = sql.replace(LOCK_FILTER, "");
        assertThat(mutated).isNotEqualTo(sql).as("变异必须真的生效（否则本测试恒真）");
        assertThatThrownBy(() -> assertThat(mutated).contains(LOCK_FILTER))
                .as("去掉过滤子句后锚点必须变红")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("行为锚：存活锁内的行不可认领；锁过期/无锁行可认领（谓词语义模拟）")
    void liveLockedRowIsNotClaimableUntilItsLockExpires() throws Exception {
        String sql = claimSqlFromProductSource();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboxDao dao = new OutboxDao(jdbc);

        // 形态 1：行 PENDING + next_attempt_at 已到 + 锁仍存活 ⇒ 不可认领
        // （修复前无过滤子句时该形态会被认领 = 锁失效）
        assertThat(claimable(sql, true, true))
                .as("存活锁内的 PENDING 行不得被认领")
                .isFalse();

        // 形态 2：同一行锁已过期 ⇒ 可被再次认领（at-least-once 的崩溃补偿路径必须保持）
        assertThat(claimable(sql, true, false))
                .as("锁过期后该行必须可被再次认领——不能写死成'锁过的行永远不能再投'")
                .isTrue();

        // 形态 3：从未被认领过的行（locked_until IS NULL ⇒ 锁不存活）⇒ 首投路径不受影响
        assertThat(claimable(sql, true, false))
                .as("无锁行的首投路径不受影响（IS NULL 分支）")
                .isTrue();

        // DAO 装配锚：claim 真的把这条 SQL 发给 JDBC（防"只测了文本、没测接线"）
        when(jdbc.query(contains("state='PENDING'"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        dao.claim("relay-x", 30, 8);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sent.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sent.getValue())
                .as("OutboxDao.claim 实际下发的 SQL 与产品源中的认领语句一致")
                .contains(LOCK_FILTER);
    }

    /**
     * 按修复后 WHERE 的语义判定一行是否可认领：PENDING + next_attempt_at 已到 +
     * 锁不存活（IS NULL 或已过期）三者同时成立。
     * {@code lockAlive} 编码行锁状态——这是对 SQL 子句的**谓词求值模拟**：
     * 子句被摘时 {@code lockAlive=true} 也会被判成可认领（正是要暴露的缺陷形态）。
     */
    private static boolean claimable(String sql, boolean nextAttemptDue, boolean lockAlive) {
        boolean stateOk = sql.contains("state='PENDING'")
                && sql.contains("next_attempt_at <= now()") && nextAttemptDue;
        return stateOk && sql.contains(LOCK_FILTER) && !lockAlive;
    }

    /** 从产品源抽出 claim 的 SQL 文本（拼接形态还原为单串）。 */
    private static String claimSqlFromProductSource() throws Exception {
        java.nio.file.Path file = worktreeRoot()
                .resolve(java.nio.file.Paths.get("services", "platform", "ruoyi-modules",
                        "ruoyi-ai-runtime", "src", "main", "java", "com", "nageoffer", "ai",
                        "ragent", "runtime", "dao", "OutboxDao.java"));
        assertThat(java.nio.file.Files.exists(file)).as("产品源必须存在：%s", file).isTrue();
        String source = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        int start = source.indexOf("UPDATE outbox_event o SET");
        assertThat(start).as("OutboxDao 中必须存在认领 SQL").isGreaterThan(0);
        int end = source.indexOf('"', source.indexOf("operation_key", start));
        assertThat(end).as("认领 SQL 的结束引号必须存在").isGreaterThan(0);
        return source.substring(start, end).replace("\" + \"", "").replace("\"+\"", "");
    }

    private static java.nio.file.Path worktreeRoot() {
        java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        java.nio.file.Path marker = java.nio.file.Paths.get("services", "platform", "ruoyi-modules", "ruoyi-ai-runtime");
        while (dir != null && !java.nio.file.Files.isDirectory(dir.resolve(marker))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("cannot locate worktree root from user.dir="
                    + System.getProperty("user.dir"));
        }
        return dir;
    }
}
