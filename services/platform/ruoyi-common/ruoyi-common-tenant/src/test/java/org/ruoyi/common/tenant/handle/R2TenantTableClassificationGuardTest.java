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

package org.ruoyi.common.tenant.handle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.tenant.handle.PlusTenantLineHandler.TableScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-2 验收（三）：三分类本身被钉住。
 *
 * <p>两个目的：
 * <ol>
 *   <li><b>分类是显式决定</b>：第二类（显式允许的系统表）必须是恰好那 4 张——增删都要
 *       改这条判据，从而必须经过评审。第一类必须与 {@code tenant.excludes} 同源。</li>
 *   <li><b>补上一个从未真正落地的调用不变量</b>：{@code ruoyi-admin/application.yml} 第 193 行
 *       声称"凡被 AI 侧 {@code @TableName} 映射且冻结形状无 tenant_id 的表，必须出现在
 *       excludes 里，不变量由 {@code AiEmbeddedTenantExclusionTest} 钉住"——
 *       实测该测试类<b>在仓库里并不存在</b>（全仓 grep 只命中 yml 注释本身）。
 *       本判据用同一口径（扫描冻结迁移的 CREATE TABLE 形状）把这个不变量真正钉住：
 *       fail-closed 的安全性正建立在这条不变量之上——若某张无 {@code tenant_id} 列的表
 *       被归入租户业务表，拦截器会为它生成不存在的列谓词。</li>
 * </ol>
 */
@Tag("dev")
class R2TenantTableClassificationGuardTest {

    /** 第二类：写死在代码里的平台级清单，必须恰好是这 4 张。 */
    private static final Set<String> PLATFORM_LEVEL = new TreeSet<>(List.of(
            "sys_tenant", "sys_oss_config", "ai_flow_trace_run", "ai_flow_trace_node"));

    /** 第一类里额外的（非 excludes 来源的）代码生成器表。 */
    private static final Set<String> CODE_GEN = new TreeSet<>(List.of("gen_table", "gen_table_column"));

    private static final Path PLATFORM_SQL =
            Path.of("services", "platform", "docs", "script", "sql", "postgres");
    private static final Path PLATFORM_MAIN = Path.of("services", "platform");

    private PlusTenantLineHandler handler;

    @BeforeEach
    void setUp() {
        R2TenantTestContext.install(true);
        handler = R2TenantTestContext.handler();
    }

    @Test
    @DisplayName("第二类必须恰好是注册的 4 张平台级表（增删即失败）")
    void platformLevelSetIsExactlyTheRegisteredFour() {
        Set<String> measured = new TreeSet<>();
        for (String candidate : allMappedTables()) {
            if (handler.classify(candidate) == TableScope.PLATFORM_LEVEL) {
                measured.add(candidate);
            }
        }
        assertThat(measured)
                .as("第二类发生变化必须是一次显式决定：请同步更新本判据与 handler 的 javadoc")
                .isEqualTo(PLATFORM_LEVEL);
    }

    @Test
    @DisplayName("第一类与 tenant.excludes 同源，且包含代码生成器表")
    void globalSharedSetMatchesExcludesPlusCodeGen() {
        for (String excluded : R2TenantTestContext.EXCLUDES) {
            if (PLATFORM_LEVEL.contains(excluded)) {
                continue;
            }
            assertThat(handler.classify(excluded))
                    .as("excludes 项 %s 必须归入第一类", excluded)
                    .isEqualTo(TableScope.GLOBAL_SHARED);
        }
        for (String codeGen : CODE_GEN) {
            assertThat(handler.classify(codeGen)).isEqualTo(TableScope.GLOBAL_SHARED);
        }
    }

    @Test
    @DisplayName("R-2-R2：代码允许清单不得超出声明的共享表集合（漂移即失败）")
    void codeAllowlistNeverExceedsTheDeclaredSharedSet() throws IOException {
        Set<String> nonBusiness = new TreeSet<>();
        for (String mapped : allMappedTables()) {
            if (handler.classify(mapped) != TableScope.TENANT_BUSINESS) {
                nonBusiness.add(mapped);
            }
        }
        Set<String> declared = new TreeSet<>(R2TenantTestContext.EXCLUDES);
        declared.addAll(CODE_GEN);

        assertThat(nonBusiness)
                .as("被映射且免过滤的表必须都在声明的共享表集合里；多出来的就是被偷偷放宽的跳过面")
                .isSubsetOf(declared);
    }

