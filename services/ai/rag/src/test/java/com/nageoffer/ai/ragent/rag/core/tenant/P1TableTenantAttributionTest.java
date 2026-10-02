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

package com.nageoffer.ai.ragent.rag.core.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 32 表租户归属的 DDL 层验收（2026-10-02 修正后的判据，05 §4.4）。
 *
 * <p><b>判据是什么。</b>不是"32 条主键一律含 tenant_id"。允许保留全局唯一代理主键；
 * 隔离由 tenant 列 + tenant WHERE + 同域 {@code (tenant_id, …)} 唯一键/复合 FK 保证。
 * 只有键在租户内可重复的表（t_agent_state、t_agent_memory_control、
 * t_agent_memory_extraction 的 claim）才必须把租户纳入键。
 * 逐表期望值在单一代码源 {@code p1/table-attribution.json}（41 表 = 32 基线 + 9 新结构），
 * 合成库的活 schema 验收（information_schema）与本校验共用该文件。
 *
 * <p><b>本测试查 DDL 文本，不查活库。</b>活库验收由合成环境 runner 在真实 PG 上执行。
 * DDL 层验收的价值：迁移文本在进 VM 之前就与逐表账一致，且 V1 的原字节被哈希钉死——
 * 追加 V3–V5 从不修改历史迁移。
 *
 * <p><b>基线诊断不再冒充验收。</b>旧版本曾断言"缺口仍存在（32）"，那是对 V1 文本的
 * 恒真检查——V1 按定义永不修改，该断言无法反映迁移是否落地。现在：
 * V1 的不变性由 SHA-256 钉死（第 1 条），迁移是否补齐归属由拼接 V1–V5 后的
 * 实际形状对照逐表账判定（第 2 条起）。
 */
class P1TableTenantAttributionTest {

    private static final Path MIGRATIONS = Path.of("resources", "database", "postgres", "migrations");

    /** 逐表账：41 表的唯一代码源，与活 schema 验收共用。 */
    private static final Path LEDGER = Path.of("src", "test", "resources", "p1", "table-attribution.json");

    /** V1 原字节哈希：历史迁移必须保持逐字节不变（追加，不修改）。 */
    private static final String V1_SHA256 = "a0cd8a9e51c703cbd19b0634c59ad9d4a13584fefae219e9d66e837f8a4b71a4";

    private static final String V1_FILE = "V1__ai_baseline.sql";
    private static final List<String> FULL_CHAIN = List.of(
            "V1__ai_baseline.sql",
            "V2__ai_static_configuration.sql",
            "V3__tenant_acl_expand.sql",
            "V4__tenant_acl_constraints.sql",
            "V5__tenant_state_and_run_refs.sql");

    // ---------- 基线诊断（V1 原字节不变） ----------

    @Test
    @DisplayName("V1 基线保持原字节：追加迁移从不修改历史文件")
    void v1BaselineByteIdentity() throws IOException {
        Path v1 = locate(MIGRATIONS.resolve(V1_FILE));
        String sha = sha256(v1);
        assertThat(sha)
                .as("V1__ai_baseline.sql 被修改了。历史迁移必须保持逐字节不变（00 §4）；"
                        + "如确属基线升级，须走单独授权流程并同步更新本哈希与 02 代码索引")
                .isEqualTo(V1_SHA256);

        // 基线诊断事实（历史记账，恒真但由上面的哈希钉住才有意义）：
        // 基线的 32 张表在 DDL 层面没有租户概念，租户归属由 V3–V5 追加补齐。
        Map<String, String> blocks = createTableBlocks(read(v1));
        assertThat(blocks).hasSize(32);
        assertThat(blocks.values().stream().filter(b -> Pattern.compile(
                        "\\btenant_id\\b", Pattern.CASE_INSENSITIVE).matcher(b).find()).count())
                .as("V1 基线含 tenant_id 的表数（基线事实：0）")
                .isZero();
    }

    // ---------- DDL 层验收：拼接全链后对照逐表账 ----------

