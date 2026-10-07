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

import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;

/**
 * P1.3a 授权域 SQL 隔离验收：逐条断言"没有无租户条件的读写"。
 *
 * <p>判据（05 §4.4）：允许保留全局唯一代理主键，但<b>访问路径恒带租户条件</b>。
 * <ul>
 *   <li>五个授权域 DAO（ai_resource / ai_resource_acl / ai_acl_epoch / ai_execution_permit
 *       / 资源源引用）：全部 SQL 文本逐条核对——SELECT/UPDATE/DELETE 的 WHERE 必须含
 *       {@code tenant_id = :param}，INSERT 的列清单必须含 tenant_id；epoch 表
 *       <b>不存在任何 INSERT</b>（无 epoch 行必须显式拒绝，不允许默认初始化）；</li>
 *   <li>资源源引用的子链查询必须同时带 tenant + parent 双条件；</li>
 *   <li>写服务的 ai_knowledge_base / ai_knowledge_document 语句同样逐条核对；</li>
 *   <li>公共模板域仓库：每条查询显式携带 {@code tenant_id = '__public_template__'}
 *       （保留租户列值，不属于 tenantless 白名单语义）；</li>
 *   <li>knowledge 包三个 service impl 的既有查询路径：租户守卫计数必须覆盖全部
 *       mapper 终结调用；无守卫的语句只能落在<b>逐条写明原因的显式清单</b>里
 *       （DO 无租户字段的遗留 insert / 调度与消费路径），清单收紧或放宽都会让本测试失败。</li>
 * </ul>
 */
@Tag("dev")
class P1ResourceSqlIsolationTest {

    // ------------------------------------------------------------ SQL 语句抽取

    /** DML 起始的字符串字面量（后续拼接片段不得以 DML 关键字开头，这是本抽取器的约定）。 */
    private static final Pattern DML_LITERAL = Pattern.compile(
            "\"\\s*(SELECT|INSERT|UPDATE|DELETE)\\b", Pattern.CASE_INSENSITIVE);

    /** WHERE 中的租户条件：命名参数或 JDBC 占位符。 */
    private static final Pattern TENANT_CONDITION = Pattern.compile(
            "\\btenant_id\\s*=\\s*(:[A-Za-z_][A-Za-z0-9_]*|\\?)", Pattern.CASE_INSENSITIVE);

    /**
     * 抽取一个 Java 源文件里的全部 SQL 语句：从每个 DML 字面量开始，到语句分号为止
     * 的<b>原始源文本</b>（保留 {@code + TEMPLATE_TENANT +} 之类的常量引用可见）。
     */
    private static List<Statement> sqlStatements(String source) {
        List<Statement> out = new ArrayList<>();
        Matcher m = DML_LITERAL.matcher(source);
        while (m.find()) {
            int start = m.start();
            int end = source.indexOf(';', start);
            assertThat(end).as("SQL 语句必须以分号收尾：%s", source.substring(start, Math.min(start + 80, source.length())))
                    .isGreaterThan(start);
            out.add(new Statement(source.substring(start, end + 1)));
        }
        return out;
    }

    private record Statement(String text) {
        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        boolean isSelect() {
            return upper().stripLeading().startsWith("\"SELECT");
        }

        boolean isInsert() {
            return upper().stripLeading().startsWith("\"INSERT");
        }

        boolean isUpdateOrDelete() {
            String u = upper().stripLeading();
            return u.startsWith("\"UPDATE") || u.startsWith("\"DELETE");
        }
    }

    // ------------------------------------------------------------ 通用断言

