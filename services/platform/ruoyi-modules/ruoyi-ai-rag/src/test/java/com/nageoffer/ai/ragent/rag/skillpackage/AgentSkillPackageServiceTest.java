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

package com.nageoffer.ai.ragent.rag.skillpackage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.service.impl.AgentSkillAdminServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F11:4 技能包导入导出（有限闭环）服务判据。
 *
 * <p>红先行依据：本类在实现前引用尚不存在的 {@link SkillPackageService} /
 * {@link SkillPackageException}，`test` 阶段先红；实现后逐条转绿。
 *
 * <p>判据边界（对应的冻结契约见 brief）：
 * <ul>
 *   <li>往返：内容逐字（不 trim）、名称/描述/工具引用/排序/enabled 不变；</li>
 *   <li>拒绝且零副作用：路径穿越、绝对路径、反斜杠、符号链接、目录、重复 entry、
 *       5 MiB 包 / 2 MiB entry / 10 MiB 解压总量 / 8 entries 上限、坏 manifest、
 *       未知 schemaVersion、缺 manifest/skill.md、包内身份字段；</li>
 *   <li>冲突：同 skillCode 已存在 → 409，零写入；</li>
 *   <li>脱敏：导出 manifest 只含契约 8 字段，不含内部 id / 租户 / 创建人 / 凭据；</li>
 *   <li>不执行包内代码：包内脚本 entry 不落盘、不执行，正文只作为 Markdown 存储。</li>
 * </ul>
 */
@Tag("dev")
class AgentSkillPackageServiceTest {

    private static final String TOOL_ID = "tool-hr";

    private static final String CONTENT =
            "  # 请假流程\n\n  1. 打开 OA  \n\n- 步骤 2：确认余额\n\n<script>alert('x')</script>\n";