    @Test
    @DisplayName("拼接 V1–V5 后，每张表的实际 DDL 形状与逐表账一致")
    void combinedMigrationsMatchAttributionLedger() throws IOException {
        Map<String, TableModel> model = new LinkedHashMap<>();
        for (String file : FULL_CHAIN) {
            applyMigration(model, read(locate(MIGRATIONS.resolve(file))), file);
        }
        Map<String, LedgerEntry> ledger = readLedger();

        assertThat(model.keySet())
                .as("迁移链建模出的表集合与逐表账应完全一致")
                .containsExactlyInAnyOrderElementsOf(ledger.keySet());
        assertThat(model).hasSize(41);

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, LedgerEntry> e : ledger.entrySet()) {
            String table = e.getKey();
            LedgerEntry expected = e.getValue();
            TableModel actual = model.get(table);
            verify(table, expected, actual, failures);
        }
        assertThat(failures)
                .as("逐表账比对失败明细")
                .isEmpty();
    }

    @Test
    @DisplayName("模板域转移是显式的：V2 种子行进入 __public_template__，不以 NULL 冒充共享")
    void templateDomainTransferIsExplicit() throws IOException {
        String v3 = read(locate(MIGRATIONS.resolve("V3__tenant_acl_expand.sql")));
        for (String table : List.of("t_agent_profile", "t_agent_prompt")) {
            Pattern p = Pattern.compile(
                    "UPDATE\\s+" + table + "\\s+SET\\s+tenant_id\\s*=\\s*'__public_template__'",
                    Pattern.CASE_INSENSITIVE);
            assertThat(p.matcher(v3).find())
                    .as("%s 的 V2 静态种子行必须显式转移到模板域（__public_template__）", table)
                    .isTrue();
        }
        // 保留模板租户不得含冒号（canonical membership 分隔符），且长度在 1..64 内。
        String reserved = "__public_template__";
        assertThat(reserved).doesNotContain(":");
        assertThat(reserved.length()).isBetween(1, 64);
    }

    // ---------- 校验逻辑 ----------

    private static void verify(String table, LedgerEntry expected, TableModel actual, List<String> failures) {
        switch (expected.attribution) {
            case "LEGACY_CLOSED" -> {
                if (actual.columns.containsKey("tenant_id")) {
                    failures.add(table + ": legacy 关闭表不应补租户列");
                }
            }
            case "TENANT_BUSINESS", "COMPOSITE_KEY", "TEMPLATE_DOMAIN", "REGISTRY" -> {
                if (!actual.notNull("tenant_id")) {
                    failures.add(table + ": tenant_id 应存在且 NOT NULL（实际 " + actual.columnState("tenant_id") + "）");
                }
                if (!actual.hasTenantCheck()) {
                    failures.add(table + ": 缺 tenant 非空 CHECK 约束");
                }
                if (expected.memberColumn && !actual.notNull("member_id")) {
                    failures.add(table + ": member_id 应存在且 NOT NULL（实际 " + actual.columnState("member_id") + "）");
                }
                if (expected.ownerColumn != null && !actual.notNull(expected.ownerColumn)) {
                    failures.add(table + ": " + expected.ownerColumn + " 应存在且 NOT NULL");
                }
            }
            default -> failures.add(table + ": 未知归属类 " + expected.attribution);
        }

        // 主键：允许保留全局代理键，也允许换复合键，以逐表账为准。
        List<String> actualPk = actual.primaryKeyColumns();
        if (expected.primaryKey != null && !expected.primaryKey.equals(actualPk)) {
            failures.add(table + ": 主键期望 " + expected.primaryKey + " 实际 " + actualPk);
        }

        // 租户作用域唯一键：账上每一条都应存在（列集精确相等；部分唯一索引计入）。
        for (List<String> want : expected.uniqueTenantScoped) {
            boolean found = actual.uniqueConstraints.values().stream().anyMatch(u -> u.equals(want))
                    || actual.indexes.values().stream()
                            .anyMatch(ix -> ix.unique && ix.columns.equals(want));
            if (!found) {
                failures.add(table + ": 缺唯一约束 " + want);
            }
        }

        // 复合 FK：引用目标与引用列精确匹配，且本侧列含 tenant_id。
        for (String fk : expected.foreignKeys) {
            ParsedFk want = ParsedFk.parse(fk);
            boolean found = actual.foreignKeys.values().stream().anyMatch(f ->
                    f.refTable.equals(want.refTable) && f.refColumns.equals(want.refColumns)
                            && f.columns.contains("tenant_id"));
            if (!found) {
                failures.add(table + ": 缺复合FK " + fk + "（实际 " + actual.foreignKeys.keySet() + "）");
            }
        }
    }

    // ---------- DDL 建模 ----------

    /** 一张表在迁移链应用后的最终形状（本验收关心的子集）。 */
    static final class TableModel {
        final String name;
        final Map<String, Boolean> columns = new LinkedHashMap<>(); // 列名 -> notNull
        String pkName;
        List<String> pkColumns = List.of();
        final Map<String, List<String>> uniqueConstraints = new LinkedHashMap<>();
        final Map<String, IndexModel> indexes = new LinkedHashMap<>();
        final Map<String, ParsedFk> foreignKeys = new LinkedHashMap<>();
        final Map<String, String> checks = new LinkedHashMap<>();

        TableModel(String name) {
            this.name = name;
        }

        boolean notNull(String col) {
            return Boolean.TRUE.equals(columns.get(col));
        }

        String columnState(String col) {
            if (!columns.containsKey(col)) {
                return "缺列";
            }
            return columns.get(col) ? "NOT NULL" : "nullable";
        }

        boolean hasTenantCheck() {
            return checks.values().stream().anyMatch(expr ->
                    Pattern.compile("\\btenant_id\\b").matcher(expr).find());
        }

        List<String> primaryKeyColumns() {
            return pkColumns;
        }
    }

    record IndexModel(String table, List<String> columns, boolean unique, String whereClause) {
    }

    record ParsedFk(List<String> columns, String refTable, List<String> refColumns) {
        static ParsedFk parse(String spec) {
            // 形如 "-> t_conversation (tenant_id, conversation_id, member_id)"
            Matcher m = Pattern.compile("->\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)").matcher(spec);
            if (!m.find()) {
                throw new IllegalArgumentException("无法解析FK账目: " + spec);
            }
            List<String> cols = new ArrayList<>();
            for (String c : m.group(2).split(",")) {
                cols.add(c.trim());
            }
            return new ParsedFk(List.of(), m.group(1), cols);
        }
    }

    private static void applyMigration(Map<String, TableModel> model, String sql, String file) {
        // CREATE TABLE
        Map<String, String> blocks = createTableBlocks(sql);
        for (Map.Entry<String, String> b : blocks.entrySet()) {
            String table = b.getKey();
            TableModel t = model.computeIfAbsent(table, TableModel::new);
            parseCreateBlock(t, b.getValue());
        }
        // ALTER / DROP / CREATE INDEX，逐条处理（按语句切分）。
        for (String stmt : splitStatements(sql.replaceAll("(?s)--.*?(\n|\r\n|$)", ""))) {
            String s = stmt.trim();
            if (s.isEmpty()) {
                continue;
            }
            String upper = s.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ALTER TABLE ")) {
                String table = identAfter(s, "ALTER TABLE ");
                TableModel t = model.computeIfAbsent(table, TableModel::new);
                if (upper.contains(" ADD COLUMN ")) {
                    Matcher m = Pattern.compile("ADD COLUMN\\s+([a-z_][a-z0-9_]*)\\s+([^;]*)",
                            Pattern.CASE_INSENSITIVE).matcher(s);
                    if (m.find()) {
                        t.columns.put(m.group(1).toLowerCase(Locale.ROOT),
                                m.group(2).toUpperCase(Locale.ROOT).contains("NOT NULL"));
                    }
                } else if (upper.contains(" ALTER COLUMN ")) {
                    Matcher m = Pattern.compile(
                            "ALTER COLUMN\\s+([a-z_][a-z0-9_]*)\\s+SET NOT NULL",
                            Pattern.CASE_INSENSITIVE).matcher(s);
                    if (m.find()) {
                        String col = m.group(1).toLowerCase(Locale.ROOT);
                        if (!t.columns.containsKey(col)) {
                            throw new IllegalStateException(file + ": ALTER COLUMN 未在 CREATE 中声明过: " + s);
                        }
                        t.columns.put(col, true);
                    }
                } else if (upper.contains(" DROP CONSTRAINT ")) {
                    String name = identAfter(s, "DROP CONSTRAINT ");
                    if (name.equals(t.pkName)) {
                        t.pkName = null;
                        t.pkColumns = List.of();
                    }
                    t.uniqueConstraints.remove(name);
                    t.checks.remove(name);
                    t.foreignKeys.remove(name);
                } else if (upper.contains(" ADD CONSTRAINT ")) {
                    Matcher m = Pattern.compile("ADD CONSTRAINT\\s+([a-z_][a-z0-9_]*)\\s+(.*)$",
                            Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
                    if (m.find()) {
                        String name = m.group(1);
                        String rest = m.group(2).trim();
                        String restUpper = rest.toUpperCase(Locale.ROOT);
                        if (restUpper.startsWith("UNIQUE")) {
                            t.uniqueConstraints.put(name, columnList(rest));
                        } else if (restUpper.startsWith("PRIMARY KEY")) {
                            t.pkName = name;
                            t.pkColumns = columnList(rest);
                        } else if (restUpper.startsWith("FOREIGN KEY")) {
                            Matcher f = Pattern.compile(
                                    "FOREIGN KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+([a-z_][a-z0-9_]*)\\s*\\(([^)]*)\\)",
                                    Pattern.CASE_INSENSITIVE).matcher(rest);
                            if (!f.find()) {
                                throw new IllegalStateException(file + ": 无法解析FK: " + s);
                            }
                            t.foreignKeys.put(name, new ParsedFk(
                                    columnListOf(f.group(1)), f.group(2), columnListOf(f.group(3))));
                        } else if (restUpper.startsWith("CHECK")) {
                            t.checks.put(name, rest);
                        } else {
                            throw new IllegalStateException(file + ": 未建模的约束形态: " + s);
                        }
                    }
                }
            } else if (upper.startsWith("DROP INDEX ")) {
                String name = identAfter(s, "DROP INDEX ");
                t:for (TableModel t : model.values()) {
                    if (t.indexes.remove(name) != null) {
                        break t;
                    }
                }
            } else if (upper.startsWith("CREATE UNIQUE INDEX ")) {
                Matcher m = Pattern.compile(
                        "CREATE UNIQUE INDEX\\s+([a-z_][a-z0-9_]*)\\s+ON\\s+([a-z_][a-z0-9_]*)\\s*\\(([^)]*)\\)(?:\\s*WHERE\\s+(.*))?$",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
                if (!m.find()) {
                    throw new IllegalStateException(file + ": 无法解析唯一索引: " + s);
                }
                model.computeIfAbsent(m.group(2), TableModel::new).indexes.put(m.group(1),
                        new IndexModel(m.group(2), columnListOf(m.group(3)), true,
                                m.group(4) == null ? "" : m.group(4).trim()));
            } else if (upper.startsWith("CREATE INDEX ")) {
                Matcher m = Pattern.compile(
                        "CREATE INDEX\\s+([a-z_][a-z0-9_]*)\\s+ON\\s+([a-z_][a-z0-9_]*)\\s*\\(([^)]*)\\)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
                if (m.find()) {
                    model.computeIfAbsent(m.group(2), TableModel::new).indexes.put(m.group(1),
                            new IndexModel(m.group(2), columnListOf(m.group(3)), false, ""));
                }
            }
            // CREATE TABLE / UPDATE / COMMENT / DO $$：CREATE 已按块处理，其余不建模。
        }
    }

    private static void parseCreateBlock(TableModel t, String block) {
        // 按顶层逗号切分列与约束（列内不含括号嵌套的假设在本 schema 成立；jsonb 默认值除外）。
        String body = block.substring(0, lastCloseParen(block));
        List<String> parts = splitTopLevel(body);
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String upper = p.toUpperCase(Locale.ROOT);
            if (upper.startsWith("PRIMARY KEY")) {
                t.pkName = t.name + "_pkey";
                t.pkColumns = columnList(p);
            } else if (upper.startsWith("CONSTRAINT ")) {
                Matcher m = Pattern.compile("CONSTRAINT\\s+([a-z_][a-z0-9_]*)\\s+(.*)$",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(p);
                if (m.find()) {
                    String name = m.group(1);
                    String rest = m.group(2).trim();
                    String restUpper = rest.toUpperCase(Locale.ROOT);
                    if (restUpper.startsWith("UNIQUE")) {
                        t.uniqueConstraints.put(name, columnList(rest));
                    } else if (restUpper.startsWith("CHECK")) {
                        t.checks.put(name, rest);
                    } else if (restUpper.startsWith("FOREIGN KEY")) {
                        Matcher f = Pattern.compile(
                                "FOREIGN KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+([a-z_][a-z0-9_]*)\\s*\\(([^)]*)\\)",
                                Pattern.CASE_INSENSITIVE).matcher(rest);
                        if (!f.find()) {
                            throw new IllegalStateException(t.name + ": 无法解析内联FK: " + p);
                        }
                        t.foreignKeys.put(name, new ParsedFk(
                                columnListOf(f.group(1)), f.group(2), columnListOf(f.group(3))));
                    }
                }
            } else if (upper.startsWith("CONSTRAINT") || upper.startsWith("FOREIGN")
                    || upper.startsWith("UNIQUE") || upper.startsWith("CHECK")) {
                // 无名约束：本 schema 未使用，遇到即报错以防漏建模。
                throw new IllegalStateException(t.name + ": 未建模的无名约束: " + p);
            } else {
                Matcher m = Pattern.compile("^([a-z_][a-z0-9_]*)\\s+", Pattern.CASE_INSENSITIVE)
                        .matcher(p);
                if (m.find()) {
                    String col = m.group(1).toLowerCase(Locale.ROOT);
                    boolean notNull = Pattern.compile("\\bNOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE)
                            .matcher(p).find();
                    boolean inlinePk = Pattern.compile("\\bPRIMARY\\s+KEY\\b", Pattern.CASE_INSENSITIVE)
                            .matcher(p).find();
                    if (inlinePk) {
                        t.pkName = t.name + "_pkey";
                        t.pkColumns = List.of(col);
                        notNull = true;
                    }
                    t.columns.put(col, notNull);
                }
            }
        }
    }

    private static int lastCloseParen(String block) {
        // block 从 CREATE TABLE 的左括号之后开始，深度从 1 起算。
        int depth = 1;
        int end = block.length();
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        return end;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean dollar = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (sql.startsWith("$$", i)) {
                dollar = !dollar;
                cur.append("$$");
                i++;
                continue;
            }
            if (dollar) {
                cur.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ';' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static List<String> columnList(String decl) {
        Matcher m = Pattern.compile("\\(([^)]*)\\)").matcher(decl);
        if (!m.find()) {
            throw new IllegalArgumentException("无法解析列清单: " + decl);
        }
        return columnListOf(m.group(1));
    }

    private static List<String> columnListOf(String raw) {
        List<String> cols = new ArrayList<>();
        for (String c : raw.split(",")) {
            String col = c.trim().toLowerCase(Locale.ROOT);
            if (!col.isEmpty()) {
                cols.add(col);
            }
        }
        return cols;
    }

    private static String identAfter(String s, String keyword) {
        int idx = s.toUpperCase(Locale.ROOT).indexOf(keyword.toUpperCase(Locale.ROOT));
        if (idx < 0) {
            throw new IllegalStateException("缺少关键字 " + keyword + ": " + s);
        }
        Matcher m = Pattern.compile("([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE)
                .matcher(s.substring(idx + keyword.length()));
        if (!m.find()) {
            throw new IllegalStateException("无法解析标识符: " + s);
        }
        return m.group(1).toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> createTableBlocks(String sql) {
        Map<String, String> blocks = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.end(), 0});
            names.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int end = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : sql.length();
            blocks.put(names.get(i), sql.substring(start, Math.min(end, sql.length())));
        }
        return blocks;
    }

    // ---------- 逐表账 ----------

    record LedgerEntry(String name, String attribution, List<String> primaryKey, String tenantColumn,
            boolean memberColumn, String ownerColumn, List<List<String>> uniqueTenantScoped,
            List<String> foreignKeys) {
        static LedgerEntry from(Map<String, Object> m) {
            return new LedgerEntry(
                    (String) m.get("name"),
                    (String) m.get("attribution"),
                    strList(m.get("primaryKey")),
                    (String) m.get("tenantColumn"),
                    Boolean.TRUE.equals(m.get("memberColumn")),
                    (String) m.get("ownerColumn"),
                    uniqueLists(m.get("uniqueTenantScoped")),
                    strList(m.get("foreignKeys")));
        }

        private static List<String> strList(Object o) {
            if (!(o instanceof List<?> l)) {
                return List.of();
            }
            return l.stream().map(String::valueOf).toList();
        }

        @SuppressWarnings("unchecked")
        private static List<List<String>> uniqueLists(Object o) {
            if (!(o instanceof List<?> l)) {
                return List.of();
            }
            return l.stream().map(x -> ((List<Object>) x).stream().map(String::valueOf).toList()).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, LedgerEntry> readLedger() throws IOException {
        String json = read(locate(LEDGER));
        Map<String, LedgerEntry> out = new LinkedHashMap<>();
        Matcher tablesArray = Pattern.compile("\"tables\"\\s*:\\s*\\[(.*)\\]\\s*\\}\\s*$",
                Pattern.DOTALL).matcher(json);
        if (!tablesArray.find()) {
            throw new IllegalStateException("逐表账缺少 tables 数组");
        }
        String body = tablesArray.group(1);
        Matcher entry = Pattern.compile("\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}", Pattern.DOTALL)
                .matcher(body);
        while (entry.find()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            String obj = entry.group(1);
            // 提取标量字段
            Matcher scalar = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(obj);
            while (scalar.find()) {
                fields.put(scalar.group(1), scalar.group(2));
            }
            // 提取布尔字段
            Matcher bools = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*(true|false)").matcher(obj);
            while (bools.find()) {
                fields.put(bools.group(1), Boolean.parseBoolean(bools.group(2)));
            }
            // primaryKey / foreignKeys：字符串数组
            Matcher arr = Pattern.compile("\"(primaryKey|foreignKeys)\"\\s*:\\s*\\[([^]]*)\\]",
                    Pattern.DOTALL).matcher(obj);
            while (arr.find()) {
                fields.put(arr.group(1), stringArray(arr.group(2)));
            }
            // uniqueTenantScoped：数组的数组
            Matcher uni = Pattern.compile("\"uniqueTenantScoped\"\\s*:\\s*\\[(.*)]",
                    Pattern.DOTALL).matcher(obj);
            if (uni.find()) {
                List<Object> lists = new ArrayList<>();
                Matcher inner = Pattern.compile("\\[([^]]*)]").matcher(uni.group(1));
                while (inner.find()) {
                    lists.add(stringArray(inner.group(1)));
                }
                fields.put("uniqueTenantScoped", lists);
            }
            String name = (String) fields.get("name");
            if (name == null) {
                continue; // 头部元数据对象（specAnchor/purpose 等）
            }
            out.put(name, LedgerEntry.from(fields));
        }
        if (out.size() < 40) {
            throw new IllegalStateException("逐表账解析出的表数量异常: " + out.size());
        }
        return out;
    }

    private static List<String> stringArray(String raw) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(raw);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    // ---------- 文件定位 ----------

    private static Path locate(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path viaRag = dir.resolve("services").resolve("ai").resolve("rag").resolve(relative);
            Path viaAi = dir.resolve("services").resolve("ai").resolve(relative);
            if (Files.isRegularFile(viaRag)) {
                return viaRag;
            }
            if (Files.isRegularFile(viaAi)) {
                return viaAi;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String sha256(Path p) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(p));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
