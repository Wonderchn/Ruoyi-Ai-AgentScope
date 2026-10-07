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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-028 源码护栏：<b>合并表平台侧追加列的登记表完整性</b>。
 *
 * <p><b>为什么需要它。</b>计划 §6 说合并表有「69 个追加列」，后续包（回填、补 {@code NOT NULL}、
 * 知识域适配）都按这个数字与清单推进。但那个数字从来没有对着 DDL 核过：它是对 V9 文本
 * grep {@code ADD COLUMN} 的命中数，其中 1 条命中是文件头注释（说明"V7 已建的表改成增量合并"
 * 的约定），不是 DDL 语句。语句级实测是 <b>68</b>，再扣除 6 条 {@code IF NOT EXISTS} 空操作
 * （{@code tenant_id} 已由 V7 加过），真正新增的平台侧列只有 <b>62</b>。
 *
 * <p>数字本身不是重点，<b>"列清单只有一处可审来源"</b>才是。所以本护栏：
 * <ol>
 *   <li><b>登记表完整性</b>——冻结迁移里给这 6 张合并表加列的每一条语句都必须在登记表里；
 *       出现未登记的列时<b>响亮失败</b>，而不是靠某次评审记得；</li>
 *   <li><b>登记表不得过期</b>——登记了但迁移里没有的条目同样失败；</li>
 *   <li><b>必填列必须有显式决定</b>——原约束强制（旧 MySQL DDL {@code NOT NULL} 或统一表
 *       {@code NOT NULL}）的列，要么在登记表里写出可执行来源，要么登记为 {@code blocked}
 *       并给出理由；不允许静默留空。</li>
 * </ol>
 *
 * <p>第 3 条对应一个真实风险：追加列一律建成可空，是因为"已有 AI 行无法提供平台侧取值，
 * 凭空造默认值等于伪造数据"。这个理由对<b>可选</b>列成立，对<b>原约束必填</b>的列只是把
 * 缺口推后——它必须有来源，或者被显式登记成待批决定。本护栏把两者分开钉住。
 *
 * <p>登记表本体是 {@code src/test/resources/unified/merged-append-column-registry.json}，
 * 由 WP-028 的机械抽取脚本从冻结迁移 + 旧 MySQL 安装脚本 + 平台实体源码生成。
 * 本类不重复造"实体映射列是否存在"的判据（那在 {@link P1PlatformEntityShapeGuardTest}）。
 *
 * <p><b>登记位（D4 修复）。</b>上面的 68 列是 <b>冻结 V9</b> 的口径，结构上不可扩展：
 * 新迁移在那份清单里没有位置。结果是 V9 之后给这 6 张表加列的迁移只能"绕"——
 * V13 与 V29 都在自己的 SQL 注释里写明了它们刻意避开 {@code ADD COLUMN IF NOT EXISTS}，
 * 因为旧判据只在 {@code ifNotExists()} 为真时才把未登记列算作违规。
 * <b>判定取决于 SQL 写法而不是是否登记</b>，这是口径缺陷，不是迁移的错。
 *
 * <p>因此登记表新增 {@code post_v9_append_columns} 分区：<b>V9 之后</b>（含 V13、V29
 * 及以后任何迁移）给这 6 张表追加的列必须在该分区显式声明
 * {@code table / column / source_migration / pg_definition / pg_not_null / reason / write_source}。
 * 该分区判据<b>刻意不接受 {@code ifNotExists} 参数</b>，裸 {@code ADD COLUMN} 与
 * {@code ADD COLUMN IF NOT EXISTS} 一视同仁，所以"换个写法绕过"不再可行；同时它把新迁移的
 * 登记位补上了，新迁移不必再绕。冻结的 68 列口径（{@code registered == V9}）保持不变。
 */
@Tag("dev")
class P1MergedAppendColumnRegistryGuardTest {

    /** 登记表：唯一来源。路径相对测试类路径。 */
    private static final String REGISTRY_RESOURCE = "/unified/merged-append-column-registry.json";

