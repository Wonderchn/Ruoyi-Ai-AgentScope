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
 * WP-026C 源码护栏：平台实体映射到的统一列与类型必须与冻结迁移（V7..V11）一致。
 *
 * <p><b>为什么需要它。</b>WP-026 的规格曾写"其余平台实体（Agent、ChatProvider、ChatModel、
 * Mcp*、ShortDrama*、Workflow*）目标表有 {@code VARCHAR(20)} 主键，实体仍是 {@code Long id}"。
 * 这句话当时没有对着 DDL 核过。实际解析冻结迁移后：只有 V7 形状的四张表
 * （{@code ai_conversation} / {@code ai_message} / {@code ai_agent_profile}，以及知识域三张）
 * 是 {@code VARCHAR} 主键；{@code ai_model} / {@code ai_model_provider} / {@code ai_mcp_*} /
 * {@code ai_short_drama_*} / {@code ai_flow_*} / {@code ai_model_config} / {@code ai_flow_trace_*}
 * 全部是 {@code bigint}（V9 由旧 MySQL DDL 转换，类型是旧库事实）。
 * 照那句规格"对齐主键类型"会把二十来个本来正确的 {@code Long id} 改错。
 *
 * <p>所以本护栏钉两件事：
 * <ol>
 *   <li><b>主键 Java 类型必须与 DDL 主键类型相容</b>——这正是错误前提会撞上的判据；</li>
 *   <li><b>实体映射的列必须真的存在于冻结形状</b>——列名写错在编译期同样无感。</li>
 * </ol>
 * 另外把"数值 Java 字段映射到字符串 DDL 列"的既有事实登记成一份显式清单：
 * 这类组合 PostgreSQL 在赋值上下文里会经 I/O 转换接受（写入成功、存成十进制文本），
 * 但<b>读回</b>时 {@code getLong} 对 {@code ''} 或非数值旧值会抛错（WP-026C 便携 PG 实测）。
 * 登记表让"新增一处"必须显式过一遍，而不是靠某次评审记得。
 */
@Tag("dev")
class P1PlatformEntityShapeGuardTest {

    private static final Path MODULES = Path.of("services", "platform");
    private static final Path PLATFORM_SQL = Path.of("services", "platform", "docs", "script", "sql", "postgres");

    /**
     * 基类列登记表：实体自己声明 id 与业务列，审计列来自基类。
     * 键是<b>全限定名</b>——仓库里有两个 {@code BaseEntity}，按简单名解析会撞车
     * （{@code org.ruoyi.common.chat.entity.BaseEntity} 有 {@code is_deleted}，
     * {@code org.ruoyi.common.mybatis.core.domain.BaseEntity} 有 {@code create_by}/{@code update_by}）。
     * 出现未登记的父类时本测试<b>响亮失败</b>，不静默跳过。
     */
    private static final Map<String, Map<String, String>> BASE_CLASS_COLUMNS = Map.of(
            "org.ruoyi.common.chat.entity.BaseEntity", Map.of(
                    "id", "Long",
                    "create_time", "LocalDateTime",
                    "update_time", "LocalDateTime",
                    "is_deleted", "Boolean"),
            "org.ruoyi.common.mybatis.core.domain.BaseEntity", Map.of(
                    "create_dept", "Long",
                    "create_by", "Long",
                    "create_time", "Date",
                    "update_by", "Long",
                    "update_time", "Date"),
            "org.ruoyi.common.tenant.core.TenantEntity", Map.of(
                    "create_dept", "Long",
                    "create_by", "Long",
                    "create_time", "Date",
                    "update_by", "Long",
                    "update_time", "Date",
                    "tenant_id", "String"));

    // All registered legacy numeric/string and deferred-PK risks belonged to the retired modules.
    // Exact equality below still rejects any new mismatch in the retained entity inventory.
    private static final Set<String> NUMERIC_ON_STRING_REGISTERED = Set.of();
    private static final Set<String> PK_TYPE_DEFERRED = Set.of();

    /** 具名主键类型相容表（Java 简单类型名 → 允许的 DDL 基础类型）。 */
    private static final Map<String, Set<String>> TYPE_COMPAT = Map.of(
            "String", Set.of("varchar", "character varying", "char", "character", "text", "uuid",
                    "jsonb", "json"),
            "Long", Set.of("bigint", "integer", "smallint", "numeric", "decimal"),
            "Integer", Set.of("integer", "bigint", "smallint", "numeric", "decimal"),
            "Boolean", Set.of("smallint", "boolean", "integer", "char"),
            "BigDecimal", Set.of("numeric", "decimal", "double precision", "real", "bigint", "integer"),
            "Date", Set.of("timestamp", "timestamp without time zone", "timestamptz", "date",
                    "timestamp with time zone"),
            "LocalDateTime", Set.of("timestamp", "timestamp without time zone", "timestamptz", "date",
                    "timestamp with time zone"),
            "LocalDate", Set.of("date", "timestamp", "timestamp without time zone"));