    /** 单条语句的租户条件断言：SELECT/UPDATE/DELETE 看 WHERE，INSERT 看列清单。 */
    private static void assertTenantGuarded(String file, Statement statement) {
        String text = statement.text();
        assertThat(text.toUpperCase(Locale.ROOT))
                .as("%s：语句必须显式引用 tenant_id（无租户条件的读写不允许存在）", file)
                .contains("TENANT_ID");
        if (statement.isInsert()) {
            Matcher columns = Pattern.compile("INSERT\\s+INTO\\s+(?:\\w+\\.)?\\w+\\s*\\(([^)]*)\\)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
            assertThat(columns.find()).as("%s：INSERT 必须带列清单以便核对租户列", file).isTrue();
            assertThat(columns.group(1).toUpperCase(Locale.ROOT))
                    .as("%s：INSERT 列清单必须包含 tenant_id", file)
                    .contains("TENANT_ID");
            return;
        }
        assertThat(TENANT_CONDITION.matcher(text).find())
                .as("%s：SELECT/UPDATE/DELETE 的 WHERE 必须带 tenant_id 条件：%s", file, text)
                .isTrue();
    }

    private static void assertAllGuarded(String file, List<Statement> statements, int expectedMin) {
        assertThat(statements.size())
                .as("%s：抽取到的 SQL 语句数下限（防止抽取器静默失效变成恒真检查）", file)
                .isGreaterThanOrEqualTo(expectedMin);
        for (Statement statement : statements) {
            assertTenantGuarded(file, statement);
        }
    }

    // ------------------------------------------------------------ 授权域五个 DAO

    @Test
    @DisplayName("五个授权域 DAO：全部 SQL 恒带租户条件，epoch 无任何默认初始化语句")
    void authorizationDaosAreAlwaysTenantGuarded() throws IOException {
        assertAllGuarded("AiResourceMapper",
                sqlStatements(ragMainSource("authorization/dao/AiResourceMapper.java")), 4);
        assertAllGuarded("AiResourceAclMapper",
                sqlStatements(ragMainSource("authorization/dao/AiResourceAclMapper.java")), 4);
        assertAllGuarded("AiExecutionPermitMapper",
                sqlStatements(ragMainSource("authorization/dao/AiExecutionPermitMapper.java")), 4);
        assertAllGuarded("ResourceSourceRefMapper",
                sqlStatements(ragMainSource("authorization/dao/ResourceSourceRefMapper.java")), 1);

        // epoch：读不到就拒绝，永远不允许出现"补一行默认版本"的语句
        List<Statement> epoch = sqlStatements(ragMainSource("authorization/dao/AiAclEpochMapper.java"));
        assertAllGuarded("AiAclEpochMapper", epoch, 2);
        assertThat(epoch).noneMatch(s -> s.isInsert());
        assertThat(epoch.stream().filter(Statement::isSelect).findFirst().orElseThrow().text())
                .as("epoch 读取语句只允许按租户取版本，不允许任何默认值兜底")
                .contains("SELECT version FROM ai_acl_epoch")
                .doesNotContain("COALESCE", "IFNULL");

        // 资源源引用：子链查询必须同时带 tenant + parent 双条件
        Statement child = sqlStatements(ragMainSource("authorization/dao/ResourceSourceRefMapper.java")).get(0);
        assertThat(child.text())
                .contains("parent_type = :parentType")
                .contains("parent_id = :parentId");
    }

    @Test
    @DisplayName("写服务：统一知识表语句逐条带租户条件")
    void writeServiceSqlIsTenantGuarded() throws IOException {
        List<Statement> statements = sqlStatements(ragMainSource("authorization/AiResourceWriteService.java"));
        assertAllGuarded("AiResourceWriteService", statements, 3);

        // 主键写/读都必须带租户：代理主键全局唯一可以保留，但访问路径不允许绕开租户
        assertThat(statements.stream().filter(statement->statement.text().contains("INSERT INTO platform.ai_knowledge_base")).findFirst().orElseThrow().text())
                .as("KB 元数据插入必须显式写 tenant_id / owner_member_id（归属只来自主体）")
                .contains("INSERT INTO platform.ai_knowledge_base")
                .contains("owner_member_id");
    }

    @Test
    @DisplayName("schema限定表名仍严格检查INSERT列清单，列外提及租户不能替代租户列")
    void qualifiedInsertGuardStillRejectsMissingTenantColumn() {
        assertTenantGuarded("qualified", new Statement("\"INSERT INTO platform.ai_knowledge_base (id,tenant_id) VALUES (:id,:tenant)\";"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertTenantGuarded("qualified",
                new Statement("\"INSERT INTO platform.ai_knowledge_base (id) VALUES (:tenant_id)\";")))
                .isInstanceOf(AssertionError.class);
    }

    // ------------------------------------------------------------ 公共模板域

    @Test
    @DisplayName("公共模板域：每条查询显式携带保留租户 __public_template__，不以 NULL 冒充共享")
    void publicTemplateQueriesPinExplicitTemplateTenant() throws IOException {
        List<Statement> statements = sqlStatements(
                systemMainSource("template/PublicTemplateRepository.java"));
        assertThat(statements).hasSize(6);
        for (Statement statement : statements) {
            // 源文本以常量引用携带保留租户值：tenant_id = '<TEMPLATE_TENANT 常量>'，
            // 这证明限定是显式列值等值比较，而不是 NULL 冒充或隐式过滤。
            assertThat(statement.text())
                    .as("模板查询必须以显式列值限定保留租户（tenant_id = '<TEMPLATE_TENANT>'）：%s", statement.text())
                    .contains("tenant_id = '")
                    .contains("TEMPLATE_TENANT");
        }
    }

    // ------------------------------------------------------------ knowledge 包既有查询路径

    /** mapper 终结调用（MyBatis-Plus 侧的读写入口）。 */
    private static final Pattern TERMINAL_CALL = Pattern.compile(
            "(knowledgeBaseMapper|documentMapper|chunkMapper|chunkLogMapper)\\."
                    + "(selectOne|selectList|selectPage|selectCount|selectMaps|selectObjs|selectById"
                    + "|selectByIds|selectBatchIds|insert|update|delete|deleteById|updateById)\\(");

    /** 租户守卫锚点：统一谓词或按租户读取的 helper。 */
    private static final Pattern TENANT_GUARD = Pattern.compile(
            "TENANT_PREDICATE|selectTenantKnowledgeBase\\(|selectTenantKnowledgeDocument\\(|selectTenantChunk\\(");

    /**
     * 允许"租户守卫计数低于 mapper 终结调用数"的方法：只能在"调度/消费路径无执行主体"
     * 时列出，逐条写明原因；清单与实测必须精确相等——收紧（修好了一条）或放宽
     * （新增无守卫语句）都会让本测试失败。遗留 insert 路径由下方
     * {@link #knowledgeInsertPathsArePinned()} 单独钉住。
     */
    private static final Map<String, String> UNGUARDED_ALLOWED = buildAllowed();

    private static Map<String, String> buildAllowed() {
        Map<String, String> allowed = new LinkedHashMap<>();
        allowed.put("KnowledgeBaseServiceImpl#create",
                "遗留 insert 路径：DO 无租户字段；KB 归属写路径由 AiResourceWriteService 承担，"
                        + "V4 NOT NULL 下该路径 fail-closed 不产生未知归属行；读路径已全部租户守卫");
        allowed.put("KnowledgeDocumentServiceImpl#executeChunk",
                "调度/消费路径无执行主体（逐表账已记录该访问路径）；P1.3a 不扩权不放行");
        allowed.put("KnowledgeDocumentServiceImpl#runChunkTask",
                "调度/消费路径无执行主体：KB 元数据读与 chunkLog 写由后续单元接线");
        allowed.put("KnowledgeDocumentServiceImpl#updateChunkLog",
                "调度/消费路径无执行主体：主键回写状态，同上");
        allowed.put("KnowledgeDocumentServiceImpl#markChunkSucceeded",
                "调度/消费路径无执行主体：主键回写状态，同上");
        allowed.put("KnowledgeDocumentServiceImpl#refreshMimeType",
                "调度/消费路径无执行主体：主键回写状态，同上");
        allowed.put("KnowledgeDocumentServiceImpl#markChunkFailed",
                "调度/消费路径无执行主体：主键回写状态，同上");
        return java.util.Collections.unmodifiableMap(allowed);
    }

    /** 遗留 insert 路径：DO 无租户字段的写点逐条列出，新增 insert 必须先修订本清单。 */
    private static final Set<String> INSERT_PATHS_ALLOWED = Set.of(
            "KnowledgeBaseServiceImpl#create",
            "KnowledgeDocumentServiceImpl#upload",
            "KnowledgeDocumentServiceImpl#runChunkTask",
            "KnowledgeChunkServiceImpl#create");

    private static final Pattern MAPPER_INSERT = Pattern.compile(
            "(knowledgeBaseMapper|documentMapper|chunkMapper|chunkLogMapper)\\.insert\\(");

    @Test
    @DisplayName("knowledge 三个 impl：遗留 insert 写点与显式清单精确相等（防止新增无租户 insert）")
    void knowledgeInsertPathsArePinned() throws IOException {
        Set<String> inserts = new java.util.LinkedHashSet<>();
        for (String file : List.of("KnowledgeBaseServiceImpl", "KnowledgeDocumentServiceImpl",
                "KnowledgeChunkServiceImpl")) {
            String source = ragMainSource("knowledge/service/impl/" + file + ".java");
            for (MethodBlock method : methodBlocks(source)) {
                if (MAPPER_INSERT.matcher(method.body()).find()) {
                    inserts.add(file + "#" + method.name());
                }
            }
        }
        assertThat(inserts)
                .as("mapper insert 写点必须与显式清单精确相等（清单内路径为 DO 无租户字段的遗留写，"
                        + "V4 NOT NULL 下 fail-closed）")
                .containsExactlyInAnyOrderElementsOf(INSERT_PATHS_ALLOWED);
    }

    @Test
    @DisplayName("knowledge 三个 impl：mapper 终结调用全部被租户守卫覆盖，无守卫点必须与显式清单精确相等")
    void knowledgeQueryPathsAreTenantGuarded() throws IOException {
        Map<String, String> unguarded = new LinkedHashMap<>();
        for (String file : List.of("KnowledgeBaseServiceImpl", "KnowledgeDocumentServiceImpl",
                "KnowledgeChunkServiceImpl")) {
            String source = ragMainSource("knowledge/service/impl/" + file + ".java");
            for (MethodBlock method : methodBlocks(source)) {
                int terminals = count(TERMINAL_CALL, method.body());
                int guards = count(TENANT_GUARD, method.body());
                if (terminals > guards) {
                    unguarded.put(file + "#" + method.name(),
                            "终结调用 " + terminals + " 处，租户守卫仅 " + guards + " 处");
                }
            }
        }
        assertThat(unguarded.keySet())
                .as("无租户守卫的语句点必须与显式清单精确相等（多出=新增了无守卫路径，"
                        + "缺失=清单已过时，应当收紧）；实测差异：%s", unguarded)
                .containsExactlyInAnyOrderElementsOf(UNGUARDED_ALLOWED.keySet());
    }

    @Test
    @DisplayName("knowledge 三个 impl：租户谓词锚点存在，且守卫数量不低于 mapper 终结调用总数")
    void knowledgeGuardAnchorsExist() throws IOException {
        for (String file : List.of("KnowledgeBaseServiceImpl", "KnowledgeDocumentServiceImpl",
                "KnowledgeChunkServiceImpl")) {
            String source = ragMainSource("knowledge/service/impl/" + file + ".java");
            assertThat(source).contains("private static final String TENANT_PREDICATE");
            int terminals = count(TERMINAL_CALL, source);
            int guards = count(TENANT_GUARD, source);
            assertThat(guards)
                    .as("%s：租户守卫锚点数必须不少于 mapper 终结调用数（守卫不足的部分只允许落在显式清单内）", file)
                    .isGreaterThanOrEqualTo(terminals - unguardedAllowance(file));
        }
    }

    private static int unguardedAllowance(String file) {
        return (int) UNGUARDED_ALLOWED.keySet().stream().filter(k -> k.startsWith(file + "#")).count();
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------ 方法块扫描

    private record MethodBlock(String name, String body) {
    }

    /**
     * 按括号配对切出类的成员方法块（depth 1→2 的块；跳过字符串/字符字面量与注释，
     * 防止注解表达式里的 {@code {{#...}}} 干扰配对）。
     */
    private static List<MethodBlock> methodBlocks(String source) {
        List<MethodBlock> blocks = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int memberStart = -1;
        int headerStart = 0;
        String pendingName = null;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '{') {
                depth++;
                if (depth == 2 && memberStart < 0) {
                    memberStart = i;
                    pendingName = methodName(source, headerStart, i);
                }
                continue;
            }
            if (c == '}') {
                if (depth == 2 && memberStart >= 0) {
                    blocks.add(new MethodBlock(pendingName, source.substring(memberStart + 1, i)));
                    memberStart = -1;
                    pendingName = null;
                }
                depth--;
                if (depth == 0) {
                    // 类体结束（或嵌套类型闭合），后续成员的 header 从这里起算
                    headerStart = i + 1;
                }
                continue;
            }
            if (c == ';' && depth == 1 && memberStart < 0) {
                // 字段声明结束：下一个成员的 header 从这里起算
                headerStart = i + 1;
            }
        }
        return blocks;
    }

    /** 从成员声明头（注解 + 签名）里取最后一个 word( 作为方法名。 */
    private static String methodName(String source, int from, int braceIndex) {
        String header = source.substring(Math.max(0, from), braceIndex);
        Matcher m = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\(").matcher(header);
        String name = null;
        while (m.find()) {
            name = m.group(1);
        }
        return name == null ? "<unknown>" : name;
    }

    // ------------------------------------------------------------ 文件定位

    /** platform 的 rag/runtime 主源码，授权类在 D1 后归 runtime。 */
    private static String ragMainSource(String subPath) throws IOException {
        return Files.readString(resolve(subPath, subPath.startsWith("authorization/") ? "runtime" : "rag"), StandardCharsets.UTF_8);
    }

    /** 原 system 公共模板现归 platform runtime。 */
    private static String systemMainSource(String subPath) throws IOException {
        return Files.readString(resolve(subPath, "runtime"), StandardCharsets.UTF_8);
    }

    private static Path resolve(String subPath, String module) {
        String cleaned = subPath.replace('\\', '/');
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("services").resolve("platform").resolve("ruoyi-modules")
                    .resolve("ruoyi-ai-" + module)
                    .resolve("src").resolve("main").resolve("java")
                    .resolve("com").resolve("nageoffer").resolve("ai").resolve("ragent")
                    .resolve(cleaned.replace('/', File.separatorChar));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate " + module + " main source: " + subPath);
    }
}
