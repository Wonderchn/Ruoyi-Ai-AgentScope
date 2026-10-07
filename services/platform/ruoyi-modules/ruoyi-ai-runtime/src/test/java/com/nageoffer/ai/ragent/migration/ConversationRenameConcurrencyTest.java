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

package com.nageoffer.ai.ragent.migration;

import com.nageoffer.ai.ragent.authorization.AiResourceWriteService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D10 / C9.4：会话改名乐观锁的<b>真库并发负例</b>，落成仓库内可重复运行的测试。
 *
 * <p>为什么必须持久化这一条：C9.4 把"两个带 {@code expectedVersion=N} 的改名并发，恰好一个成功、
 * 另一个 409，且最终 {@code title}/{@code version} 与成功者一致"写成 D10 完成的<b>判据</b>。
 * 一次性探针（workspace 里的 Java 小程序）证明过它，但探针不会随代码演进重跑：一旦有人把
 * {@code version = version + 1} 从 UPDATE 里挪走、或把条件改成"先读后写"，探针不会响，只有这条
 * 测试会响。
 *
 * <p>三条纪律（与 {@code ConversationWritePostgresTest} 同源，但这里专门压并发）：
 * <ol>
 *   <li><b>SQL 文本只有一份</b>：直接反射读 {@code AiResourceWriteService.SQL_RENAME_CONVERSATION}
 *       （包级可见的常量），不在这里抄第二份——抄一份就会出现"测试通过、生产 SQL 不同"的分叉。
 *       反射是刻意的：本测试位于 {@code migration} 包（T1 租约内），不越界改 {@code authorization} 包；</li>
 *   <li><b>表形状来自冻结迁移</b>：{@code ai_conversation} 的 DDL 从
 *       {@code docs/script/sql/postgres/V*.sql} 按版本顺序抽出后执行，不手写 CREATE
 *       （手写一份立刻产生"测试通过、生产列不同"的分叉）；</li>
 *   <li><b>真并发</b>：两条独立连接 + {@code CyclicBarrier} 同时放行，不是"先做一次再做一次"。</li>
 * </ol>
 *
 * <p>需要 {@code -Dragent.conversation.test.jdbc-url=...}；未提供时<b>显式跳过并给出原因</b>。
 * 口令用可选参数 {@code -Dragent.conversation.test.jdbc-password=...}（默认空串），
 * 因此既能指向 trust 的本地库，也能指向需要口令的隔离库。
 */
@Tag("dev")
@EnabledIfSystemProperty(named = ConversationRenameConcurrencyTest.URL_PROPERTY, matches = ".+",
        disabledReason = "需要隔离 PostgreSQL：-D" + ConversationRenameConcurrencyTest.URL_PROPERTY
                + "=jdbc:postgresql://host:port/db")
class ConversationRenameConcurrencyTest {

    static final String URL_PROPERTY = "ragent.conversation.test.jdbc-url";
    static final String PASSWORD_PROPERTY = "ragent.conversation.test.jdbc-password";

    private static final String TENANT = "T-D10";
    private static final String MEMBER = "platform:T-D10:11";
    private static final String CONVERSATION = "conv-d10";
    private static final String ROW_ID = "wp028-d10";

    /** 并发轮数：每一轮都必须"恰好一个赢家 + 版本恰好 +1"，丢更新会在某一轮暴露。 */
    private static final int ROUNDS = 20;

    private static String url;
    private static String user;
    private static String password;
    private static String renameSql;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getProperty(URL_PROPERTY);
        assertThat(url).as("缺少 -D" + URL_PROPERTY).isNotBlank();
        user = System.getProperty("ragent.conversation.test.jdbc-user", "postgres");
        password = System.getProperty(PASSWORD_PROPERTY, "");

        renameSql = productionRenameSql();
        // 生产 SQL 必须仍然满足 C9.1：标题与版本在同一条语句内自增
        assertThat(renameSql)
                .as("C9.1：title 与 version 必须写在同一条 UPDATE 语句里")
                .contains("title = :title")
                .contains("version = version + 1")
                .contains(":expectedVersion");