    private static final Set<String> STRING_DDL_TYPES = Set.of("varchar", "character varying", "char",
            "character", "text");

    private static final Set<String> NUMERIC_JAVA_TYPES = Set.of("Long", "Integer", "Short", "BigInteger");

    // ------------------------------------------------------------------ 判据

    @Test
    @DisplayName("平台实体的 @TableId Java 类型必须与冻结迁移的 DDL 主键类型相容")
    void entityPrimaryKeyTypeMatchesTheFrozenShard() throws IOException {
        Map<String, Map<String, String>> shape = frozenShape();
        Map<String, String> pkTypes = frozenPrimaryKeyTypes(shape);
        List<String> mismatches = new ArrayList<>();
        Set<String> deferredFound = new TreeSet<>();
        Map<String, String> checked = new TreeMap<>();
        for (Entity e : entities()) {
            Map<String, String> cols = shape.get(e.table());
            if (cols == null) {
                mismatches.add(e.name() + " -> " + e.table() + ": 冻结迁移里没有这张表");
                continue;
            }
            if (e.idJavaType() == null) {
                mismatches.add(e.name() + " -> " + e.table() + ": 既没有 @TableId/id 字段，父类也没登记 id");
                continue;
            }
            String pkCol = pkTypes.getOrDefault(e.table(), "id");
            String ddlType = cols.get(pkCol);
            if (ddlType == null) {
                mismatches.add(e.name() + " -> " + e.table() + ": 主键列 " + pkCol + " 不在冻结形状里");
                continue;
            }
            String javaBase = simpleType(e.idJavaType());
            Set<String> allowed = TYPE_COMPAT.get(javaBase);
            if (allowed == null) {
                mismatches.add(e.name() + " -> " + e.table() + ": 未登记的主键 Java 类型 " + e.idJavaType());
                continue;
            }
            if (!allowed.contains(sqlBaseType(ddlType))) {
                String key = e.name() + " -> " + e.table() + "." + pkCol;
                if (PK_TYPE_DEFERRED.contains(key)) {
                    deferredFound.add(key);
                } else {
                    mismatches.add(key + ": @TableId " + e.idJavaType() + " 与 DDL " + ddlType + " 不相容");
                }
                continue;
            }
            checked.put(e.name(), e.table() + "." + pkCol + " : " + e.idJavaType() + " / " + ddlType);
        }
        assertThat(mismatches).as("@TableId 类型与冻结 DDL 不一致").isEmpty();
        assertThat(checked)
                .as("fixed retained entity inventory after legacy module retirement; no empty or partial scan")
                .containsOnlyKeys(
                        "AgentContextCompactionDO", "AgentConversationDO", "AgentMemoryDO",
                        "AgentMemoryExtractionDO", "AgentMessageDO", "AgentProfileDO",
                        "AgentPromptDO", "AgentSkillDO", "BizChangeLogDO",
                        "ChatConfig", "ChatMessage", "ChatModel",
                        "ConversationDO", "ConversationMessageDO", "ConversationSummaryDO",
                        "IngestionPipelineDO", "IngestionPipelineNodeDO", "IngestionTaskDO",
                        "IngestionTaskNodeDO", "IntentNodeDO", "KnowledgeBaseDO",
                        "KnowledgeChunkDO", "KnowledgeDocumentChunkLogDO", "KnowledgeDocumentDO",
                        "KnowledgeDocumentScheduleDO", "KnowledgeDocumentScheduleExecDO", "MessageFeedbackDO",
                        "QueryTermMappingDO", "RagTraceNodeDO", "RagTraceRunDO",
                        "SampleQuestionDO", "TraceNode", "TraceRun",
                        "UserDO");
        assertThat(checked.get("AgentProfileDO"))
                .as("the retained agent profile keeps the unified varchar primary key")
                .isEqualTo("ai_agent_profile.id : String / varchar");
        assertThat(checked.get("ChatModel"))
                .as("the retained model keeps the legacy bigint primary key")
                .isEqualTo("ai_model.id : Long / bigint");
        assertThat(shape.get("ai_model_provider").get("id"))
                .as("retiring ChatProvider does not rewrite its frozen bigint schema")
                .isEqualTo("bigint");
        assertThat(deferredFound)
                .as("已登记的推迟项必须仍然命中；修好之后要把它从 PK_TYPE_DEFERRED 删掉，不能留着掩盖回归")
                .isEqualTo(new TreeSet<>(PK_TYPE_DEFERRED));
    }