    private static final Path PLATFORM_SQL =
            Path.of("services", "platform", "docs", "script", "sql", "postgres");

    /**
     * 登记表覆盖的 6 张"合并表"：统一表名 -> 来源旧表名。
     *
     * <p>键是统一表名；值只用于消息，不参与判定。旧表名写在这里会把
     * {@link P1UnifiedTableNameGuardTest} 的旧名扫描器引到本文件（它扫注释），
     * 所以值一律写成中文说明，真实旧表名只存在于登记表 JSON 里。
     */
    private static final Map<String, String> MERGED_TABLES = Map.of(
            "ai_conversation", "旧平台会话表",
            "ai_message", "旧平台消息表",
            "ai_agent_profile", "旧平台智能体表",
            "ai_knowledge_base", "旧平台知识库表",
            "ai_knowledge_document", "旧平台知识文档表",
            "ai_knowledge_chunk", "旧平台知识片段表");

    /**
     * 实测口径（用于防回归：数字变了要显式改，而不是让文档与 DDL 继续漂移）。
     * 见登记表 {@code count_discrepancy} 字段。
     */
    private static final int EXPECTED_STATEMENTS = 68;
    private static final int EXPECTED_TRULY_NEW = 62;
    private static final int EXPECTED_NOOP = 6;

    /**
     * 登记表 {@code columns} 分区覆盖的冻结窗口终点：V9。
     *
     * <p>V9 之后给合并表加列的迁移<b>不</b>进入该分区（它必须保持 68 列的冻结口径），
     * 而必须在 {@code post_v9_append_columns} 分区声明。
     */
    private static final int FROZEN_REGISTRY_VERSION = 9;

    /**
     * 冻结扫描窗口：登记表生成时已存在的、会触碰合并表的迁移版本。
     *
     * <p>显式限定，取代原来"{@code Files.list} 遍历整个目录、DisplayName 却声称只覆盖
     * 冻结迁移"的隐式口径——否则以后每加一个迁移都会悄悄进入冻结判据的扫描面。
     */
    private static final Set<String> FROZEN_VERSIONS =
            Set.of("V7", "V8", "V9", "V10", "V11", "V12", "V13");

    /** V9 之后追加列的登记位分区名。 */
    private static final String POST_V9_PARTITION = "post_v9_append_columns";

    /** 分区每条声明必须给出的字段：少一个就等于没有登记位。 */
    private static final Set<String> POST_V9_REQUIRED_FIELDS =
            Set.of("table", "column", "source_migration", "pg_definition", "pg_not_null",
                    "reason", "write_source");

    /**
     * 当前已声明的 post-V9 追加列。<b>只做存在性锚点，不做等值断言</b>：
     * 将来再加列时改登记表即可，不必同时改测试——否则又会变成"结构上不可扩展"。
     */
    private static final Set<String> KNOWN_POST_V9_DECLARED = Set.of(
            "ai_conversation.version",
            "ai_knowledge_document.version");

    /**
     * 原约束必填但<b>没有可执行来源</b>的列，必须<b>恰好</b>是这 5 列。
     *
     * <p>全部落在知识域三表——它们因缺两项部署输入（旧向量集前缀、对象存储登记来源）在
     * V11 里登记为 {@code blocked}，所以本包也给不出来源；这不是遗漏，是显式决定。
     * 新增一处就会失败：那时必须先拿到来源或给出理由，不能靠"追加列一律可空"糊过去。
     */
    private static final Set<String> EXPECTED_BLOCKED_REQUIRED = Set.of(
            "ai_knowledge_base.user_id",
            "ai_knowledge_chunk.fid",
            "ai_knowledge_chunk.idx",
            "ai_knowledge_document.knowledge_id",
            "ai_knowledge_document.type");

    /** 原约束必填且有可执行来源的列，同样必须恰好相等（防止"来源"被静默扩大解释）。 */
    private static final Set<String> EXPECTED_SUPPLIED_REQUIRED = Set.of(
            "ai_agent_profile.agent_name",
            "ai_agent_profile.model_id",
            "ai_agent_profile.tenant_id",
            "ai_conversation.tenant_id",
            "ai_knowledge_base.tenant_id",
            "ai_knowledge_chunk.tenant_id",
            "ai_knowledge_document.tenant_id",
            "ai_message.tenant_id");