        try (Connection c = open()) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS platform");
                st.execute("DROP TABLE IF EXISTS platform.ai_conversation CASCADE");
                for (String ddl : frozenConversationDdl()) {
                    st.execute(ddl);
                }
            }
            // 形状锚点：列必须来自冻结迁移；抽空了会让下面每条判据失去意义
            List<String> columns = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns"
                         + " WHERE table_schema='platform' AND table_name='ai_conversation'")) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
            assertThat(columns)
                    .as("从冻结迁移抽出的 ai_conversation 形状")
                    .contains("id", "conversation_id", "user_id", "title", "tenant_id", "member_id", "deleted")
                    .as("V13 的并发列必须也在形状里（D10 依赖它）")
                    .contains("version");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (url == null) {
            return;
        }
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM platform.ai_conversation WHERE id LIKE 'wp028-d10%'");
        }
    }

    // ------------------------------------------------------------------ 判据

    @Test
    @DisplayName("并发负例：两个 expectedVersion=N 的改名恰好一个成功，最终 title/version 与成功者一致")
    void concurrentRenamesHaveExactlyOneWinner() throws Exception {
        seed(0);
        long expected = currentVersion();

        RenameOutcome outcome = renameConcurrently("winner-A", "winner-B", expected);

        assertThat(outcome.affected())
                .as("恰好一个 UPDATE 命中 1 行，另一个 0 行（服务层把 0 行翻译成 409）")
                .containsExactlyInAnyOrder(1, 0);
        assertThat(outcome.errors()).as("两个线程都不应抛异常").isNull();

        assertThat(title()).as("最终标题必须与成功者一致").isEqualTo(outcome.winnerTitle());
        assertThat(currentVersion()).as("版本恰好前进 1").isEqualTo(expected + 1);
        assertThat(title()).as("没有出现'标题写了但版本没涨'或反之").isIn("winner-A", "winner-B");
    }

    @Test
    @DisplayName("陈旧 expectedVersion 不写入（0 行），标题与版本都保持成功者的值")
    void staleExpectedVersionWritesNothing() throws Exception {
        seed(0);
        long expected = currentVersion();

        assertThat(rename("first-write", expected)).as("匹配的 expectedVersion 命中 1 行").isEqualTo(1);
        assertThat(currentVersion()).isEqualTo(expected + 1);

        assertThat(rename("stale-write", expected))
                .as("同一个 expectedVersion 再用一次必须 0 行：这就是 409 的数据面")
                .isZero();
        assertThat(title()).as("0 行意味着不写入").isEqualTo("first-write");
        assertThat(currentVersion()).as("冲突不改版本").isEqualTo(expected + 1);
    }

    @Test
    @DisplayName("兼容模式：expectedVersion 缺失时仍显式更新，且版本同样前进（C9.5）")
    void compatibilityModeStillAdvancesVersion() throws Exception {
        seed(0);
        long expected = currentVersion();

        assertThat(rename("compat-write", null))
                .as("缺 expectedVersion 是显式兼容模式：无条件更新，但仍 version = version + 1")
                .isEqualTo(1);
        assertThat(title()).isEqualTo("compat-write");
        assertThat(currentVersion())
                .as("客户端总能自证版本前进（C9.5b）")
                .isEqualTo(expected + 1);
    }

    @Test
    @DisplayName("多轮并发：每轮恰好一个赢家、版本恰好 +1，无丢更新")
    void repeatedRoundsNeverLoseAnUpdate() throws Exception {
        seed(0);
        long version = currentVersion();

        for (int round = 1; round <= ROUNDS; round++) {
            RenameOutcome outcome = renameConcurrently("round-" + round + "-A", "round-" + round + "-B", version);
            assertThat(outcome.affected())
                    .as("第 %s 轮：恰好一个赢家", round)
                    .containsExactlyInAnyOrder(1, 0);
            assertThat(currentVersion()).as("第 %s 轮：版本恰好 +1", round).isEqualTo(version + 1);
            assertThat(title()).as("第 %s 轮：标题与赢家一致", round).isEqualTo(outcome.winnerTitle());
            version = version + 1;
        }
        assertThat(version).isEqualTo(ROUNDS);
    }

    // ------------------------------------------------------------------ helpers

    /** 一次并发改名的结果：两条连接各影响几行、谁赢、两个线程是否抛异常。 */
    private record RenameOutcome(List<Integer> affected, String winnerTitle, String errors) { }

    private static RenameOutcome renameConcurrently(String titleA, String titleB, Long expectedVersion)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        int[] affected = new int[2];
        String[] errors = new String[2];
        String[] titles = {titleA, titleB};
        Thread[] threads = new Thread[2];

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try (Connection c = open()) {
                    c.setAutoCommit(false);
                    barrier.await();
                    try (PreparedStatement ps = prepare(c, titles[idx], expectedVersion)) {
                        affected[idx] = ps.executeUpdate();
                    }
                    c.commit();
                } catch (Exception e) {
                    errors[idx] = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        String joinedErrors = (errors[0] == null && errors[1] == null)
                ? null
                : "A=" + errors[0] + ", B=" + errors[1];
        String winner = affected[0] == 1 ? titleA : titleB;
        return new RenameOutcome(List.of(affected[0], affected[1]), winner, joinedErrors);
    }

    /** 单条（非并发）改名，返回影响行数；expectedVersion 为 null 即兼容模式。 */
    private static int rename(String title, Long expectedVersion) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = prepare(c, title, expectedVersion)) {
            return ps.executeUpdate();
        }
    }

    /**
     * 用生产 SQL 文本准备语句：把 {@code :name} 占位符按出现顺序换成 {@code ?}，
     * 再按同一顺序绑定。这样生产 SQL 改动会自动反映到本测试，不需要同步抄写。
     */
    private static PreparedStatement prepare(Connection c, String title, Long expectedVersion) throws SQLException {
        Matcher matcher = NAMED_PARAM.matcher(renameSql);
        List<String> order = new ArrayList<>();
        while (matcher.find()) {
            order.add(matcher.group(1));
        }
        String positional = matcher.reset().replaceAll("?");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", title);
        values.put("tenant", TENANT);
        values.put("member", MEMBER);
        values.put("conversation", CONVERSATION);
        values.put("expectedVersion", expectedVersion);

        PreparedStatement ps = c.prepareStatement(positional);
        for (int i = 0; i < order.size(); i++) {
            Object v = values.get(order.get(i));
            if (v == null) {
                ps.setNull(i + 1, java.sql.Types.BIGINT);
            } else if (v instanceof Long l) {
                ps.setLong(i + 1, l);
            } else {
                ps.setString(i + 1, String.valueOf(v));
            }
        }
        return ps;
    }

    private static final Pattern NAMED_PARAM = Pattern.compile(":([a-zA-Z][a-zA-Z0-9_]*)");

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static void seed(long version) throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM platform.ai_conversation WHERE id LIKE 'wp028-d10%'");
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO platform.ai_conversation"
                     + " (id, conversation_id, user_id, title, last_time, create_time, update_time,"
                     + "  deleted, tenant_id, member_id, version)"
                     + " VALUES (?,?,?,?, now(), now(), now(), 0, ?, ?, ?)")) {
            ps.setString(1, ROW_ID);
            ps.setString(2, CONVERSATION);
            ps.setString(3, "11");
            ps.setString(4, "seed-title");
            ps.setString(5, TENANT);
            ps.setString(6, MEMBER);
            ps.setLong(7, version);
            ps.executeUpdate();
        }
    }

    private static long currentVersion() throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT version FROM platform.ai_conversation WHERE id = ?")) {
            ps.setString(1, ROW_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("fixture 行必须存在").isTrue();
                return rs.getLong(1);
            }
        }
    }

    private static String title() throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT title FROM platform.ai_conversation WHERE id = ?")) {
            ps.setString(1, ROW_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("fixture 行必须存在").isTrue();
                return rs.getString(1);
            }
        }
    }

    /** 反射读生产 SQL 常量：本测试在 migration 包内，不越界改 authorization 包。 */
    private static String productionRenameSql() throws ReflectiveOperationException {
        Field field = AiResourceWriteService.class.getDeclaredField("SQL_RENAME_CONVERSATION");
        field.setAccessible(true);
        String sql = (String) field.get(null);
        assertThat(sql).as("生产 SQL 常量必须可读且非空").isNotBlank();
        return sql;
    }

    /**
     * 从冻结迁移里抽出 {@code platform.ai_conversation} 的 DDL（建表 + 后续 ALTER），
     * 与仓库里测试用的 {@code FrozenTableDdl.forTable} 同一规则。本文件刻意自带的这一份是
     * 小副本：真正的共享需要 test-jar（改构建配置，属需单独决定的范围），
     * 而"手写一份 CREATE"会直接产生"测试通过、生产列不同"的分叉。
     */
    private static List<String> frozenConversationDdl() throws IOException {
        Path sqlDir = locate(Path.of("services", "platform", "docs", "script", "sql", "postgres"));
        List<Path> files;
        try (Stream<Path> stream = Files.list(sqlDir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted((a, b) -> Integer.compare(versionOf(a), versionOf(b)))
                    .toList();
        }
        Pattern create = Pattern.compile(
                "CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+platform\\.ai_conversation\\s*\\([^;]*?\\)\\s*;",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern alter = Pattern.compile(
                "ALTER\\s+TABLE\\s+platform\\.ai_conversation\\s+[^;]*;",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        List<String> out = new ArrayList<>();
        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            Matcher c = create.matcher(sql);
            if (c.find()) {
                out.add(c.group());
            }
            Matcher a = alter.matcher(sql);
            while (a.find()) {
                out.add(a.group());
            }
        }
        assertThat(out).as("冻结迁移里必须能抽出 ai_conversation 的 DDL").isNotEmpty();
        return out;
    }

    private static Path locate(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative);
    }

    private static int versionOf(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