    @Test
    @DisplayName("平台实体映射的每一列都必须存在于冻结迁移 V7..V11 的形状里")
    void everyMappedColumnExistsInTheFrozenShape() throws IOException {
        Map<String, Map<String, String>> shape = frozenShape();
        // 正锚点：动态 DO 块追加的列（V7 的 source_*）必须被识别，否则会产生假阳性
        assertThat(shape.get("ai_agent_memory"))
                .as("V7 用 EXECUTE format(ALTER TABLE ... ADD COLUMN source_refs ...) 动态追加列")
                .containsKeys("source_refs", "source_policy_version", "source_acl_version");
        assertThat(shape.get("ai_agent_profile"))
                .as("V9 给 V7 建的 ai_agent_profile 追加旧 MySQL 独有列")
                .containsKeys("agent_name", "system_prompt", "model_id", "tenant_id");

        List<String> unknown = new ArrayList<>();
        for (Entity e : entities()) {
            Map<String, String> cols = shape.get(e.table());
            if (cols == null) {
                continue; // 上一判据已经报过
            }
            for (Map.Entry<String, String> f : mappedColumns(e).entrySet()) {
                if (!cols.containsKey(f.getKey())) {
                    unknown.add(e.name() + " -> " + e.table() + "." + f.getKey() + " (" + f.getValue() + ")");
                }
            }
        }
        assertThat(unknown).as("实体映射了冻结形状里不存在的列").isEmpty();
    }

    /**
     * WP-039A：**同一张表上的"孪生列"**——旧列被保留，同时登记了一个 identity 别名指向统一列。
     *
     * <p>为什么需要这一条：{@link #everyMappedColumnExistsInTheFrozenShape()} 只检查"列存在"，
     * 而孪生列的**旧列也是真实存在的列**，所以它必然漏掉这一类缺陷。WP-038A 就是这样：
     * 平台实体 {@code ChatSession.sessionTitle} 没有 {@code @TableField}，按驼峰约定落到
     * {@code session_title}（旧列，存在！），而统一读路径读 {@code title}——
     * 于是"新增会话插入失败 + 改名看起来没生效"，而列存在性判据一路绿灯。
     *
     * <p>标记就在 V11 的列登记里：{@code identity} 且**源列 ≠ 目标列**的行，
     * 恰好就是"旧列 → 统一列"的孪生对。本条判据据此要求：任何实体字段都不得解析到孪生对的
     * **旧列**；确实需要用旧列的，必须在 {@link #LEGACY_TWIN_USES} 里写明理由。
     */
    private static final Set<String> LEGACY_TWIN_USES = Set.of();

    /**
     * WP-039C：孪生对的**完整期望集合**——全库只有这两对。
     *
     * <p>为什么要断言"完整"而不是只断言"实体没用到旧列"：后者只在**已有实体恰好命中**时才失败。
     * 将来某条迁移新增一个 identity 别名，而暂时没有实体字段命中它时，判据会静默通过——
     * 等到有人照着字段名写实体时才炸。断言完整集合之后，"新增孪生对"本身必须是一次显式决定。
     *
     * <p>这个集合是**实测**出来的：扫描 V7.. 全部迁移的列登记，取 {@code identity} 且
     * 源列 ≠ 目标列的行，得到 2 条。其余 kind（{@code cast_text} 68 行等）源列与目标列同名，
     * 不构成孪生；另有 3 条形如 {@code RUNNING -> RETRY_WAIT} 的行是**状态取值**映射而非列映射。
     */
    private static final Map<String, Map<String, String>> EXPECTED_LEGACY_TWINS = Map.of(
            "ai_conversation", Map.of("session_title", "title"),
            "ai_agent_profile", Map.of("agent_name", "name"));

    @Test
    @DisplayName("孪生对的集合必须恰好是已登记的两对：新增别名必须是一次显式决定")
    void legacyTwinSetIsExactlyTheRegisteredPairs() throws IOException {
        assertThat(legacyTwins())
                .as("列登记里出现了新的『旧列 → 统一列』identity 别名，或已有的消失了。"
                        + "新增别名会让照字段名写的实体静默落到旧列（WP-038A/WP-039A 那一类缺陷），"
                        + "所以必须显式处理：确认新的统一列后更新 EXPECTED_LEGACY_TWINS，"
                        + "并检查是否已有实体字段命中旧列。")
                .isEqualTo(EXPECTED_LEGACY_TWINS);
    }

    @Test
    @DisplayName("平台独有的会话列没有统一孪生：session_content / remark 不构成孪生对")
    void platformOnlyConversationColumnsHaveNoTwin() throws IOException {
        Map<String, String> conversationTwins = legacyTwins().getOrDefault("ai_conversation", Map.of());
        // 这两列是 V9 追加的"平台侧独有列"，统一表里**没有**与之同义的另一列，
        // 所以不存在"写错列"这一类风险；它们也不该出现在孪生表里。
        assertThat(conversationTwins)
                .as("session_content / remark 是平台独有列（V9 追加），没有统一孪生；"
                        + "若将来给它们加了统一列，本条会失败——那时必须像 title/name 一样显式映射")
                .doesNotContainKeys("session_content", "remark");
        assertThat(conversationTwins).containsOnlyKeys("session_title");
    }