    private static final Set<String> EXECUTABLE_SOURCES = Set.of("v7_ai_shape", "v11_legacy_copy");

    private static final Set<String> NON_EXECUTABLE_SOURCES =
            Set.of("v11_blocked", "platform_entity_only", "none");

    private static final Set<String> ALL_DECISIONS =
            Set.of("supplied", "blocked", "optional_no_decision_needed");

    // ------------------------------------------------------------------ 判据

    @Test
    @DisplayName("登记表必须可读且自洽：行数、表集合、口径与实测一致")
    void registryIsReadableAndSelfConsistent() throws IOException {
        List<Map<String, Object>> columns = registry();
        assertThat(columns)
                .as("登记表必须真的读到列，否则下面每条判据都是空跑")
                .hasSize(EXPECTED_STATEMENTS);
        assertThat(counts().path("statements_in_v9_merged_blocks").asInt())
                .as("登记表自报的语句数")
                .isEqualTo(EXPECTED_STATEMENTS);
        assertThat(registryRoot().path("problems").isEmpty())
                .as("抽取脚本自报的 problem 必须为空：" + registryRoot().path("problems"))
                .isTrue();

        Set<String> tables = new TreeSet<>();
        Set<String> keys = new TreeSet<>();
        for (Map<String, Object> c : columns) {
            tables.add((String) c.get("table"));
            String key = c.get("table") + "." + c.get("column");
            assertThat(keys.add(key)).as("登记表里有重复行：" + key).isTrue();
        }
        assertThat(tables)
                .as("登记表覆盖的合并表集合")
                .isEqualTo(new TreeSet<>(MERGED_TABLES.keySet()));

        // 口径：68 条语句 = 62 真新增 + 6 条 IF NOT EXISTS 空操作
        Map<String, Object> counts = new TreeMap<>();
        counts().fields().forEachRemaining(e -> counts.put(e.getKey(), e.getValue().asInt()));
        assertThat((Integer) counts.get("truly_new_platform_columns")).isEqualTo(EXPECTED_TRULY_NEW);
        assertThat((Integer) counts.get("noop_if_not_exists")).isEqualTo(EXPECTED_NOOP);
        assertThat((Integer) counts.get("truly_new_platform_columns")
                + (Integer) counts.get("noop_if_not_exists"))
                .as("真新增 + 空操作 必须等于语句总数")
                .isEqualTo(EXPECTED_STATEMENTS);
        assertThat((Integer) counts.get("grep_hits_including_comment"))
                .as("grep 口径比语句口径多出的那 1 条，就是文件头注释；"
                        + "计划里的『69 列』来自 grep，不是 DDL 语句数")
                .isEqualTo(EXPECTED_STATEMENTS + 1);
    }

