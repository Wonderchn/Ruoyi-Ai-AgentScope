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

package com.nageoffer.ai.ragent.rag.core.unified;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E5/WP-025 源码护栏：AI 侧三模块（runtime/rag/agent）的生产与测试源码**不得**再引用冻结 AI 表名，
 * 且生产 SQL 里出现的每个统一表名必须真的存在于 **V7 起的整条迁移链**建立的形状里。
 *
 * <p><b>为什么需要两条判据。</b>
 * <ul>
 *   <li>第一条防"改了一半"：旧表名在统一库里不存在，任何残留都是运行期才会炸的语句；</li>
 *   <li>第二条防"改错方向"：把名字改成统一库里没有的表（例如把 {@code t_message} 写成
 *       {@code ai_chat_message} 这类名字相近但语义不同的表）编译同样通过，只有对照迁移文本
 *       才能发现。表名清单从冻结 SQL **解析**得到，不是硬编码数组——硬编码清单会随迁移漂移
 *       而悄悄过期。</li>
 * </ul>
 *
 * <p><b>G-37（判据作用域过期）与它的锚点。</b>第二条判据曾经只解析 {@code V7..V10}，于是 V11 之后
 * 新增的 {@code ai_*} 表（V15 的 {@code ai_runtime_config_revision}、V18 的 {@code ai_mq_consume_dedup} …）
 * 一律被判"未建表"，护栏对 V11 之后的迁移形同失效（失败方向是安全的误报，但覆盖已经漏了）。
 * 现在扫描 **V7 起的全链** {@code V\d+__*.sql}，并由
 * {@link #everyUnifiedTableNameIsBuiltByTheFrozenMigrations()} 的锚点断言
 * 要求"新迁移引入的表确实被读到" —— 没有这条锚点，"改回窄作用域"这件事本身会静默通过。
 *
 * <p><b>刻意豁免。</b>{@code AclProvider} 与 {@code SyntheticAclProvider} 的注释陈述的是
 * <b>冻结 AI 基线</b>的事实（"基线表没有 tenant/ACL 列"），旧表名在那里是正确写法；
 * 冻结 AI 链夹具（{@code p1/resources.sql}、{@code p1/table-attribution.json}、
 * {@code P1TableTenantAttributionTest}）校验冻结迁移文本，同样保持原名。
 * 豁免按<b>文件+行内容</b>登记在此，收紧或放宽都会让本测试失败。
 */
@Tag("dev")
class P1UnifiedTableNameGuardTest {

    /**
     * 扫描根：整个 platform 树。WP-025 只覆盖三个 AI 模块，WP-026 起平台自己的
     * {@code ruoyi-common-chat} / {@code ruoyi-common-trace} / {@code ruoyi-chat} / {@code ruoyi-aiflow}
     * 也映射统一表，所以扫描范围必须与"会被迁移改名"的范围一致。
     */
    private static final Path MODULES = Path.of("services", "platform");
    private static final Path PLATFORM_SQL = Path.of("services", "platform", "docs", "script", "sql", "postgres");

    /** 允许保留旧表名的文件（相对 MODULES），因为注释陈述的是冻结 AI 基线的事实。 */
    private static final Set<String> BASELINE_FACT_FILES = Set.of(
            "ruoyi-modules/ruoyi-ai-rag/src/main/java/com/nageoffer/ai/ragent/rag/runtime/AclProvider.java",
            "ruoyi-modules/ruoyi-ai-rag/src/test/java/com/nageoffer/ai/ragent/rag/runtime/p04/SyntheticAclProvider.java");

    /** 冻结 AI 链夹具：校验冻结迁移文本，不参与统一链改名。 */
    private static final List<String> FROZEN_CHAIN_FIXTURES = List.of(
            "P1TableTenantAttributionTest.java",
            "resources/p1/resources.sql",
            "resources/p1/table-attribution.json");

    /**
     * 本护栏自身：{@link #RENAMED} 与解析用正则里必须写出旧表名与新表名，否则无从扫描。
     * 它是"扫描器"而不是"被扫描的实现"，因此按文件豁免；豁免同时被
     * {@link #exemptionsStayJustified()} 之外的 {@link #scannerFileIsOnlyTheGuard()} 钉住。
     */
    private static final String GUARD_SELF = "P1UnifiedTableNameGuardTest.java";

    /**
     * 迁移产物与冻结链验收：它们<em>按设计</em>要写旧表名。
     * <ul>
     *   <li>{@code docs/script/sql/**} 是迁移/落地区生成物，旧表名是它的输入；</li>
     *   <li>{@code P1OptionalBackendsClosedTest} 的结构断言针对冻结独立 AI 应用（WP-059 归档）；</li>
     *   <li>两个 ACL provider 的注释陈述冻结 AI 基线事实。</li>
     * </ul>
     */
    private static final List<String> NOT_SCANNED_PATHS = List.of(
            "docs/script/sql/",
            "P1OptionalBackendsClosedTest",
            "src/test/resources/p1/");

    /** 冻结 AI 表名 -> 统一表名（与 03-table-map.json 的 source.schema=ai 一致）。 */
    private static final List<String[]> RENAMED = List.of(
            new String[]{"t_agent_context_compaction", "ai_agent_context_compaction"},
            new String[]{"t_agent_conversation", "ai_agent_conversation"},
            new String[]{"t_agent_memory_control", "ai_agent_memory_control"},
            new String[]{"t_agent_memory_extraction", "ai_agent_memory_extraction"},
            new String[]{"t_agent_memory", "ai_agent_memory"},
            new String[]{"t_agent_message", "ai_agent_message"},
            new String[]{"t_agent_profile", "ai_agent_profile"},
            new String[]{"t_agent_prompt", "ai_agent_prompt"},
            new String[]{"t_agent_skill", "ai_agent_skill"},
            new String[]{"t_agent_state", "ai_agent_state"},
            new String[]{"t_biz_change_log", "ai_biz_change_log"},
            new String[]{"t_conversation_summary", "ai_conversation_summary"},
            new String[]{"t_conversation", "ai_conversation"},
            new String[]{"t_ingestion_pipeline_node", "ai_ingestion_pipeline_node"},
            new String[]{"t_ingestion_pipeline", "ai_ingestion_pipeline"},
            new String[]{"t_ingestion_task_node", "ai_ingestion_task_node"},
            new String[]{"t_ingestion_task", "ai_ingestion_task"},
            new String[]{"t_intent_node", "ai_intent_node"},
            new String[]{"t_knowledge_base", "ai_knowledge_base"},
            new String[]{"t_knowledge_chunk", "ai_knowledge_chunk"},
            new String[]{"t_knowledge_document_chunk_log", "ai_knowledge_document_chunk_log"},
            new String[]{"t_knowledge_document_schedule_exec", "ai_knowledge_document_schedule_exec"},
            new String[]{"t_knowledge_document_schedule", "ai_knowledge_document_schedule"},
            new String[]{"t_knowledge_document", "ai_knowledge_document"},
            new String[]{"t_knowledge_vector", "ai_knowledge_vector"},
            new String[]{"t_message_feedback", "ai_message_feedback"},
            new String[]{"t_message", "ai_message"},
            new String[]{"t_query_term_mapping", "ai_query_term_mapping"},
            new String[]{"t_rag_trace_node", "ai_rag_trace_node"},
            new String[]{"t_rag_trace_run", "ai_rag_trace_run"},
            new String[]{"t_sample_question", "ai_sample_question"},
            new String[]{"t_user", "ai_legacy_user"},
            // WP-026: 平台侧实体原来指向的旧 MySQL 表名（03-table-map.json source.schema=mysql/ruoyi-ai.sql）
            new String[]{"agent_info", "ai_agent_profile"},
            new String[]{"chat_config", "ai_model_config"},
            new String[]{"chat_message", "ai_message"},
            new String[]{"chat_model", "ai_model"},
            new String[]{"chat_provider", "ai_model_provider"},
            new String[]{"chat_session", "ai_conversation"},
            new String[]{"knowledge_attach", "ai_knowledge_document"},
            new String[]{"knowledge_fragment", "ai_knowledge_chunk"},
            new String[]{"knowledge_info", "ai_knowledge_base"},
            new String[]{"mcp_market_info", "ai_mcp_market"},
            new String[]{"mcp_market_tool", "ai_mcp_market_tool"},
            new String[]{"mcp_tool_info", "ai_mcp_tool"},
            new String[]{"short_drama_audio", "ai_short_drama_audio"},
            new String[]{"short_drama_character_appearance", "ai_short_drama_character_appearance"},
            new String[]{"short_drama_character", "ai_short_drama_character"},
            new String[]{"short_drama_location", "ai_short_drama_location"},
            new String[]{"short_drama_project", "ai_short_drama_project"},
            new String[]{"short_drama_script", "ai_short_drama_script"},
            new String[]{"short_drama_storyboard", "ai_short_drama_storyboard"},
            new String[]{"t_workflow_component", "ai_flow_component"},
            new String[]{"t_workflow_edge", "ai_flow_edge"},
            new String[]{"t_workflow_node", "ai_flow_node"},
            new String[]{"t_workflow_runtime_node", "ai_flow_runtime_node"},
            new String[]{"t_workflow_runtime", "ai_flow_runtime"},
            new String[]{"t_workflow", "ai_flow_workflow"},
            new String[]{"trace_node", "ai_flow_trace_node"},
            new String[]{"trace_run", "ai_flow_trace_run"});

    private static final Pattern OLD_NAMES = oldNamePattern();
    private static final Pattern AI_TABLE_IN_SQL = Pattern.compile(
            "\\bai_[a-z_]{3,}\\b", Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------ 判据一：旧表名不残留

    @Test
    @DisplayName("AI 三模块源码不再引用冻结 AI 表名（豁免文件按名登记）")
    void noProductionOrTestSourceReferencesAFrozenTableName() throws IOException {
        Path root = modulesRoot();
        List<String> offenders = new ArrayList<>();
        for (Path file : sourceFiles()) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            if (isNotScanned(rel) || BASELINE_FACT_FILES.contains(rel)) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = OLD_NAMES.matcher(text);
            while (m.find()) {
                offenders.add(rel + ": " + m.group(1));
            }
        }
        assertThat(offenders)
                .as("统一链下这些表名不存在，残留只会在运行期炸；应改成对应的 ai_* 名字")
                .isEmpty();
    }

    /**
     * 豁免必须仍然名副其实：被豁免的文件里旧表名只能出现在注释里，
     * 且豁免文件确实还在（文件改名会让豁免悄悄失效）。
     */
    @Test
    @DisplayName("豁免文件仍然存在，且旧表名只出现在注释中")
    void exemptionsStayJustified() throws IOException {
        Path modulesRoot = modulesRoot();
        for (String rel : BASELINE_FACT_FILES) {
            Path file = modulesRoot.resolve(rel);
            assertThat(Files.isRegularFile(file))
                    .as("豁免文件不存在，豁免应删除：" + rel).isTrue();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!OLD_NAMES.matcher(line).find()) {
                    continue;
                }
                String s = line.strip();
                assertThat(s.startsWith("*") || s.startsWith("//") || s.startsWith("/*") || s.startsWith("--"))
                        .as("被豁免的是「陈述冻结 AI 基线事实的注释」；这一行是代码：" + rel + " -> " + s)
                        .isTrue();
            }
        }
    }

    // ------------------------------------------------------------ 判据二：统一表名真实存在

    @Test
    @DisplayName("生产 SQL 引用的统一表名都存在于 V7 起整条迁移链的形状里")
    void everyUnifiedTableNameIsBuiltByTheFrozenMigrations() throws IOException {
        Set<String> built = tablesCreatedByFrozenUnifiedMigrations();
        assertThat(built)
                .as("从 V7 起全链解析出的统一表（锚点抽样：解析器必须真的读到建表语句）")
                .contains("ai_conversation", "ai_message", "ai_message_feedback", "ai_knowledge_chunk",
                        "ai_resource", "ai_agent_state")
                .as("G-37 作用域锚点：V11 之后新增的表必须也在解析结果里。"
                        + "若有人把作用域改回 V7..V10，这两条会立刻失败——"
                        + "否则'改回窄作用域'会静默通过，护栏再次对 V11+ 失效")
                .contains("ai_runtime_config_revision", "ai_mq_consume_dedup");

        List<String> unknown = new ArrayList<>();
        for (Path file : sourceFiles()) {
            String rel = modulesRoot().relativize(file).toString().replace('\\', '/');
            if (isNotScanned(rel)) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (String table : referencedTablesInSql(text)) {
                if (!built.contains(table)) {
                    unknown.add(rel + ": " + table);
                }
            }
        }
        assertThat(unknown)
                .as("SQL 里引用的 ai_* 表在 V7 起的迁移链里没有对应建表语句")
                .isEmpty();
    }

    /** 32 张改名表在源码里出现的名字必须是统一名，而不是"名字相近的另一张表"。 */
    @Test
    @DisplayName("改名表的统一目标名与冻结迁移解析结果一致")
    void renamedTargetsMatchTheFrozenShape() throws IOException {
        Set<String> built = tablesCreatedByFrozenUnifiedMigrations();
        for (String[] pair : RENAMED) {
            assertThat(built)
                    .as("%s 应改名为 %s，但冻结迁移里没有这张表", pair[0], pair[1])
                    .contains(pair[1]);
        }
    }

    // ------------------------------------------------------------ helpers

    private static List<Path> sourceFiles() throws IOException {
        Path root = modulesRoot();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String rel = p.toString().replace('\\', '/');
                        if (rel.contains("/target/") || rel.contains("/node_modules/")) {
                            return false;
                        }
                        return rel.endsWith(".java") || rel.endsWith(".sql") || rel.endsWith(".xml");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static Path modulesRoot() {
        return locate(MODULES);
    }

    /**
     * 不参与扫描的文件：冻结夹具（校验冻结文本）、护栏自身（是扫描器）、
     * 迁移产物与针对冻结独立 AI 应用的结构断言（旧表名是它们的输入）。
     */
    private static boolean isNotScanned(String rel) {
        return rel.endsWith(GUARD_SELF)
                || FROZEN_CHAIN_FIXTURES.stream().anyMatch(rel::contains)
                || NOT_SCANNED_PATHS.stream().anyMatch(rel::contains);
    }

    /**
     * 护栏自身豁免不能变成"把实现也豁免掉"的漏洞：全仓库唯一允许命中
     * {@link #GUARD_SELF} 的文件就是本类。
     */
    @Test
    @DisplayName("护栏自身的豁免只覆盖本文件")
    void scannerFileIsOnlyTheGuard() throws IOException {
        List<String> others = new ArrayList<>();
        for (Path file : sourceFiles()) {
            String rel = modulesRoot().relativize(file).toString().replace('\\', '/');
            if (rel.endsWith(GUARD_SELF)) {
                continue;
            }
            if (file.getFileName().toString().equals(GUARD_SELF)) {
                others.add(rel);
            }
        }
        assertThat(others)
                .as("另一个同名文件会共享本护栏的豁免，必须改名或显式处理")
                .isEmpty();
    }

    /** 只在 SQL 文本里找表名：{@code FROM/JOIN/INTO/UPDATE (+ schema) table}。 */
    private static Set<String> referencedTablesInSql(String text) {
        Set<String> out = new LinkedHashSet<>();
        // 字符串字面量与文本块里的 DML
        Matcher dml = Pattern.compile(
                "(?i)\\b(?:FROM|JOIN|INTO|UPDATE)\\s+(?:platform\\s*\\.\\s*)?\"?(ai_[a-z_]+)\"?")
                .matcher(text);
        while (dml.find()) {
            out.add(dml.group(1).toLowerCase(Locale.ROOT));
        }
        // @TableName("ai_x") —— 实体绑定也是生产引用
        Matcher tableName = Pattern.compile("@TableName\\s*\\(\\s*(?:value\\s*=\\s*)?\"(ai_[a-z_]+)\"")
                .matcher(text);
        while (tableName.find()) {
            out.add(tableName.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * V7 起**整条链**建立的 {@code ai_*} 表形状。
     *
     * <p><b>G-37：</b>这里以前写死 {@code for (int v = 7; v <= 10; v++)}，于是 V11 之后新增的
     * {@code ai_*} 表一律被判"未建表"——护栏对 V11 之后的迁移形同失效（假阴性方向的误报，
     * 覆盖已经漏了）。现在扫描全链 {@code V\d+__*.sql} 且 {@code version >= 7}；
     * 调用方的锚点断言会要求"V15/V18 引入的表确实被读到"，防止作用域再被改窄。
     */
    private static Set<String> tablesCreatedByFrozenUnifiedMigrations() throws IOException {
        Path sqlDir = locate(PLATFORM_SQL);
        Set<String> built = new LinkedHashSet<>();
        List<Path> files;
        try (Stream<Path> stream = Files.list(sqlDir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .filter(p -> versionOf(p) >= 7)
                    .sorted((a, b) -> Integer.compare(versionOf(a), versionOf(b)))
                    .toList();
        }
        assertThat(files).as("缺少 V7 起的迁移文件").isNotEmpty();
        for (Path file : files) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(
                    "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:platform\\s*\\.\\s*)?\"?(ai_[a-z_]+)\"?")
                    .matcher(sql);
            while (m.find()) {
                built.add(m.group(1).toLowerCase(Locale.ROOT));
            }
            // V7 起用 ALTER TABLE ... ADD COLUMN 补齐身份列，也是形状的一部分；
            // 这里只收集"被引用但未 CREATE"的漏网（例如只出现在 ALTER 里）交给下游判断。
            Matcher alter = Pattern.compile(
                    "(?i)ALTER\\s+TABLE\\s+(?:platform\\s*\\.\\s*)?\"?(ai_[a-z_]+)\"?")
                    .matcher(sql);
            while (alter.find()) {
                built.add(alter.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return built;
    }

    private static int versionOf(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Pattern oldNamePattern() {
        List<String> names = RENAMED.stream().map(p -> p[0]).sorted((a, b) -> b.length() - a.length()).toList();
        return Pattern.compile("(?<![A-Za-z0-9_])(" + String.join("|", names) + ")(?![A-Za-z0-9_])");
    }

    /** 从测试工作目录向上找仓库根下的相对路径。 */
    private static Path locate(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