    @Test
    @DisplayName("R-2-R2：第二类必须包含在声明的 excludes 里（声明与代码一致）")
    void platformLevelIsDeclaredInExcludes() {
        assertThat(PLATFORM_LEVEL)
                .as("代码里的第二类必须同时出现在 tenant.excludes 声明中")
                .isSubsetOf(new TreeSet<>(R2TenantTestContext.EXCLUDES));
    }

    @Test
    @DisplayName("租户业务表：典型样本归入第三类")
    void representativeBusinessTablesAreThirdClass() {
        for (String table : new String[]{"sys_user", "sys_role", "sys_dept", "sys_config",
                "sys_dict_data", "sys_oper_log", "ai_agent_message", "ai_agent_conversation",
                "ai_rag_trace_run", "ai_biz_change_log", "ai_knowledge_document",
                "ai_knowledge_chunk", "test_leave"}) {
            assertThat(handler.classify(table))
                    .as("%s 是租户业务表", table)
                    .isEqualTo(TableScope.TENANT_BUSINESS);
        }
    }

    @Test
    @DisplayName("表名匹配不区分大小写（SQL 解析器可能给出任意大小写）")
    void classificationIsCaseInsensitive() {
        assertThat(handler.classify("SYS_TENANT")).isEqualTo(TableScope.PLATFORM_LEVEL);
        assertThat(handler.classify("Flow_Spel")).isEqualTo(TableScope.GLOBAL_SHARED);
        assertThat(handler.classify("Sys_User")).isEqualTo(TableScope.TENANT_BUSINESS);
    }

    @Test
    @DisplayName("生产配置的 tenant.excludes 不得把租户业务表写成共享表（配置不能悄悄放宽过滤）")
    void applicationYmlExcludesDoNotWeakenTenantBusinessTables() throws IOException {
        Set<String> configured = applicationYmlExcludes();

        assertThat(configured)
                .as("ruoyi-admin/application.yml 的 tenant.excludes 发生变化必须是一次显式决定："
                        + "新增一项就等于让那张表不再受限")
                .containsExactlyInAnyOrderElementsOf(new TreeSet<>(R2TenantTestContext.EXCLUDES));

        assertThat(configured)
                .as("租户业务表被写进 excludes 会让它绕过限域与 fail-closed")
                .doesNotContain("sys_user", "sys_role", "sys_dept", "sys_config", "sys_dict_data",
                        "sys_oper_log", "ai_agent_message", "ai_agent_conversation",
                        "ai_rag_trace_run", "ai_biz_change_log", "ai_knowledge_document",
                        "ai_knowledge_chunk", "test_leave");

        for (String excluded : configured) {
            assertThat(handler.classify(excluded))
                    .as("excludes 项 %s 必须被归入非租户业务表", excluded)
                    .isNotEqualTo(TableScope.TENANT_BUSINESS);
        }
    }

    @Test
    @DisplayName("不变量：被平台实体映射、且冻结 DDL 无 tenant_id 列的表，不得归入租户业务表")
    void everyMappedTableWithoutTenantColumnIsNotBusiness() throws IOException {
        Map<String, Set<String>> shape = frozenShape();
        assertThat(shape)
                .as("冻结形状解析器不得静默读空")
                .hasSizeGreaterThan(100);

        List<String> violations = new ArrayList<>();
        List<String> notInDdl = new ArrayList<>();
        for (String table : allMappedTables()) {
            if (PLATFORM_LEVEL.contains(table)) {
                continue;
            }
            Set<String> columns = shape.get(table);
            if (columns == null) {
                notInDdl.add(table);
                continue;
            }
            if (!columns.contains("tenant_id") && handler.classify(table) == TableScope.TENANT_BUSINESS) {
                violations.add(table);
            }
        }

        assertThat(violations)
                .as("这些表没有 tenant_id 列却被当成租户业务表：拦截器会生成不存在的列谓词。"
                        + "必须加入 tenant.excludes（并同步 handler 的第一类清单）")
                .isEmpty();

        // 不在冻结 DDL 里的映射表：不得是"有实体、会被查询、却会被拦"的形态，
        // 这里只登记结果，具体表名由判据输出固定下来（当前为 3 张：见 R-2 报告）。
        assertThat(notInDdl)
                .as("新出现的不在冻结 DDL 内的映射表需要人工确认其租户语义")
                .containsExactlyInAnyOrderElementsOf(expectedNotInDdl());
    }