    @Test
    @DisplayName("冻结窗口 V7..V13 给合并表加的每一列都必须在登记表里：未登记列必须响亮失败")
    void everyAppendColumnInTheFrozenMigrationsIsRegistered() throws IOException {
        Set<String> registered = registeredKeys();
        assertThat(registered).as("锚点：登记表非空").hasSize(EXPECTED_STATEMENTS);

        Set<String> found = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        // 扫描面显式限定到冻结窗口：本判据的 {@code ifNotExists} 语义只对 V9 的
        // "ADD COLUMN IF NOT EXISTS" 有定义（V7 的 10 列是建表期裸加列，不属于登记表口径）。
        // V9 之后的新迁移由 everyAppendColumnAfterV9IsDeclaredInThePostV9Partition 判定。
        for (Map.Entry<String, Path> e : frozenMigrationFiles().entrySet()) {
            for (AppendColumn ac : appendColumns(e.getValue(), e.getKey())) {
                if (!MERGED_TABLES.containsKey(ac.table())) {
                    continue;
                }
                String key = ac.table() + "." + ac.column();
                found.add(key);
                if (!registered.contains(key) && ac.ifNotExists()) {
                    offenders.add(key + "  (" + ac.version() + ")");
                }
            }
        }
        assertThat(offenders)
                .as("冻结迁移给合并表加了未登记的列。追加列必须过一次显式审查："
                        + "在登记表里登记它的原约束、既有行是否可能有值、可选性、"
                        + "平台写入契约的来源，以及无来源时的处置")
                .isEmpty();

        // 复式核对：登记表必须完全等于 V9 六张合并表的语句集合（不多不少）
        Set<String> v9 = new TreeSet<>();
        for (AppendColumn ac : appendColumns(migrationFiles().get("V9"), "V9")) {
            if (MERGED_TABLES.containsKey(ac.table()) && ac.ifNotExists()) {
                v9.add(ac.table() + "." + ac.column());
            }
        }
        assertThat(v9).as("V9 合并块的语句集合").hasSize(EXPECTED_STATEMENTS);
        assertThat(registered)
                .as("登记表既不能漏列，也不能留着迁移里已不存在的过期条目")
                .isEqualTo(v9);
        assertThat(found).as("锚点：迁移里确实扫到了合并表加列语句；为空说明解析器读空了")
                .isNotEmpty()
                .contains("ai_conversation.create_by", "ai_knowledge_base.retrieve_limit");
    }

    /**
     * D4 登记位判据：<b>V9 之后</b>给合并表加列，必须在 {@code post_v9_append_columns} 里声明。
     *
     * <p>与上面冻结判据的关键差别：<b>本判据不看 {@code ADD COLUMN IF NOT EXISTS}</b>。
     * V13 与 V29 都在自己的 SQL 注释里写明它们刻意用裸 {@code ADD COLUMN} 来避开旧判据，
     * 所以只按写法判定，等于把"对口径有缺陷的护栏的精确规避"固化成通过条件。
     * 这里改成按<b>登记事实</b>判定，并给新迁移补上登记位。
     */
    @Test
    @DisplayName("V9 之后给合并表加列必须在 post_v9_append_columns 声明：裸 ADD COLUMN 同样要登记")
    void everyAppendColumnAfterV9IsDeclaredInThePostV9Partition() throws IOException {
        Set<String> declared = postV9DeclaredKeys();
        assertThat(declared)
                .as("锚点：post-V9 登记位非空，否则下面每条判据都是空跑")
                .isNotEmpty()
                .containsAll(KNOWN_POST_V9_DECLARED);
        assertThat(declared)
                .as("两个分区必须互斥：V9 的 " + EXPECTED_STATEMENTS + " 列是冻结口径，"
                        + "不得混进 post-V9 分区")
                .doesNotContainAnyElementsOf(registeredKeys());

        for (Map<String, Object> row : postV9DeclaredRows()) {
            for (String field : POST_V9_REQUIRED_FIELDS) {
                assertThat(row)
                        .as("post-V9 声明缺字段 " + field + "：" + row)
                        .containsKey(field);
            }
            for (String field : List.of("table", "column", "source_migration",
                    "pg_definition", "reason", "write_source")) {
                assertThat((String) row.get(field))
                        .as("post-V9 声明的 " + field + " 不能为空白：" + row)
                        .isNotBlank();
            }
            assertThat(row.get("pg_not_null"))
                    .as("post-V9 声明必须记下该列的 NOT NULL 事实（可空性不得靠猜）：" + row)
                    .isInstanceOf(Boolean.class);
        }

        Map<String, String> offenders = new TreeMap<>();
        Set<String> found = new TreeSet<>();
        for (Map.Entry<String, Path> e : postV9MigrationFiles().entrySet()) {
            for (AppendColumn ac : appendColumns(e.getValue(), e.getKey())) {
                if (!MERGED_TABLES.containsKey(ac.table())) {
                    continue;
                }
                String key = ac.table() + "." + ac.column();
                found.add(key);
                if (!postV9UnregisteredKeys(declared, ac.table(), ac.column()).isEmpty()) {
                    offenders.put(key, ac.version() + (ac.ifNotExists()
                            ? " (ADD COLUMN IF NOT EXISTS)" : " (裸 ADD COLUMN)"));
                }
            }
        }
        assertThat(offenders)
                .as("V9 之后给合并表加列必须在登记表 post_v9_append_columns 分区显式声明"
                        + "（table/column/source_migration/pg_definition/pg_not_null/reason/write_source）。"
                        + "本判据不区分 SQL 写法，所以『改成裸 ADD COLUMN 就绕过去』不再可行")
                .isEmpty();

        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(found);
        assertThat(stale)
                .as("post-V9 分区有迁移里已不存在的过期声明（声明了却没加列）")
                .isEmpty();

        assertThat(found)
                .as("锚点：post-V9 迁移里确实扫到了合并表加列语句；为空说明解析器读空了")
                .isNotEmpty()
                .containsAll(KNOWN_POST_V9_DECLARED);
    }