    private final AgentSkillMapper mapper = mock(AgentSkillMapper.class);
    private final AgentSkillCacheManager cacheManager = mock(AgentSkillCacheManager.class);
    private final IntentNodeRegistry intentRegistry = mock(IntentNodeRegistry.class);
    private final BizChangeLogContext audit = mock(BizChangeLogContext.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 内存行集：只由 mock mapper 的 insert/selectById 驱动，用来断言"零新增/零修改"。 */
    private final Map<String, AgentSkillDO> store = new LinkedHashMap<>();

    private boolean conflict;

    private AgentSkillAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentSkillAdminServiceImpl(mapper, cacheManager, intentRegistry, audit, objectMapper);
        when(intentRegistry.listMcpToolNodes())
                .thenReturn(List.of(IntentNode.builder().id("n1").mcpToolId(TOOL_ID).build()));
        when(mapper.exists(any())).thenAnswer(invocation -> conflict);
        when(mapper.insert(any(AgentSkillDO.class))).thenAnswer(invocation -> {
            AgentSkillDO row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId("generated-" + row.getSkillCode());
            }
            store.put(row.getId(), copy(row));
            return 1;
        });
        when(mapper.selectById(anyString())).thenAnswer(invocation -> {
            AgentSkillDO row = store.get(invocation.getArgument(0));
            return row == null ? null : copy(row);
        });
    }

    // ------------------------------------------------------------------ 往返

    @Test
    @DisplayName("导出→删除原行→导入：正文逐字、元数据、toolIds、sortOrder、enabled 全部不丢")
    void roundTripPreservesContentMetadataAndEnabled() {
        AgentSkillDO original = AgentSkillDO.builder()
                .id("skill-1")
                .skillCode("leave_apply")
                .name("请假申请")
                .description("当用户要请假或查询假期余额时使用")
                .content(CONTENT)
                .toolIds(List.of(TOOL_ID))
                .sortOrder(7)
                .enabled(0)
                .createBy("secret-creator")
                .updateBy("secret-updater")
                .createTime(new Date(1_700_000_000_000L))
                .updateTime(new Date(1_700_000_100_000L))
                .deleted(0)
                .build();
        store.put(original.getId(), copy(original));

        SkillPackageExport export = service.exportPackage("skill-1");

        assertThat(export.filename()).isEqualTo("leave_apply.zip");
        Map<String, byte[]> exported = SkillPackageTestFixtures.unzip(export.bytes());
        assertThat(exported).containsOnlyKeys("manifest.json", "skill.md");
        assertThat(new String(exported.get("skill.md"), StandardCharsets.UTF_8)).isEqualTo(CONTENT);

        // 模拟直接导入会撞唯一键：先删除原行，再导入同一个包
        store.clear();

        SkillPackageImportResult result = service.importPackage(file(export.bytes()));

        assertThat(result.skillCode()).isEqualTo("leave_apply");
        AgentSkillDO imported = store.get(result.id());
        assertThat(imported).isNotNull();
        assertThat(imported.getSkillCode()).isEqualTo("leave_apply");
        assertThat(imported.getName()).isEqualTo("请假申请");
        assertThat(imported.getDescription()).isEqualTo("当用户要请假或查询假期余额时使用");
        assertThat(imported.getContent()).isEqualTo(CONTENT);
        assertThat(imported.getToolIds()).containsExactly(TOOL_ID);
        assertThat(imported.getSortOrder()).isEqualTo(7);
        assertThat(imported.getEnabled()).isZero();
        verify(cacheManager).clearCache();
        verify(audit).put(eq(result.id()), isNull(), any(AgentSkillDO.class));

        // 再导出一次：导入后的行仍能产出等值内容
        SkillPackageExport again = service.exportPackage(result.id());
        JsonNode manifest = readTree(SkillPackageTestFixtures.unzip(again.bytes()).get("manifest.json"));
        assertThat(manifest.get("skillCode").asText()).isEqualTo("leave_apply");
        assertThat(manifest.get("contentFile").asText()).isEqualTo("skill.md");
        assertThat(manifest.get("enabled").asBoolean()).isFalse();
        assertThat(new String(SkillPackageTestFixtures.unzip(again.bytes()).get("skill.md"),
                StandardCharsets.UTF_8)).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("包内缺省 enabled=true、sortOrder=0")
    void omittedEnabledDefaultsToEnabledAndSortOrderToZero() {
        byte[] zip = SkillPackageTestFixtures.zipWithRawManifest(
                SkillPackageTestFixtures.manifest("default_on", "默认启用", "说明", List.of(), null, null),
                "正文");

        SkillPackageImportResult result = service.importPackage(file(zip));

        AgentSkillDO imported = store.get(result.id());
        assertThat(imported.getEnabled()).isEqualTo(1);
        assertThat(imported.getSortOrder()).isZero();
        assertThat(imported.getToolIds()).isEmpty();
    }

    // ------------------------------------------------------------------ 恶意包：零副作用

    @Test
    @DisplayName("路径穿越 ../x：拒绝且零新增/零修改")
    void pathTraversalEntryIsRejectedWithoutWrites() {
        byte[] zip = withExtra("../evil.md", "x");
        assertRejected(zip, SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("绝对路径 /evil.md（及 C:/evil.md）：拒绝且零写入")
    void absolutePathEntryIsRejectedWithoutWrites() {
        assertRejected(withExtra("/evil.md", "x"), SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);

        assertRejected(withExtra("C:/evil.md", "x"), SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("反斜杠路径 dir\\evil.md：拒绝且零写入")
    void backslashPathEntryIsRejectedWithoutWrites() {
        assertRejected(withExtra("dir\\evil.md", "x"), SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("符号链接 entry（Unix mode 0xA000）：拒绝且零写入")
    void symlinkEntryIsRejectedWithoutWrites() {
        LinkedHashMap<String, byte[]> entries = validEntries();
        entries.put("link.txt", "target".getBytes(StandardCharsets.UTF_8));
        byte[] zip = SkillPackageTestFixtures.zipWithEntries(entries);
        zip = SkillPackageTestFixtures.patchExternalAttributes(zip, "link.txt", 0xA1FFL << 16);

        assertRejected(zip, SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("目录 entry dir/：拒绝且零写入")
    void directoryEntryIsRejectedWithoutWrites() {
        LinkedHashMap<String, byte[]> entries = validEntries();
        entries.put("dir/", new byte[0]);

        assertRejected(SkillPackageTestFixtures.zipWithEntries(entries), SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("重复 entry skill.md×2：拒绝且零写入")
    void duplicateEntryIsRejectedWithoutWrites() {
        LinkedHashMap<String, byte[]> entries = validEntries();
        entries.put("xxxxx.md", "second".getBytes(StandardCharsets.UTF_8));
        byte[] zip = SkillPackageTestFixtures.zipWithEntries(entries);
        zip = SkillPackageTestFixtures.patchEntryName(zip, "xxxxx.md", "skill.md");

        assertRejected(zip, SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("ZIP >5 MiB：拒绝（413）且零写入")
    void zipOverFiveMiBIsRejectedWithoutWrites() {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];

        assertRejected(oversized, SkillPackageError.PACKAGE_TOO_LARGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("单 entry 解压 >2 MiB：拒绝（413）且零写入")
    void entryOverTwoMiBIsRejectedWithoutWrites() {
        String huge = "a".repeat(2 * 1024 * 1024 + 1);
        byte[] zip = SkillPackageTestFixtures.validPackage(
                "huge_entry", "超限", "说明", List.of(), 0, true, huge);

        assertRejected(zip, SkillPackageError.PACKAGE_TOO_LARGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("解压总量 >10 MiB：拒绝（413）且零写入")
    void totalUncompressedOverTenMiBIsRejectedWithoutWrites() {
        LinkedHashMap<String, byte[]> entries = validEntries();
        for (int i = 0; i < 6; i++) {
            entries.put("extra-" + i + ".bin", new byte[2 * 1024 * 1024]);
        }

        assertRejected(SkillPackageTestFixtures.zipWithEntries(entries), SkillPackageError.PACKAGE_TOO_LARGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("entries >8：拒绝（413）且零写入")
    void moreThanEightEntriesIsRejectedWithoutWrites() {
        LinkedHashMap<String, byte[]> entries = validEntries();
        for (int i = 0; i < 9; i++) {
            entries.put("tiny-" + i + ".txt", new byte[]{1});
        }

        assertRejected(SkillPackageTestFixtures.zipWithEntries(entries), SkillPackageError.PACKAGE_TOO_LARGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("坏 manifest（非 JSON）：拒绝且零写入")
    void malformedManifestJsonIsRejectedWithoutWrites() {
        byte[] zip = SkillPackageTestFixtures.zipWithRawManifest(
                "{not json".getBytes(StandardCharsets.UTF_8), "正文");

        assertRejected(zip, SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("未知 schemaVersion=2：拒绝且零写入")
    void unknownSchemaVersionIsRejectedWithoutWrites() throws Exception {
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(
                SkillPackageTestFixtures.manifest("future", "未来", "说明", List.of(), 0, true));
        manifest.put("schemaVersion", 2);
        byte[] zip = SkillPackageTestFixtures.zipWithRawManifest(objectMapper.writeValueAsBytes(manifest), "正文");

        assertRejected(zip, SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("缺 manifest.json：拒绝且零写入")
    void missingManifestIsRejectedWithoutWrites() {
        Map<String, byte[]> onlyContent = Map.of(
                "skill.md", "正文".getBytes(StandardCharsets.UTF_8));

        assertRejected(SkillPackageTestFixtures.zipWithEntries(onlyContent), SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("缺 skill.md：拒绝且零写入")
    void missingSkillContentIsRejectedWithoutWrites() {
        Map<String, byte[]> onlyManifest = Map.of(
                "manifest.json",
                SkillPackageTestFixtures.manifest("no_content", "缺正文", "说明", List.of(), 0, true));

        assertRejected(SkillPackageTestFixtures.zipWithEntries(onlyManifest), SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("manifest 携带租户/创建人等包内身份：拒绝且零写入（不从包恢复身份）")
    void manifestCarryingIdentityIsRejectedWithoutWrites() throws Exception {
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(
                SkillPackageTestFixtures.manifest("identity", "身份", "说明", List.of(), 0, true));
        manifest.put("tenantId", "tenant-from-package");
        manifest.put("createBy", "user-from-package");
        byte[] zip = SkillPackageTestFixtures.zipWithRawManifest(objectMapper.writeValueAsBytes(manifest), "正文");

        assertRejected(zip, SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("未知工具引用：拒绝且零写入（只引用已存在 MCP 工具，不自动加载外部工具）")
    void unknownToolIdIsRejectedWithoutWrites() {
        byte[] zip = SkillPackageTestFixtures.validPackage(
                "tool_ref", "工具", "说明", List.of("tool-not-installed"), 0, true, "正文");

        assertRejected(zip, SkillPackageError.INVALID_PACKAGE);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("冲突：同 skillCode 已存在 → 409 且零写入")
    void existingSkillCodeConflictsWithoutWrites() {
        conflict = true;
        byte[] zip = SkillPackageTestFixtures.validPackage(
                "leave_apply", "请假", "说明", List.of(), 0, true, "正文");

        SkillPackageException ex = assertThrows(SkillPackageException.class,
                () -> service.importPackage(file(zip)));

        assertThat(ex.error()).isEqualTo(SkillPackageError.SKILL_CONFLICT);
        assertThat(ex.httpStatus()).isEqualTo(409);
        verify(mapper).exists(any());
        verify(mapper, never()).insert(any(AgentSkillDO.class));
        verify(mapper, never()).updateById(any(AgentSkillDO.class));
        verify(cacheManager, never()).clearCache();
        verify(audit, never()).put(any(), any(), any());
        assertThat(store).isEmpty();
    }

    // ------------------------------------------------------------------ 导出脱敏

    @Test
    @DisplayName("导出 manifest 只有契约 8 字段，不含内部 id/租户/创建人/凭据")
    void exportRedactsInternalIdentityAndCredentialFields() throws Exception {
        AgentSkillDO row = AgentSkillDO.builder()
                .id("internal-id-9")
                .skillCode("secure_skill")
                .name("脱敏技能")
                .description("说明")
                .content("正文")
                .toolIds(List.of(TOOL_ID))
                .sortOrder(3)
                .enabled(1)
                .createBy("secret-creator")
                .updateBy("secret-updater")
                .createTime(new Date(1_700_000_000_000L))
                .updateTime(new Date(1_700_000_100_000L))
                .deleted(0)
                .build();
        store.put(row.getId(), copy(row));

        byte[] zip = service.exportPackage(row.getId()).bytes();
        Map<String, byte[]> entries = SkillPackageTestFixtures.unzip(zip);
        assertThat(entries).containsOnlyKeys("manifest.json", "skill.md");

        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));
        Set<String> fields = new HashSet<>();
        manifest.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "schemaVersion", "skillCode", "name", "description",
                "toolIds", "sortOrder", "enabled", "contentFile");
        for (String forbidden : List.of("id", "tenantId", "tenant_id", "createBy", "updateBy",
                "createTime", "updateTime", "credentials", "credential", "token", "password")) {
            assertThat(manifest.has(forbidden))
                    .as("导出 manifest 不得含内部字段 %s", forbidden)
                    .isFalse();
        }
        assertThat(manifest.toString()).doesNotContain("internal-id-9", "secret-creator", "secret-updater");
        assertThat(manifest.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(manifest.get("skillCode").asText()).isEqualTo("secure_skill");
        assertThat(manifest.get("toolIds")).hasSize(1);
        assertThat(manifest.get("toolIds").get(0).asText()).isEqualTo(TOOL_ID);
        assertThat(manifest.get("contentFile").asText()).isEqualTo("skill.md");
    }

    @Test
    @DisplayName("导出不存在的技能：具名 404（不是 5xx）")
    void exportOfMissingSkillIsNamedNotFound() {
        SkillPackageException ex = assertThrows(SkillPackageException.class,
                () -> service.exportPackage("missing"));

        assertThat(ex.error()).isEqualTo(SkillPackageError.SKILL_NOT_FOUND);
        assertThat(ex.httpStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 不执行包内代码

    @Test
    @DisplayName("包内脚本 entry 不被执行、不落盘；正文只按 Markdown 存储")
    void packageCodeIsNeverExecuted(@TempDir Path tempDir) throws Exception {
        Path marker = tempDir.resolve("pwned.txt");
        String script = "#!/bin/sh\ntouch \"" + marker.toAbsolutePath() + "\"\n";
        LinkedHashMap<String, byte[]> entries = validEntries("scripted", "脚本", "说明");
        entries.put("run.sh", script.getBytes(StandardCharsets.UTF_8));

        SkillPackageImportResult result = service.importPackage(
                file(SkillPackageTestFixtures.zipWithEntries(entries)));

        assertThat(Files.exists(marker)).as("包内脚本不得被执行").isFalse();
        AgentSkillDO imported = store.get(result.id());
        assertThat(imported.getContent()).isEqualTo("正文");
        assertThat(imported.getContent()).doesNotContain("#!/bin/sh");
    }

    // ------------------------------------------------------------------ 夹具

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "skill.zip", "application/zip", bytes);
    }

    private LinkedHashMap<String, byte[]> validEntries() {
        return validEntries("pkg_skill", "技能包", "说明");
    }

    private LinkedHashMap<String, byte[]> validEntries(String skillCode, String name, String description) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json",
                SkillPackageTestFixtures.manifest(skillCode, name, description, List.of(), 0, true));
        entries.put("skill.md", "正文".getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private byte[] withExtra(String entryName, String value) {
        LinkedHashMap<String, byte[]> entries = validEntries();
        entries.put(entryName, value.getBytes(StandardCharsets.UTF_8));
        return SkillPackageTestFixtures.zipWithEntries(entries);
    }

    private void assertRejected(byte[] zip, SkillPackageError expected) {
        SkillPackageException ex = assertThrows(SkillPackageException.class,
                () -> service.importPackage(file(zip)));
        assertThat(ex.error()).isEqualTo(expected);
        assertThat(ex.httpStatus()).isEqualTo(expected.httpStatus());
        assertThat(store).as("拒绝路径不得新增行").isEmpty();
        verify(mapper, never()).insert(any(AgentSkillDO.class));
        verify(mapper, never()).updateById(any(AgentSkillDO.class));
        verify(cacheManager, never()).clearCache();
        verify(audit, never()).put(any(), any(), any());
    }

    private JsonNode readTree(byte[] json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AgentSkillDO copy(AgentSkillDO source) {
        return AgentSkillDO.builder()
                .id(source.getId())
                .skillCode(source.getSkillCode())
                .name(source.getName())
                .description(source.getDescription())
                .content(source.getContent())
                .toolIds(source.getToolIds() == null ? null : List.copyOf(source.getToolIds()))
                .sortOrder(source.getSortOrder())
                .enabled(source.getEnabled())
                .createBy(source.getCreateBy())
                .updateBy(source.getUpdateBy())
                .createTime(source.getCreateTime())
                .updateTime(source.getUpdateTime())
                .deleted(source.getDeleted())
                .build();
    }
}