    @Test
    @DisplayName("实体字段不得解析到孪生列的旧列（WP-038A 那一类缺陷）")
    void noEntityFieldResolvesToALegacyTwinColumn() throws IOException {
        Map<String, Map<String, String>> twins = legacyTwins();
        // 正锚点：解析器必须真的读到登记里的孪生对，否则本条判据恒真
        assertThat(twins)
                .as("V11 列登记里的 identity 别名（源列≠目标列）就是孪生对；读空会让本条判据失去意义")
                .containsKeys("ai_conversation", "ai_agent_profile");
        assertThat(twins.get("ai_conversation"))
                .as("会话表：旧列 session_title 的孪生是统一列 title")
                .containsEntry("session_title", "title");
        assertThat(twins.get("ai_agent_profile"))
                .as("智能体表：旧列 agent_name 的孪生是统一列 name")
                .containsEntry("agent_name", "name");

        List<String> offenders = new ArrayList<>();
        Set<String> found = new TreeSet<>();
        for (Entity e : entities()) {
            Map<String, String> tableTwins = twins.get(e.table());
            if (tableTwins == null) {
                continue;
            }
            for (Map.Entry<String, String> f : resolvedColumns(e).entrySet()) {
                String canonical = tableTwins.get(f.getKey());
                if (canonical == null) {
                    continue;
                }
                String key = e.name() + "#" + f.getKey();
                if (!LEGACY_TWIN_USES.contains(key)) {
                    offenders.add(key + " -> " + e.table() + "." + f.getKey()
                            + "（旧列）而统一读路径用 " + e.table() + "." + canonical
                            + "：字段名按驼峰约定落到旧列了。加 @TableField(\"" + canonical + "\")，"
                            + "或若确实要用旧列，写进 LEGACY_TWIN_USES 并说明理由");
                }
                found.add(key);
            }
        }
        assertThat(offenders)
                .as("实体字段解析到了孪生列的旧列：写入的是一个没人读的列，"
                        + "而统一列若是 NOT NULL 且无默认值，插入还会直接失败")
                .isEmpty();
        assertThat(found).as("登记表里不应有已失效的条目").isEqualTo(new TreeSet<>(LEGACY_TWIN_USES));
    }

    @Test
    @DisplayName("数值 Java 字段映射到字符串 DDL 列的既有事实必须逐条登记")
    void numericFieldsOnStringColumnsStayRegistered() throws IOException {
        Map<String, Map<String, String>> shape = frozenShape();
        Set<String> found = new TreeSet<>();
        for (Entity e : entities()) {
            Map<String, String> cols = shape.get(e.table());
            if (cols == null) {
                continue;
            }
            for (Map.Entry<String, String> f : resolvedColumns(e).entrySet()) {
                String ddl = cols.get(f.getKey());
                if (ddl == null) {
                    continue;
                }
                if (NUMERIC_JAVA_TYPES.contains(f.getValue()) && STRING_DDL_TYPES.contains(sqlBaseType(ddl))) {
                    found.add(e.name() + "#" + f.getKey());
                }
            }
        }
        assertThat(found)
                .as("未登记的『数值 Java 字段 ↔ 字符串 DDL 列』：PostgreSQL 赋值上下文会接受写入，"
                        + "但读回 getLong 对 '' 或非数值旧行抛错。确认这是有意为之后再登记。")
                .isEqualTo(new TreeSet<>(NUMERIC_ON_STRING_REGISTERED));
    }

    /**
     * 负例实证：列判定器必须真的能拒绝一个不存在的列。
     * 没有这一条，{@link #everyMappedColumnExistsInTheFrozenShape()} 可能因为解析器读空而恒真。
     */
    @Test
    @DisplayName("负例实证：冻结形状判定器拒绝不存在的列，且接受真实存在的列")
    void shapeCheckerRejectsWhatIsAbsent() throws IOException {
        Map<String, Map<String, String>> shape = frozenShape();
        Map<String, String> profile = shape.get("ai_agent_profile");
        assertThat(profile).isNotNull();
        assertThat(profile).containsKey("agent_name");
        assertThat(profile).doesNotContainKey("category");
        assertThat(profile).doesNotContainKey("agent_name_typo");
        // V7 的 ai_agent_profile 本身没有 category（那是 ai_model 的列）：这类"看起来该有"的列
        // 正是本护栏要挡的。
        assertThat(shape.get("ai_model")).containsKey("category");
        assertThat(shape.get("ai_conversation")).containsKey("member_id");
        assertThat(shape.get("ai_model_provider")).containsKey("create_by");
    }