    /**
     * D4 负例实证：post-V9 判定器必须拒绝"新迁移加列但没登记"，且<b>不依赖 SQL 写法</b>。
     *
     * <p>没有这一条就无法区分"护栏被修好"与"护栏被绕过"。旧判定器
     * {@link #unregisteredKeys} 只在 {@code ifNotExists} 为真时命中——这正是 V13/V29 用裸
     * {@code ADD COLUMN} 逃逸的机制；本负例把两者的行为差别直接钉下来。
     */
    @Test
    @DisplayName("负例实证：post-V9 判定器对裸 ADD COLUMN 同样拒绝，不依赖 SQL 写法")
    void postV9CheckerRejectsUnregisteredColumnsRegardlessOfSqlForm() {
        Set<String> declared = Set.of("ai_conversation.version");
        // 正例
        assertThat(postV9UnregisteredKeys(declared, "ai_conversation", "version"))
                .as("已声明的列不该被判为未登记").isEmpty();
        // 负例：真实存在的 V29 列，登记表没声明
        assertThat(postV9UnregisteredKeys(declared, "ai_knowledge_document", "version"))
                .as("未声明的 post-V9 加列必须被拒绝（这是 V29 真实加的列）")
                .isNotEmpty()
                .contains("ai_knowledge_document.version");
        // 负例：构造的新列
        assertThat(postV9UnregisteredKeys(declared, "ai_message", "brand_new_column"))
                .as("未声明的 post-V9 加列必须被拒绝（构造用例）")
                .isNotEmpty();
        // 范围外不参与
        assertThat(postV9UnregisteredKeys(declared, "not_a_merged_table", "anything"))
                .as("不在 6 张合并表范围内的表不参与判定").isEmpty();

        // 对照：同一个未登记列，旧判定器在"裸 ADD COLUMN"下返回空（= 漏判，D4 缺陷本身），
        // 新判定器不为所动。这一对断言就是"咬合真的生效"的证据。
        assertThat(unregisteredKeys(declared, "ai_message", "brand_new_column", false))
                .as("对照：旧判定器在裸 ADD COLUMN 下确实漏判——D4 缺陷的机制")
                .isEmpty();
        assertThat(postV9UnregisteredKeys(declared, "ai_message", "brand_new_column"))
                .as("新判定器不因 SQL 写法而漏判")
                .isNotEmpty();
    }

