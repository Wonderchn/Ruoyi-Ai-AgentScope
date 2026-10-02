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

package com.nageoffer.ai.ragent.boundary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.1b 合成 fixture 契约护栏。
 *
 * <p>本测试<b>不连接任何数据库</b>：它验证的是"合成输入是否真的覆盖了两租户所有正反例"，
 * 以及 fixture 规范 / AI 域 SQL / platform 域 SQL 三者是否互相一致。
 *
 * <p>为什么需要它：P1 的隔离验收必须建立在"同名不同身份、无 ACL、显式 tenant_all、
 * tombstone、未知归属、legacy token"这些<b>具体输入</b>之上。如果这些输入缺失或漂移，
 * 后面的负例断言就会变成空转（"什么都没命中"也会看起来像"拒绝成功"），
 * 而空转的拒绝证据是最容易被误当成隔离通过的东西。
 *
 * <p>反过来说：本测试通过<b>只</b>说明合成 fixture 齐备，<b>不</b>构成"无存量"证明，
 * 也不构成任何运行时的隔离通过。C6 存量结论仍须负责人依据给出。
 */
class P1FixtureContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * fixture 规范的唯一权威位置。
     *
     * <p>公开仓库中的纯合成输入由测试和生成器共同读取，不依赖本机私人规划副本。
     */
    private static final String SPEC_RELATIVE = "tools/p1-fixtures/p1-fixture-spec.json";

    /** AI 域合成 SQL（本模块测试资源，编译后随 classpath 提供）。 */
    private static final String AI_SQL_RESOURCE = "p1/resources.sql";

    /** platform 域合成 SQL 的仓库相对路径；不在本模块 classpath 上，按文件系统读取。 */
    private static final String PLATFORM_SQL_RELATIVE =
            "services/platform/ruoyi-modules/ruoyi-ai-integration/src/test/resources/p1/tenants.sql";

    /** 05 §4.2 的 P1 开放动作与对应 platform permission（契约表，不允许悄悄改）。 */
    private static final List<String> EXPECTED_PERMISSIONS = List.of(
            "ai:kb:list", "ai:kb:write", "ai:kb:read", "ai:kb:delete", "ai:kb:acl", "ai:kb:retrieve",
            "ai:document:read", "ai:document:download", "ai:conversation:read", "ai:conversation:export",
            "ai:memory:read", "ai:run:read", "ai:run:event:read");

    // ------------------------------------------------------------------ 规范本身

    @Test
    @DisplayName("fixture 规范存在、可解析，且声明的版本与基线固定")
    void specExistsAndDeclaresBaseline() throws IOException {
        JsonNode spec = readSpec();
        assertEquals("P1-FIXTURE-v1", spec.path("manifestVersion").asText());
        assertEquals("P1-FULL-v1", spec.path("specVersion").asText());
        assertEquals("c2fdc73c67ca9156098ce4a1b1f2853486fc5aa1", spec.path("baseline").asText(),
                "fixture 规范必须锚定 P1 起始基线，否则无法判断它是否随基线漂移");
        assertTrue(spec.path("purpose").asText().contains("合成"),
                "规范必须自述为合成数据，避免被当作存量盘点结论");
    }

    @Test
    @DisplayName("两租户 + 同名不同身份：username 相同但 userId/membershipId 不同，不得合并")
    void sameUsernameNeverMergesAcrossTenants() throws IOException {
        JsonNode spec = readSpec();

        List<JsonNode> pairs = toList(spec.path("sameUsernameDifferentIdentity"));
        assertEquals(2, pairs.size(), "同名对照必须恰好两条");
        assertEquals(pairs.get(0).path("username").asText(), pairs.get(1).path("username").asText(),
                "同名对照的两条必须真的同名，否则这个反例没有意义");
        assertFalse(pairs.get(0).path("tenantId").asText().equals(pairs.get(1).path("tenantId").asText()),
                "同名对照必须跨租户");
        assertFalse(pairs.get(0).path("userId").asText().equals(pairs.get(1).path("userId").asText()),
                "同名不同 userId 是「同名不合并」的核心输入");
        assertNotEqualsAny(pairs.get(0).path("membershipId").asText(), pairs.get(1).path("membershipId").asText());

        // 每个租户至少一个成员，且 membershipId 必须符合 canonical 形式 platform:<tenant>:<userId>
        for (JsonNode tenant : toList(spec.path("tenants"))) {
            String tenantId = tenant.path("tenantId").asText();
            List<JsonNode> users = toList(tenant.path("users"));
            assertFalse(users.isEmpty(), "租户 " + tenantId + " 必须至少有一个成员");
            for (JsonNode user : users) {
                String membershipId = user.path("membershipId").asText();
                assertEquals("platform:" + tenantId + ":" + user.path("userId").asText(), membershipId,
                        "canonical membershipId 必须是 platform:<tenantId>:<userId>");
                assertFalse(membershipId.contains("::"),
                        "tenant 部分不能为空，否则会产生歧义 member 引用");
            }
        }
    }

    @Test
    @DisplayName("每个租户都有部门子树；数据范围覆盖 1/2/3/4/5/6 与「仅本人」")
    void departmentsAndDataScopesAreCovered() throws IOException {
        JsonNode spec = readSpec();

        for (JsonNode tenant : toList(spec.path("tenants"))) {
            List<JsonNode> depts = toList(tenant.path("departments"));
            assertTrue(depts.size() >= 2,
                    "租户 " + tenant.path("tenantId").asText() + " 必须有父/子部门，才能验证「本部门及子树」");
            boolean hasChild = depts.stream().anyMatch(d -> d.path("parentDeptId").asInt() != 0);
            assertTrue(hasChild, "至少要有 parent 指向同租户父部门的子部门");
            for (JsonNode dept : depts) {
                assertTrue(dept.path("deptId").asInt() > 0, "部门必须有确定 ID 才能复算");
            }
        }

        Set<String> scopeCodes = new LinkedHashSet<>();
        for (JsonNode scope : toList(spec.path("dataScopes"))) {
            scopeCodes.add(scope.path("scopeCode").asText());
            assertFalse(scope.path("action").asText().isBlank(),
                    "数据范围必须绑定到具体动作：无关高权限角色不能扩大到所有 AI 动作");
        }
        // DataScopeType：1 全部 / 2 自定义部门 / 3 本部门 / 4 本部门及子树 / 5 仅本人 / 6 子树或本人
        for (String required : List.of("1", "2", "3", "4", "5", "6")) {
            assertTrue(scopeCodes.contains(required),
                    "数据范围 " + required + " 必须有对应 fixture 场景，否则该分支无法被验收覆盖");
        }
    }

    @Test
    @DisplayName("资源正反例齐备：私有、显式 tenant_all、tombstone、父允许子删除、跨租户同名")
    void resourcesCoverRequiredScenarios() throws IOException {
        JsonNode spec = readSpec();
        List<JsonNode> resources = toList(spec.path("resources"));
        assertTrue(resources.size() >= 7, "资源样例数量不足：" + resources.size());

        assertTrue(hasAcl(resources, "tenant_all"),
                "缺少显式 tenant_all 持久 grant 的正例——它必须与「scope 空值」区分开");
        assertTrue(hasStatus(resources, "DELETED"),
                "缺少 tombstone 反例：删除必须优先于 ACL");
        assertTrue(hasChildNarrowing(resources),
                "缺少「父授权 tenant_all + 子资源私有收窄」的反例");
        assertTrue(hasParentAllowedChildDeleted(resources),
                "缺少「父允许但子 tombstone」的反例：不得因父权限放行");
        assertTrue(hasSameIdDifferentTenant(resources),
                "缺少跨租户相同资源选择符的反例");
        assertTrue(hasOwnerDept(resources),
                "资源必须带 ownerDeptId（由 platform 核实后绑定，客户端不提供可信部门）");

        // 每条 ACL 的 subject 必须是四种受支持类型之一
        Set<String> allowedSubjects = Set.of("member", "department", "role", "tenant_all");
        for (JsonNode resource : resources) {
            for (JsonNode acl : toList(resource.path("acl"))) {
                String subjectType = acl.path("subjectType").asText();
                assertTrue(allowedSubjects.contains(subjectType),
                        "非法 subjectType=" + subjectType + "；跨租户分享必须被拒绝");
            }
        }
        assertTrue(spec.path("sameResourceSelectorAcrossTenants").isArray()
                        && spec.path("sameResourceSelectorAcrossTenants").size() > 0,
                "必须显式记录跨租户同名选择符的映射，供负例断言引用");
    }

    @Test
    @DisplayName("会话/记忆/状态/run/对象/向量均带归属，且含「同 session 不同 tenant」隔离用例")
    void ownershipIsPresentOnEveryDomain() throws IOException {
        JsonNode spec = readSpec();

        assertNonEmptyWithFields(spec, "conversations", "tenantId", "memberId");
        assertNonEmptyWithFields(spec, "memories", "tenantId", "memberId");
        assertNonEmptyWithFields(spec, "stateKeys", "tenantId", "memberId", "sessionId", "stateKey");
        assertNonEmptyWithFields(spec, "runs", "tenantId", "runId");
        assertNonEmptyWithFields(spec, "objects", "tenantId", "key");
        assertNonEmptyWithFields(spec, "vectors", "tenantId", "collection", "chunkId");

        // 状态：T1/T2 使用相同 session_id + state_key，验证复合命名空间而非裸 key
        List<JsonNode> stateKeys = toList(spec.path("stateKeys"));
        boolean sharedSessionAcrossTenants = false;
        for (int i = 0; i < stateKeys.size(); i++) {
            for (int j = i + 1; j < stateKeys.size(); j++) {
                if (stateKeys.get(i).path("sessionId").asText().equals(stateKeys.get(j).path("sessionId").asText())
                        && stateKeys.get(i).path("stateKey").asText().equals(stateKeys.get(j).path("stateKey").asText())
                        && !stateKeys.get(i).path("tenantId").asText()
                        .equals(stateKeys.get(j).path("tenantId").asText())) {
                    sharedSessionAcrossTenants = true;
                }
            }
        }
        assertTrue(sharedSessionAcrossTenants,
                "必须存在「同 session/key、不同 tenant」的用例：裸 key 命中即为隔离失败");

        // 记忆必须带 source refs，且至少一条来源已撤权/删除
        List<JsonNode> memories = toList(spec.path("memories"));
        assertTrue(memories.stream().allMatch(m -> m.path("sourceRefs").size() > 0),
                "每条记忆都必须关联 source refs；来源未知禁止向模型提供");
        List<JsonNode> deletedIds = new ArrayList<>();
        for (JsonNode resource : toList(spec.path("resources"))) {
            if ("DELETED".equals(resource.path("status").asText())) {
                deletedIds.add(resource);
            }
        }
        assertFalse(deletedIds.isEmpty(), "缺少 deleted 资源，无法构造「来源撤权后无输出」用例");
        boolean revokedSourceCase = false;
        for (JsonNode memory : memories) {
            for (JsonNode ref : toList(memory.path("sourceRefs"))) {
                for (JsonNode deleted : deletedIds) {
                    if (ref.asText().equals(deleted.path("id").asText())) {
                        revokedSourceCase = true;
                    }
                }
            }
        }
        assertTrue(revokedSourceCase,
                "至少一条记忆必须引用已 tombstone 的资源，否则「撤权后 summary/memory 无输出」无法验收");

        // 向量：确定性、可复算，且两个租户在同一物理 collection 上
        List<JsonNode> vectors = toList(spec.path("vectors"));
        Set<String> collections = new LinkedHashSet<>();
        for (JsonNode vector : vectors) {
            collections.add(vector.path("collection").asText());
            JsonNode expected = vector.path("expectedVector");
            assertTrue(expected.isArray() && expected.size() > 0,
                    "向量必须是确定值，否则 PG 命中无法复算");
            for (JsonNode component : expected) {
                assertTrue(component.isNumber(), "向量分量必须是数值");
            }
        }
        assertEquals(1, collections.size(),
                "两个租户的向量必须落在同一物理 collection，否则「同表按 tenant 区分」无法被证明");
    }

    @Test
    @DisplayName("未知归属与 legacy token 样例齐备（隔离而非删除，也不得默认 tenant）")
    void unknownOwnershipAndLegacyTokensAreCovered() throws IOException {
        JsonNode spec = readSpec();

        List<JsonNode> unknown = toList(spec.path("unknownOwnership"));
        assertTrue(unknown.size() >= 2, "至少两条未知归属样例（KB 与 message 各一）");
        for (JsonNode row : unknown) {
            assertFalse(row.path("reason").asText().isBlank(),
                    "未知归属必须写明原因，否则无法判断是「确实不可知」还是「没做」");
            assertTrue(row.path("expect").asText().contains("隔离"),
                    "未知归属的期望必须是隔离为不可访问，而不是默认 tenant 或删除");
        }

        List<JsonNode> tokens = toList(spec.path("legacyTokens"));
        assertTrue(tokens.size() >= 4, "legacy/admin/platform/伪造 header 四类样例都必须有");
        for (JsonNode token : tokens) {
            assertEquals(404, token.path("expectedHttp").asInt(),
                    "旧 token 与伪造身份头必须与「资源不存在」同外显，不能泄露存在性");
            assertEquals("RESOURCE_NOT_FOUND_OR_FORBIDDEN", token.path("expectedErrorCode").asText());
        }
    }

    @Test
    @DisplayName("计数与规范自洽；每个计数都为正且与实际数组长度一致")
    void countsMatchTheDeclaredCollections() throws IOException {
        JsonNode spec = readSpec();
        JsonNode counts = spec.path("counts");
        assertTrue(counts.isObject() && counts.size() > 0, "必须声明计数，供装载后 row-hash 核对");

        assertEquals(toList(spec.path("tenants")).size(), counts.path("tenants").asInt());
        assertEquals(toList(spec.path("conversations")).size(), counts.path("conversations").asInt());
        assertEquals(toList(spec.path("memories")).size(), counts.path("memories").asInt());
        assertEquals(toList(spec.path("stateKeys")).size(), counts.path("stateKeys").asInt());
        assertEquals(toList(spec.path("runs")).size(), counts.path("runs").asInt());
        assertEquals(toList(spec.path("objects")).size(), counts.path("objects").asInt());
        assertEquals(toList(spec.path("vectors")).size(), counts.path("vectors").asInt());
        assertEquals(toList(spec.path("resources")).size(), counts.path("aiResources").asInt());
        assertEquals(toList(spec.path("legacyTokens")).size(), counts.path("legacyTokens").asInt());

        int departments = 0;
        for (JsonNode tenant : toList(spec.path("tenants"))) {
            departments += toList(tenant.path("departments")).size();
        }
        assertEquals(departments, counts.path("departments").asInt());

        int users = 0;
        for (JsonNode tenant : toList(spec.path("tenants"))) {
            users += toList(tenant.path("users")).size();
        }
        assertEquals(users, counts.path("platformUsers").asInt());

        // 哈希段必须声明算法与输入清单：可复算的前提是知道"对什么算"
        assertEquals("SHA256", spec.path("hashes").path("algorithm").asText());
        assertEquals(3, spec.path("hashes").path("inputs").size(),
                "哈希输入必须恰好覆盖规范 + 两侧 SQL");
        assertFalse(spec.path("hashes").path("inputs").toString().contains("hash"),
                "规范里不得预置可漂移的哈希常量；哈希在 Validate/Generate 时重算");
    }

    @Test
    @DisplayName("两侧 SQL 与规范一致：同一选择符、同一人员身份、P1 权限全集、无真实凭据")
    void sqlFixturesAgreeWithTheSpec() throws IOException {
        JsonNode spec = readSpec();
        String aiSql = readClasspath(AI_SQL_RESOURCE);

        Path platformSqlPath = resolveRepoFile(PLATFORM_SQL_RELATIVE);
        String platformSql = Files.readString(platformSqlPath, StandardCharsets.UTF_8);

        for (JsonNode pair : toList(spec.path("sameUsernameDifferentIdentity"))) {
            assertTrue(platformSql.contains("'" + pair.path("username").asText() + "'"),
                    "platform SQL 必须真的插入同名用户 " + pair.path("username").asText());
            assertTrue(platformSql.contains(pair.path("userId").asText()),
                    "platform SQL 必须插入 userId=" + pair.path("userId").asText());
            assertTrue(platformSql.contains("'" + pair.path("tenantId").asText() + "'"),
                    "platform SQL 必须插入租户 " + pair.path("tenantId").asText());
        }

        for (String permission : EXPECTED_PERMISSIONS) {
            assertTrue(platformSql.contains("'" + permission + "'"),
                    "platform SQL 缺少 P1 动作权限 " + permission + "；菜单授权与 05 §4.2 必须一致");
        }

        // AI 侧 SQL：必须覆盖 registry/ACL/epoch 的关键标记与未知归属处置说明
        for (String marker : List.of("ai_resource", "ai_resource_acl", "ai_acl_epoch",
                "tenant_all", "kb-t2-same-selector", "kb_unknown_owner")) {
            assertTrue(aiSql.contains(marker), "AI 域 SQL 缺少关键标记：" + marker);
        }
        // 2026-10-02 判据演进：V3–V5 迁移已在专属合成库冻结并实际应用，
        // fixture 由 runner 在完整迁移之后装载，因此"STAGE 注释模板"不再是隔离证据；
        // 新判据是 fixture 显式声明其装载时机（完整迁移之后），且未知归属
        // 的拒绝发生在迁移守卫（P1001），不默认 tenant、不删数据。
        assertTrue(aiSql.contains("STAGE V3") && aiSql.contains("完整应用"),
                "AI 域 SQL 必须记录迁移阶段状态与冻结事实（STAGE V3–V5 已冻结并完整应用）");
        assertTrue(aiSql.contains("不得默认 tenant"),
                "AI 域 SQL 必须写明未知归属不得默认 tenant");

        // 两侧 SQL 都不得包含真实凭据形态；password 只能是合成 MD5
        assertNoRealCredentials(aiSql, "AI 域 SQL");
        assertNoRealCredentials(platformSql, "platform 域 SQL");

        // 合成密码哈希：三个合成用户共用同一个值（3 行数据）+ 文件头说明 1 处 = 4 次。
        // 用精确计数而不是"是否包含"，是为了让"有人又悄悄加了另一个密码值"也能被发现。
        String syntheticHash = "e285735dc33ec875d94b200bd77d8720";
        assertEquals(4, countOccurrences(platformSql, syntheticHash),
                "platform SQL 中合成密码哈希出现次数必须是 4（3 行数据 + 1 处文件头说明）；"
                        + "多了说明有人在别处又写了一份凭据值，容易漂移");
        assertEquals(1, countOccurrences(platformSql, "avatar, password, status"),
                "password 列只应出现在 sys_user 的列清单里一次；"
                        + "出现多次通常意味着有人绕过列清单手工拼 SQL");

        assertTrue(platformSql.contains("同名"),
                "platform SQL 必须写明同名不合并的意图");
    }

    @Test
    @DisplayName("SQL fixture 是本单元的证据基础，必须存在且非空（防止路径写错被当成内容不对）")
    void sqlFixturesActuallyExistAndAreSubstantial() {
        Path platformSqlPath = resolveRepoFile(PLATFORM_SQL_RELATIVE);
        assertTrue(platformSqlPath.toFile().length() > 1024,
                "platform SQL 过小，可能被截断或写错位置：" + platformSqlPath);
        assertTrue(platformSqlPath.toAbsolutePath().normalize().endsWith(
                        Path.of("ruoyi-ai-integration", "src", "test", "resources", "p1", "tenants.sql")),
                "解析出的 platform SQL 路径必须在 03 规定的精确位置内：" + platformSqlPath);
    }

    // ------------------------------------------------------------------ 工具方法

    private static JsonNode readSpec() throws IOException {
        return MAPPER.readTree(Files.readString(resolveRepoFile(SPEC_RELATIVE), StandardCharsets.UTF_8));
    }

    /** 从 classpath 读取 UTF-8 文本；缺失即失败（绝不静默跳过）。 */
    private static String readClasspath(String resource) throws IOException {
        try (InputStream in = P1FixtureContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "classpath 上找不到 " + resource + "；fixture 契约不允许静默缺失");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 从仓库内解析文件：从当前工作目录逐级向上尝试若干候选路径。
     *
     * <p>找不到就抛错而不是返回空串——否则后续 {@code contains} 断言会以"文件是空的"为由失败，
     * 把"路径没找对"伪装成"内容不对"。多模块构建下工作目录可能是仓库根、也可能是模块目录。
     */
    private static Path resolveRepoFile(String relativePath) {
        List<Path> candidates = new ArrayList<>();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 6 && cwd != null; i++) {
            candidates.add(cwd.resolve(relativePath).normalize());
            candidates.add(cwd.resolve("Ruoyi-Ai-AgentScope").resolve(relativePath).normalize());
            cwd = cwd.getParent();
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("无法从工作目录 " + Path.of("").toAbsolutePath()
                + " 定位 " + relativePath + "；已尝试 " + candidates.size() + " 个候选路径");
    }

    private static List<JsonNode> toList(JsonNode array) {
        assertTrue(array.isArray(), "期望 JSON 数组，实际是 " + array.getNodeType());
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private static void assertNonEmptyWithFields(JsonNode spec, String field, String... required) {
        List<JsonNode> items = toList(spec.path(field));
        assertFalse(items.isEmpty(), field + " 不能为空");
        for (JsonNode item : items) {
            for (String key : required) {
                assertFalse(item.path(key).asText().isBlank(),
                        field + " 的每条记录都必须有 " + key + "（归属不可缺省）");
            }
        }
    }

    private static boolean hasAcl(List<JsonNode> resources, String subjectType) {
        for (JsonNode resource : resources) {
            for (JsonNode acl : toList(resource.path("acl"))) {
                if (subjectType.equals(acl.path("subjectType").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasStatus(List<JsonNode> resources, String status) {
        return resources.stream().anyMatch(r -> status.equals(r.path("status").asText()));
    }

    private static boolean hasOwnerDept(List<JsonNode> resources) {
        return resources.stream().allMatch(r -> r.path("ownerDeptId").asInt() > 0);
    }

    private static boolean hasChildNarrowing(List<JsonNode> resources) {
        for (JsonNode child : resources) {
            if (!"KB".equals(child.path("parentType").asText())) {
                continue;
            }
            String parentId = child.path("parentId").asText();
            for (JsonNode parent : resources) {
                if (!parent.path("id").asText().equals(parentId)) {
                    continue;
                }
                boolean parentIsTenantAll = hasAcl(List.of(parent), "tenant_all");
                boolean childIsPrivate = child.path("acl").size() > 0 && !hasAcl(List.of(child), "tenant_all");
                if (parentIsTenantAll && childIsPrivate) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasParentAllowedChildDeleted(List<JsonNode> resources) {
        for (JsonNode child : resources) {
            if (!"DELETED".equals(child.path("status").asText())) {
                continue;
            }
            String parentId = child.path("parentId").asText();
            for (JsonNode parent : resources) {
                if (parent.path("id").asText().equals(parentId) && "ACTIVE".equals(parent.path("status").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasSameIdDifferentTenant(List<JsonNode> resources) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode resource : resources) {
            String key = resource.path("tenantId").asText() + "|" + resource.path("id").asText();
            assertTrue(seen.add(key), "同一租户内资源 ID 重复：" + key + "；复合唯一必须成立");
        }
        // 跨租户"相同选择符"由 sameResourceSelectorAcrossTenants 段保证
        return resources.stream().anyMatch(r -> "T2".equals(r.path("tenantId").asText()));
    }

    private static void assertNotEqualsAny(String a, String b) {
        assertFalse(a.equals(b), "两值必须不同：a=" + a + " b=" + b);
    }

    private static void assertNoRealCredentials(String sql, String label) {
        String lower = sql.toLowerCase();
        for (String forbidden : List.of("-----begin", "akial", "sk-", "password=", "access_key =", "secret_key =")) {
            assertFalse(lower.contains(forbidden),
                    label + " 含疑似真实凭据片段：" + forbidden + "；公开仓库不得提交真实密钥");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