    /**
     * 实测结果：见 R-2 报告 §表分类。修改此集合等于承认一张新表的租户语义。
     *
     * <p>扫描范围刻意限定 {@code src/main/java}（生产引用）。常见的"第三张" {@code ai_x}
     * 实测只出现在 {@code P1UnifiedTableNameGuardTest} 的<b>注释</b>里，不是生产实体，
     * 因此不应出现在本集合——把它列进来会让判据与实测不符。
     */
    private static Set<String> expectedNotInDdl() {
        return new TreeSet<>(List.of("adi_user", "test_leave"));
    }

    // ------------------------------------------------------------------ helpers

    private static final Path ADMIN_YML = Path.of("services", "platform", "ruoyi-admin",
            "src", "main", "resources", "application.yml");

    /**
     * 从生产配置里读出 {@code tenant.excludes}。刻意用逐行解析而不是断言固定字符串：
     * 注释与空行在真实文件里是交错的（那正是"grep + head 截断"踩过的坑）。
     */
    private static Set<String> applicationYmlExcludes() throws IOException {
        Path yml = locateFile(ADMIN_YML);
        List<String> lines = Files.readAllLines(yml, StandardCharsets.UTF_8);
        Set<String> excludes = new LinkedHashSet<>();
        boolean inTenant = false;
        boolean inExcludes = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inTenant) {
                if (trimmed.equals("tenant:")) {
                    inTenant = true;
                }
                continue;
            }
            if (!inExcludes) {
                if (trimmed.equals("excludes:")) {
                    inExcludes = true;
                    continue;
                }
                // tenant 段里的其它键（enable 等）或下一段
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.contains(":")
                        && !line.startsWith(" ")) {
                    break;
                }
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !line.startsWith(" ")
                        && !trimmed.startsWith("-")) {
                    break;
                }
                continue;
            }
            if (trimmed.startsWith("- ")) {
                excludes.add(trimmed.substring(2).trim());
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // 回到顶层的下一个配置段
            break;
        }
        return excludes;
    }

    /** 平台生产源码里出现的全部 @TableName。 */
    private static Set<String> allMappedTables() throws UnsupportedOperationException {
        Path root = locate(PLATFORM_MAIN);
        Set<String> tables = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("@TableName\\((?:value\\s*=\\s*)?\"([a-zA-Z0-9_]+)\"");
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    tables.add(matcher.group(1).toLowerCase());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法扫描平台实体", e);
        }
        return tables;
    }

    /** 冻结迁移里每张物理表的列集合（CREATE TABLE + ALTER TABLE ADD COLUMN，后写覆盖前写）。 */
    private static Map<String, Set<String>> frozenShape() throws IOException {
        Path dir = locate(PLATFORM_SQL);
        Map<String, Set<String>> shape = new HashMap<>();
        Pattern create = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:platform\\.)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\((.*?)\\)\\s*;",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern alter = Pattern.compile(
                "ALTER\\s+TABLE\\s+(?:platform\\.)?([a-zA-Z_][a-zA-Z0-9_]*)\\s+ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?",
                Pattern.CASE_INSENSITIVE);
        Pattern column = Pattern.compile("^\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?\\s+");

        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream
                    .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted()
                    .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher createMatcher = create.matcher(text);
                while (createMatcher.find()) {
                    String table = createMatcher.group(1).toLowerCase();
                    Set<String> columns = shape.computeIfAbsent(table, key -> new LinkedHashSet<>());
                    for (String line : createMatcher.group(2).split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("--")
                                || trimmed.toUpperCase().startsWith("CONSTRAINT")) {
                            continue;
                        }
                        Matcher columnMatcher = column.matcher(trimmed);
                        if (columnMatcher.find()) {
                            columns.add(columnMatcher.group(1).toLowerCase());
                        }
                    }
                }
                Matcher alterMatcher = alter.matcher(text);
                while (alterMatcher.find()) {
                    shape.computeIfAbsent(alterMatcher.group(1).toLowerCase(), key -> new LinkedHashSet<>())
                            .add(alterMatcher.group(2).toLowerCase());
                }
            }
        }
        return shape;
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

    /** 同 {@link #locate}，但接受普通文件。 */
    private static Path locateFile(Path relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate file " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