    @Test
    @DisplayName("必填列必须有显式决定：要么有可执行来源，要么登记为 blocked 并说明理由")
    void everyRequiredColumnHasAnExplicitDecision() throws IOException {
        List<Map<String, Object>> columns = registry();
        List<String> problems = new ArrayList<>();
        Set<String> supplied = new TreeSet<>();
        Set<String> blocked = new TreeSet<>();
        int optional = 0;

        for (Map<String, Object> c : columns) {
            String key = c.get("table") + "." + c.get("column");
            boolean required = (Boolean) c.get("required");
            String decision = (String) c.get("decision");
            String source = (String) c.get("write_source");
            String reason = (String) c.get("blocked_reason");

            if (!ALL_DECISIONS.contains(decision)) {
                problems.add(key + ": 未登记的处置决定 " + decision);
                continue;
            }
            if (!required) {
                optional++;
                if (!"optional_no_decision_needed".equals(decision)) {
                    problems.add(key + ": 可选列不该有 blocked/supplied 决定，实际 " + decision);
                }
                continue;
            }
            // required 列：决定必须与来源一致，且理由不能是空白
            if ("optional_no_decision_needed".equals(decision)) {
                problems.add(key + ": 必填列没有显式决定（静默留空）");
            } else if ("supplied".equals(decision)) {
                if (!EXECUTABLE_SOURCES.contains(source)) {
                    problems.add(key + ": 声称有来源，但来源 " + source + " 不是可执行来源");
                }
                supplied.add(key);
            } else if ("blocked".equals(decision)) {
                if (!NON_EXECUTABLE_SOURCES.contains(source)) {
                    problems.add(key + ": 登记为 blocked，但来源 " + source + " 是可执行来源");
                }
                if (reason == null || reason.isBlank()) {
                    problems.add(key + ": 登记为 blocked 但没有给出理由");
                }
                blocked.add(key);
            }
        }

        assertThat(problems)
                .as("必填列必须有显式决定：有来源，或登记为 blocked 并给出理由")
                .isEmpty();
        assertThat(optional)
                .as("锚点：可选列确实存在；为 0 说明 required 判定读空了")
                .isGreaterThan(0);
        assertThat(supplied)
                .as("必填且有可执行来源的列。新增/减少都必须是显式决定——"
                        + "多一处要先确认来源真的可执行，少一处要确认它真的变成 blocked")
                .isEqualTo(new TreeSet<>(EXPECTED_SUPPLIED_REQUIRED));
        assertThat(blocked)
                .as("必填但无可执行来源的列（全部落在知识域三表：其 V11 复制路径因两项部署输入缺失"
                        + "而登记为 blocked）。这类列不能让『追加列一律可空』糊过去，"
                        + "必须以 blocked + 理由登记")
                .isEqualTo(new TreeSet<>(EXPECTED_BLOCKED_REQUIRED));
    }

    @Test
    @DisplayName("追加列一律可空；原约束必填的列必须登记下来，作为收紧前的待办")
    void appendedColumnsAreNullableAndRequiredOnesAreTracked() throws IOException {
        List<Map<String, Object>> columns = registry();
        List<String> notNull = new ArrayList<>();
        Set<String> relaxed = new TreeSet<>();
        for (Map<String, Object> c : columns) {
            String key = c.get("table") + "." + c.get("column");
            if (Boolean.TRUE.equals(c.get("pg_not_null"))) {
                notNull.add(key);
            }
            // 旧 MySQL DDL 是 NOT NULL、统一形状建成可空：约束被放松，必须留痕
            if (Boolean.TRUE.equals(c.get("legacy_not_null")) && !Boolean.TRUE.equals(c.get("pg_not_null"))) {
                relaxed.add(key);
            }
        }
        assertThat(notNull)
                .as("追加列必须一律可空：已有 AI 行无法提供平台侧值，造默认值等于伪造数据")
                .isEmpty();
        // relaxed = 旧库 NOT NULL 但统一形状建成可空的列 = 原约束必填列全体
        // （= 有来源的 8 列 + 无来源已登记 blocked 的 5 列）。13 列每一列都必须有显式决定，
        // 这正是上一条判据要求的；这里再把"约束确实被放松了"这件事留痕。
        Set<String> allRequired = new TreeSet<>(EXPECTED_SUPPLIED_REQUIRED);
        allRequired.addAll(EXPECTED_BLOCKED_REQUIRED);
        assertThat(allRequired).as("必填列全体").hasSize(13);
        assertThat(relaxed)
                .as("锚点：确实存在『旧库 NOT NULL → 统一形状可空』的列；"
                        + "为 0 说明旧 DDL 没解析到（本判据会失去意义）")
                .isNotEmpty()
                .isEqualTo(allRequired);
        assertThat(relaxed)
                .as("必填列数必须与登记表的 required 计数一致，否则说明 required 判定与"
                        + "『原约束』口径漂移了")
                .hasSize((Integer) registryRoot().path("counts").path("required").asInt());
    }