    @Test
    @DisplayName("未登记的父类必须响亮失败，而不是让列解析悄悄变少")
    void unknownBaseClassesFailLoudly() throws IOException {
        List<String> unknown = new ArrayList<>();
        for (Entity e : entities()) {
            String parent = e.extendsType();
            if (parent == null) {
                continue; // 无父类：AI 侧 DO，列全在文件内
            }
            String fqn = e.extendsFqn();
            if (!BASE_CLASS_COLUMNS.containsKey(fqn)) {
                unknown.add(e.name() + " extends " + parent + " (" + fqn + ")");
            }
        }
        assertThat(unknown)
                .as("新增父类后必须在 BASE_CLASS_COLUMNS 登记它的列，否则列检查会漏掉审计列")
                .isEmpty();
    }

    /**
     * WP-031B：租户行拦截器会给被映射的表注入 {@code tenant_id}。冻结形状里<b>没有</b>这一列的表
     * 必须出现在 {@code tenant.excludes} 里，否则一旦有租户上下文，查询/写入会注入不存在的列而直接报错。
     *
     * <p>这条不变量此前只靠人工记得（交接材料里写着"注册前还要确认排除 3 张表"）。现在它由
     * 冻结 DDL 与 {@code application.yml} 两个真实来源推导，新增一张无 {@code tenant_id} 的表
     * 而忘了排除就会失败。
     */
    @Test
    @DisplayName("被 AI 实体映射且冻结形状无 tenant_id 的表必须在 tenant.excludes 里")
    void mappedTablesWithoutTenantIdAreTenantExcluded() throws IOException {
        Map<String, Map<String, String>> shape = frozenShape();
        Set<String> excludes = tenantExcludes();
        assertThat(excludes)
                .as("锚点：必须真的读到 tenant.excludes（否则本判据恒真）")
                .contains("sys_menu", "ai_flow_trace_run", "ai_flow_trace_node");
        assertThat(excludes)
                .as("WP-031B 新增的 3 张排除表")
                .contains("ai_legacy_user", "ai_provider_envelope", "ai_provider_spend");

        List<String> missing = new ArrayList<>();
        Set<String> mappedWithoutTenant = new TreeSet<>();
        for (Entity e : entities()) {
            Map<String, String> cols = shape.get(e.table());
            if (cols == null || cols.containsKey("tenant_id")) {
                continue;
            }
            mappedWithoutTenant.add(e.table());
            if (!excludes.contains(e.table())) {
                missing.add(e.name() + " -> " + e.table());
            }
        }
        assertThat(missing)
                .as("这些表被实体映射但没有 tenant_id，也未排除租户过滤 —— 有租户上下文时 SQL 会失败")
                .isEmpty();
        assertThat(mappedWithoutTenant)
                .as("锚点：确实存在『无 tenant_id 但被映射』的表；若集合变空说明判据已失去意义")
                .contains("ai_legacy_user");
    }