    /**
     * 负例实证：登记表判定器必须真的拒绝一个没登记的列。
     * 没有这一条，上面两条判据可能因为解析器读空而恒真。
     */
    @Test
    @DisplayName("负例实证：登记表判定器拒绝未登记的列，并接受真实存在的列")
    void registryCheckerRejectsWhatIsNotRegistered() {
        Set<String> registered = Set.of("ai_conversation.create_by", "ai_message.total_tokens");
        // 正例
        assertThat(unregisteredKeys(registered, "ai_conversation", "create_by", true))
                .as("已登记的列不该被判为未登记").isEmpty();
        // 负例：迁移里新加一列，登记表没跟上
        assertThat(unregisteredKeys(registered, "ai_conversation", "brand_new_column", true))
                .as("未登记的列必须被拒绝")
                .isNotEmpty()
                .contains("ai_conversation.brand_new_column");
        // 负例：不在覆盖范围内的表不参与（由 MERGED_TABLES 控制），但覆盖范围内的新表会被抓
        assertThat(unregisteredKeys(registered, "ai_message", "another_new_column", true))
                .as("第二张覆盖表上的未登记列同样必须被拒绝")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ 解析

    private record AppendColumn(String version, String table, String column, boolean ifNotExists) {
    }

    /** 冻结迁移里所有 {@code ALTER TABLE platform.<ai_table> ADD COLUMN [IF NOT EXISTS] <col>}。 */
    private static List<AppendColumn> appendColumns(Path file, String version) throws IOException {
        String sql = stripComments(Files.readString(file, StandardCharsets.UTF_8));
        List<AppendColumn> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:platform\\s*\\.\\s*)?\"?(ai_[a-z_]+)\"?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        while (m.find()) {
            int semi = sql.indexOf(';', m.end());
            String stmt = semi < 0 ? sql.substring(m.end()) : sql.substring(m.end(), semi);
            Matcher add = Pattern.compile(
                    "ADD\\s+COLUMN(\\s+IF\\s+NOT\\s+EXISTS)?\\s+\"?([a-zA-Z_0-9]+)\"?",
                    Pattern.CASE_INSENSITIVE).matcher(stmt);
            while (add.find()) {
                out.add(new AppendColumn(version, m.group(1).toLowerCase(Locale.ROOT),
                        add.group(2).toLowerCase(Locale.ROOT), add.group(1) != null));
            }
        }
        return out;
    }

    /** 迁移目录里所有 V*.sql，按版本名索引（调用方各自限定扫描面）。 */
    private static Map<String, Path> migrationFiles() throws IOException {
        Path dir = locate(PLATFORM_SQL);
        Map<String, Path> out = new TreeMap<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                Matcher m = Pattern.compile("^(V\\d+)__.*\\.sql$").matcher(p.getFileName().toString());
                if (m.matches()) {
                    out.put(m.group(1), p);
                }
            }
        }
        assertThat(out.keySet())
                .as("必须读到冻结迁移 V7..V13")
                .contains("V7", "V8", "V9", "V10", "V11", "V12", "V13");
        return out;
    }

    /**
     * 冻结窗口 V7..V13 的迁移文件。
     *
     * <p>扫描范围<b>显式限定</b>：原来 {@code Files.list} 遍历整个目录、DisplayName 却声称
     * 只覆盖"冻结迁移 V7..V12"，两者不一致——新迁移会被悄悄卷进冻结判据。
     */
    private static Map<String, Path> frozenMigrationFiles() throws IOException {
        Map<String, Path> out = new TreeMap<>();
        for (Map.Entry<String, Path> e : migrationFiles().entrySet()) {
            if (FROZEN_VERSIONS.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        assertThat(out.keySet())
                .as("冻结窗口必须完整读到：" + new TreeSet<>(FROZEN_VERSIONS))
                .containsExactlyInAnyOrderElementsOf(FROZEN_VERSIONS);
        return out;
    }

    /** V9 之后（版本号 &gt; 9）的迁移文件；它们加给合并表的列必须在 post-V9 分区声明。 */
    private static Map<String, Path> postV9MigrationFiles() throws IOException {
        Map<String, Path> out = new TreeMap<>();
        for (Map.Entry<String, Path> e : migrationFiles().entrySet()) {
            if (versionNumber(e.getKey()) > FROZEN_REGISTRY_VERSION) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static int versionNumber(String version) {
        return Integer.parseInt(version.substring(1));
    }

    /** 未登记的键：判定器的单一实现，负例实证与正式判据都用它，避免"判据和被证对象不同源"。 */
    private static Set<String> unregisteredKeys(Set<String> registered, String table, String column,
                                                boolean ifNotExists) {
        Set<String> out = new TreeSet<>();
        if (!MERGED_TABLES.containsKey(table) || !ifNotExists) {
            return out;
        }
        String key = table + "." + column;
        if (!registered.contains(key)) {
            out.add(key);
        }
        return out;
    }

    /**
     * post-V9 未登记的键：判定器的单一实现。
     *
     * <p><b>刻意不接受 {@code ifNotExists} 参数</b>——旧判定器（{@link #unregisteredKeys}）
     * 的缺陷正是这个参数：裸 {@code ADD COLUMN} 一律不被算作违规，于是"换个写法"就能绕过。
     * 本分区按登记事实判定，与 SQL 写法无关。
     */
    private static Set<String> postV9UnregisteredKeys(Set<String> declared, String table, String column) {
        Set<String> out = new TreeSet<>();
        if (!MERGED_TABLES.containsKey(table)) {
            return out;
        }
        String key = table + "." + column;
        if (!declared.contains(key)) {
            out.add(key);
        }
        return out;
    }

    /** 登记表 post-V9 分区的原始行。 */
    private static List<Map<String, Object>> postV9DeclaredRows() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : registryRoot().path(POST_V9_PARTITION)) {
            Map<String, Object> row = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> row.put(e.getKey(),
                    e.getValue().isNull() ? null : toJava(e.getValue())));
            out.add(row);
        }
        return out;
    }

    private static Set<String> postV9DeclaredKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Map<String, Object> c : postV9DeclaredRows()) {
            keys.add(c.get("table") + "." + c.get("column"));
        }
        return keys;
    }

    private static Set<String> registeredKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Map<String, Object> c : registry()) {
            keys.add(c.get("table") + "." + c.get("column"));
        }
        return keys;
    }

    private static JsonNode registryRoot() throws IOException {
        try (InputStream in = P1MergedAppendColumnRegistryGuardTest.class
                .getResourceAsStream(REGISTRY_RESOURCE)) {
            assertThat(in)
                    .as("登记表必须在测试类路径上：" + REGISTRY_RESOURCE)
                    .isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    private static List<Map<String, Object>> registry() throws IOException {
        JsonNode root = registryRoot();
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : root.path("columns")) {
            Map<String, Object> row = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> row.put(e.getKey(),
                    e.getValue().isNull() ? null : toJava(e.getValue())));
            out.add(row);
        }
        return out;
    }

    private static Object toJava(JsonNode n) {
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isInt() || n.isLong()) {
            return n.intValue();
        }
        return n.asText();
    }

    private static JsonNode counts() throws IOException {
        return registryRoot().path("counts");
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*--.*$", "");
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
        throw new IllegalStateException("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }

    /** 保留：显式暴露登记表覆盖的表集合，避免将来有人把表集合改小后判据静默变松。 */
    @Test
    @DisplayName("登记表覆盖的表集合必须恰好是 6 张合并表")
    void coveredTablesAreExactlyTheMergedOnes() throws IOException {
        Set<String> covered = new LinkedHashSet<>();
        for (Map<String, Object> c : registry()) {
            covered.add((String) c.get("table"));
        }
        assertThat(covered).isEqualTo(new TreeSet<>(MERGED_TABLES.keySet()));
        assertThat(MERGED_TABLES).hasSize(6);
    }
}