    /** 从 ruoyi-admin 的 application.yml 读 {@code tenant.excludes}。 */
    private static Set<String> tenantExcludes() throws IOException {
        Path yml = locateFile(Path.of("services", "platform", "ruoyi-admin", "src", "main", "resources",
                "application.yml"));
        Set<String> out = new LinkedHashSet<>();
        boolean inTenant = false;
        boolean inExcludes = false;
        for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
            String stripped = line.strip();
            if (stripped.startsWith("#") || stripped.isEmpty()) {
                continue;
            }
            if (line.startsWith("tenant:")) {
                inTenant = true;
                inExcludes = false;
                continue;
            }
            if (inTenant && line.startsWith("  excludes:")) {
                inExcludes = true;
                continue;
            }
            if (!inTenant || !inExcludes) {
                continue;
            }
            if (stripped.startsWith("- ")) {
                out.add(stripped.substring(2).strip());
                continue;
            }
            // 回到 tenant 块之外
            if (!line.startsWith("    ")) {
                inTenant = false;
                inExcludes = false;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 解析

    private record Entity(String name, String table, Path file, String idJavaType, String extendsType,
                          String extendsFqn, String body, String header) {
    }

    private static List<Entity> entities() throws IOException {
        Path root = modulesRoot();
        List<Entity> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            // 只看生产源码：测试夹具会自建同名临时表、也会在断言字符串里写 @TableName，
            // 那些不是"实体对统一库的形状声明"。
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return s.contains("/src/main/java/") && !s.contains("/target/");
                    })
                    .sorted().toList();
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher cm = Pattern.compile("\\bclass\\s+(\\w+)([^{]*)\\{").matcher(text);
                while (cm.find()) {
                    int headerStart = Math.max(0, cm.start() - 900);
                    String header = text.substring(headerStart, cm.start());
                    Matcher tn = Pattern.compile("@TableName\\s*\\(\\s*(?:value\\s*=\\s*)?\"(ai_[a-z_]+)\"")
                            .matcher(header);
                    if (!tn.find()) {
                        continue;
                    }
                    String body = braceBody(text, cm.end() - 1);
                    String ext = null;
                    Matcher em = Pattern.compile("extends\\s+([\\w.]+)").matcher(cm.group(2));
                    if (em.find()) {
                        ext = em.group(1);
                    }
                    String extFqn = ext == null ? null : resolveFqn(file, text, ext);
                    String idType = resolveIdType(body, extFqn);
                    out.add(new Entity(cm.group(1), tn.group(1).toLowerCase(Locale.ROOT), file, idType, ext,
                            extFqn, body, header));
                }
            }
        }
        return out;
    }

    /**
     * 主键 Java 类型：先看本类的 {@code @TableId}，再看本类的 {@code id} 字段，
     * 最后落到已登记父类的 {@code id}（例如 {@code Workflow} 自己不声明 id）。
     */
    private static String resolveIdType(String rawBody, String extFqn) {
        String body = stripJavaComments(rawBody);
        Matcher idm = Pattern.compile("@TableId[^;]*?private\\s+([\\w.<>\\[\\]]+)\\s+\\w+", Pattern.DOTALL)
                .matcher(body);
        if (idm.find()) {
            return idm.group(1);
        }
        Matcher own = Pattern.compile("private\\s+([\\w.<>\\[\\]]+)\\s+id\\s*;").matcher(body);
        if (own.find()) {
            return own.group(1);
        }
        if (extFqn != null) {
            Map<String, String> base = BASE_CLASS_COLUMNS.get(extFqn);
            if (base != null) {
                return base.get("id");
            }
        }
        return null;
    }

    /** 去掉块注释与行注释：javadoc 里出现的 `（ai_model.id, category=chat）` 会被字段正则误当成字段。 */
    private static String stripJavaComments(String body) {
        return body.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\n]*$", " ");
    }

    /** 实体文件内自己声明的列（不含父类）。 */
    /**
     * 从 V7.. 的列登记里抽出**孪生列**：`identity` 且源列 != 目标列的行。
     *
     * <p>登记行形状（V11）：
     * `('mysql/ruoyi-ai.sql', '旧会话表', 'ai_conversation', 'session_title', 'title', 'identity', ...)`
     * —— 即 `源库, 源表, 目标表, 源列, 目标列, 类型`。返回 `目标表 -> (旧列 -> 统一列)`。
     */
    private static Map<String, Map<String, String>> legacyTwins() throws IOException {
        Pattern row = Pattern.compile(
                "\\(\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)'");
        Map<String, Map<String, String>> twins = new TreeMap<>();
        try (Stream<Path> files = Files.list(locate(PLATFORM_SQL))) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).toList()) {
                Matcher m = row.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    String targetTable = m.group(3);
                    String sourceColumn = m.group(4);
                    String targetColumn = m.group(5);
                    if ("identity".equals(m.group(6)) && !sourceColumn.equals(targetColumn)) {
                        twins.computeIfAbsent(targetTable, k -> new TreeMap<>()).put(sourceColumn, targetColumn);
                    }
                }
            }
        }
        return twins;
    }
    private static Map<String, String> mappedColumns(Entity e) {
        Map<String, String> cols = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : declaredFields(e.body()).entrySet()) {
            if ("__ID__".equals(f.getKey())) {
                continue;
            }
            cols.put(f.getKey(), f.getValue());
        }
        return cols;
    }

    /** 实体声明的列 + 已登记父类的列。 */
    private static Map<String, String> resolvedColumns(Entity e) {
        Map<String, String> cols = new LinkedHashMap<>();
        if (e.extendsFqn() != null) {
            Map<String, String> base = BASE_CLASS_COLUMNS.get(e.extendsFqn());
            if (base != null) {
                cols.putAll(base);
            }
        }
        cols.putAll(mappedColumns(e));
        return cols;
    }

    /**
     * 解析类体里声明的字段。{@code @TableField(exist = false)} 跳过；显式列名优先，
     * 否则按 MyBatis-Plus 的驼峰转下划线约定。返回 {@code __ID__} 键用于占位判断。
     */
    private static Map<String, String> declaredFields(String rawBody) {
        Map<String, String> fields = new LinkedHashMap<>();
        String body = stripJavaComments(rawBody);
        Matcher m = Pattern.compile("(?<ann>(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*)"
                + "(?<mods>(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*)"
                + "(?<type>[A-Za-z_][\\w.<>,\\[\\]\\s]*?)\\s+(?<name>[a-z]\\w*)\\s*(?:=[^;]+)?;",
                Pattern.DOTALL).matcher(body);
        while (m.find()) {
            String ann = m.group("ann") == null ? "" : m.group("ann");
            String mods = m.group("mods") == null ? "" : m.group("mods");
            if (mods.contains("static") || mods.contains("final")) {
                continue;
            }
            if (ann.replace(" ", "").contains("exist=false")) {
                continue;
            }
            String type = simpleType(m.group("type").trim());
            Matcher tf = Pattern.compile("@TableField\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"")
                    .matcher(ann);
            String col = tf.find() ? tf.group(1).replace("`", "")
                    : camelToSnake(m.group("name"));
            fields.put(col, type);
        }
        return fields;
    }

    /** 冻结迁移里每张表的列集合，含平铺 ALTER 与 DO 块 EXECUTE format 里的动态 ADD COLUMN。 */
    private static Map<String, Map<String, String>> frozenShape() throws IOException {
        Path sqlDir = locate(PLATFORM_SQL);
        Map<String, Map<String, String>> tables = new TreeMap<>();
        // WP-034A：版本上界从目录**推导**（V7 起，按数值排序），不再写死 11。
        // 写死会让"新加的迁移给表加了列"静默逃过列检查——那正是本护栏要防的漂移。
        List<Integer> versions;
        try (Stream<Path> stream = Files.list(sqlDir)) {
            versions = stream.map(p -> p.getFileName().toString())
                    .map(name -> {
                        Matcher m = Pattern.compile("^V(\\d+)__.*\\.sql$").matcher(name);
                        return m.matches() ? Integer.valueOf(m.group(1)) : null;
                    })
                    .filter(v -> v != null && v >= 7)
                    .sorted()
                    .toList();
        }
        assertThat(versions)
                .as("必须读到 V7 起的统一链迁移")
                .isNotEmpty()
                .contains(7, 8, 9, 10, 11);
        for (int version : versions) {
            List<Path> files;
            try (Stream<Path> stream = Files.list(sqlDir)) {
                files = stream.filter(p -> p.getFileName().toString().startsWith("V" + version + "__")).toList();
            }
            assertThat(files).as("缺少冻结迁移 V" + version).isNotEmpty();
            for (Path file : files) {
                String sql = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher ct = Pattern.compile(
                        "CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+(?:platform\\s*\\.\\s*)?\"?(ai_[a-z_]+)\"?\\s*\\(",
                        Pattern.CASE_INSENSITIVE).matcher(sql);
                while (ct.find()) {
                    String body = parenBody(sql, ct.end() - 1);
                    Map<String, String> cols = tables.computeIfAbsent(ct.group(1).toLowerCase(Locale.ROOT),
                            k -> new LinkedHashMap<>());
                    for (String def : splitTopLevel(body)) {
                        String d = def.strip();
                        if (d.isEmpty() || d.startsWith("--")) {
                            continue;
                        }
                        if (d.toUpperCase(Locale.ROOT).startsWith("PRIMARY KEY")) {
                            cols.put("__PK__", String.join(",", identList(d)));
                            continue;
                        }
                        if (d.matches("(?is)^(UNIQUE|FOREIGN|CONSTRAINT|CHECK|KEY|INDEX|EXCLUDE)\\b.*")) {
                            continue;
                        }
                        Matcher col = Pattern.compile("^\"?([a-zA-Z_0-9]+)\"?\\s+(.*)$", Pattern.DOTALL).matcher(d);
                        if (col.find()) {
                            String type = typeOf(col.group(2));
                            if (!type.isEmpty()) {
                                cols.put(col.group(1).toLowerCase(Locale.ROOT), type);
                            }
                        }
                    }
                }
                // 平铺 ALTER TABLE ... ADD COLUMN
                Matcher alt = Pattern.compile("ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:platform\\s*\\.\\s*)?\"?(ai_[a-z_]+)\"?",
                        Pattern.CASE_INSENSITIVE).matcher(sql);
                while (alt.find()) {
                    int semi = sql.indexOf(';', alt.end());
                    String stmt = semi < 0 ? sql.substring(alt.end()) : sql.substring(alt.end(), semi);
                    addColumns(tables, alt.group(1).toLowerCase(Locale.ROOT), stmt);
                }
                // DO 块：FOREACH target_table IN ARRAY ARRAY['a','b'] ... EXECUTE format('ALTER TABLE platform.%I ADD COLUMN x type, ...')
                Matcher doBlock = Pattern.compile(
                        "FOREACH\\s+\\w+\\s+IN\\s+ARRAY\\s+ARRAY\\s*\\[([^\\]]*)\\](.*?)(?:END\\s+LOOP|\\$\\w*\\$\\s*;)",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(sql);
                while (doBlock.find()) {
                    List<String> targets = new ArrayList<>();
                    Matcher t = Pattern.compile("'(ai_[a-z_]+)'").matcher(doBlock.group(1));
                    while (t.find()) {
                        targets.add(t.group(1).toLowerCase(Locale.ROOT));
                    }
                    Matcher exec = Pattern.compile("EXECUTE\\s+format\\s*\\('(.*?)'", Pattern.DOTALL)
                            .matcher(doBlock.group(2));
                    while (exec.find()) {
                        String inner = exec.group(1).replace("''", "'");
                        for (String target : targets) {
                            addColumns(tables, target, inner);
                        }
                    }
                }
            }
        }
        return tables;
    }

    private static void addColumns(Map<String, Map<String, String>> tables, String table, String sqlFragment) {
        Matcher m = Pattern.compile("ADD\\s+COLUMN(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+\"?([a-zA-Z_0-9]+)\"?\\s+([^,;]+)")
                .matcher(sqlFragment);
        while (m.find()) {
            String type = typeOf(m.group(2));
            if (!type.isEmpty()) {
                tables.computeIfAbsent(table, k -> new LinkedHashMap<>())
                        .putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), type);
            }
        }
    }

    private static Map<String, String> frozenPrimaryKeyTypes(Map<String, Map<String, String>> shape) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Map<String, String>> e : shape.entrySet()) {
            String pk = e.getValue().get("__PK__");
            String first = pk == null ? "id" : pk.split(",")[0];
            out.put(e.getKey(), first);
        }
        return out;
    }

    private static final Set<String> CONSTRAINT_WORDS = Set.of("not", "null", "default", "primary",
            "unique", "references", "check", "generated", "collate", "constraint");

    /**
     * 取列类型的基础类型。非贪婪正则在 {@code bigint NOT NULL} 上只会取到 {@code b}
     * （WP-026C 踩过：两份分析脚本都先给出 'b'/'v'/'t' 这种单字符类型），
     * 所以这里按 token 走到第一个约束关键字为止。
     */
    private static String typeOf(String rest) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (String w : rest.trim().split("\\s+")) {
            if (depth == 0 && CONSTRAINT_WORDS.contains(w.toLowerCase(Locale.ROOT).replace(",", ""))) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w);
            depth += count(w, '(') - count(w, ')');
        }
        return sqlBaseType(sb.toString().replace(",", "").trim());
    }

    private static String sqlBaseType(String sqlType) {
        String t = sqlType.trim().toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("^([a-z]+(?:\\s+[a-z]+)*)").matcher(t);
        return m.find() ? m.group(1).trim() : t;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static List<String> identList(String def) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([a-zA-Z_0-9]+)\"").matcher(def);
        while (m.find()) {
            out.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) {
            int open = def.indexOf('(');
            if (open >= 0) {
                for (String p : def.substring(open + 1, def.lastIndexOf(')')).split(",")) {
                    out.add(p.strip().replace("\"", "").toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : body.toCharArray()) {
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String braceBody(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex, i + 1);
                }
            }
        }
        return text.substring(openIndex);
    }

    private static String parenBody(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex + 1, i);
                }
            }
        }
        return text.substring(openIndex + 1);
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*--.*$", "");
    }

    /** 用文件自身的 import + package 解析父类全限定名；按简单名解析会撞上两个 BaseEntity。 */
    private static String resolveFqn(Path file, String text, String type) {
        if (type.contains(".")) {
            return type;
        }
        Matcher im = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE).matcher(text);
        while (im.find()) {
            String fq = im.group(1);
            if (fq.endsWith("." + type)) {
                return fq;
            }
        }
        Matcher pm = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(text);
        return pm.find() ? pm.group(1) + "." + type : type;
    }

    private static String simpleType(String javaType) {
        String t = javaType.replaceAll("<.*>", "").trim();
        int dot = t.lastIndexOf('.');
        return dot < 0 ? t : t.substring(dot + 1);
    }

    private static String camelToSnake(String name) {
        return name.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
    }

    private static Path modulesRoot() {
        return locate(MODULES);
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

    /** 同 {@link #locate}，但接受普通文件（application.yml 这类单文件来源）。 */
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

    /** 保留供以后的判据使用：显式暴露解析到的表数，避免解析器静默读空。 */
    @Test
    @DisplayName("冻结形状解析器必须真的读到表与列（防静默读空）")
    void frozenShapeParserIsNotEmpty() throws IOException {
        Map<String, Map<String, String>> shape = frozenShape();
        Set<String> tables = new TreeSet<>(shape.keySet());
        assertThat(tables)
                .contains("ai_conversation", "ai_message", "ai_agent_profile", "ai_model",
                        "ai_model_provider", "ai_knowledge_base", "ai_flow_workflow", "ai_short_drama_project",
                        "ai_mcp_tool", "ai_model_config", "ai_rag_trace_run", "ai_legacy_user");
        assertThat(shape.size()).isGreaterThanOrEqualTo(40);
        // 动态列
        assertThat(shape.get("ai_agent_state")).containsKeys("source_refs", "source_policy_version");
        // 主键解析：V7 形状是 varchar，V9 的旧 MySQL 形状是 bigint
        assertThat(shape.get("ai_agent_profile")).containsEntry("id", "varchar");
        assertThat(shape.get("ai_model_provider")).containsEntry("id", "bigint");
        assertThat(new LinkedHashSet<>(shape.keySet())).isNotEmpty();
    }
}
