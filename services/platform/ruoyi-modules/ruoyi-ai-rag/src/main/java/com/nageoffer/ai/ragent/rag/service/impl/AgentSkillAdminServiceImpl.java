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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.controller.request.AgentSkillPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentSkillSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentSkillToolOptionVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentSkillVO;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.service.AgentSkillAdminService;
import com.nageoffer.ai.ragent.rag.skillpackage.SkillPackageCodec;
import com.nageoffer.ai.ragent.rag.skillpackage.SkillPackageData;
import com.nageoffer.ai.ragent.rag.skillpackage.SkillPackageError;
import com.nageoffer.ai.ragent.rag.skillpackage.SkillPackageException;
import com.nageoffer.ai.ragent.rag.skillpackage.SkillPackageExport;
import com.nageoffer.ai.ragent.rag.skillpackage.SkillPackageImportResult;
import com.nageoffer.ai.ragent.rag.skillpackage.SkillPackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentSkillAdminServiceImpl implements AgentSkillAdminService, SkillPackageService {

    /**
     * 技能标识是模型加载正文时报的名字，限定小写字母开头的蛇形串，中文和空格模型报不准
     */
    private static final Pattern SKILL_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    /**
     * 与 ai_agent_skill 的列宽一致
     */
    private static final int NAME_MAX_LENGTH = 64;
    private static final int DESCRIPTION_MAX_LENGTH = 512;

    private final AgentSkillMapper agentSkillMapper;
    private final AgentSkillCacheManager cacheManager;
    private final IntentNodeRegistry intentNodeRegistry;
    private final BizChangeLogContext bizChangeLogContext;
    private final ObjectMapper objectMapper;

    @Override
    public IPage<AgentSkillVO> pageQuery(AgentSkillPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<AgentSkillDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<AgentSkillDO> result = agentSkillMapper.selectPage(
                page,
                Wrappers.lambdaQuery(AgentSkillDO.class)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(AgentSkillDO::getSkillCode, keyword)
                                .or()
                                .like(AgentSkillDO::getName, keyword)
                                .or()
                                .like(AgentSkillDO::getDescription, keyword))
                        .orderByAsc(AgentSkillDO::getSortOrder)
                        .orderByDesc(AgentSkillDO::getUpdateTime)
        );
        return result.convert(record -> toVO(record, false));
    }

    @Override
    public AgentSkillVO queryById(String id) {
        return toVO(loadById(id), true);
    }

    @Override
    @LogRecord(
            success = "创建技能：{{#requestParam.name}}",
            fail = "创建技能失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public String create(AgentSkillSaveRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String skillCode = normalizeSkillCode(requestParam.getSkillCode());
        boolean duplicated = agentSkillMapper.exists(Wrappers.lambdaQuery(AgentSkillDO.class)
                .eq(AgentSkillDO::getSkillCode, skillCode));
        if (duplicated) {
            throw new ClientException("技能标识已存在: " + skillCode);
        }

        AgentSkillDO record = AgentSkillDO.builder()
                .skillCode(skillCode)
                .name(requireName(requestParam.getName()))
                .description(requireDescription(requestParam.getDescription()))
                .content(requireContent(requestParam.getContent()))
                .toolIds(validateToolIds(requestParam.getToolIds()))
                .sortOrder(requestParam.getSortOrder() == null ? 0 : requestParam.getSortOrder())
                .enabled(Boolean.FALSE.equals(requestParam.getEnabled()) ? 0 : 1)
                .build();
        agentSkillMapper.insert(record);
        cacheManager.clearCache();
        bizChangeLogContext.put(record.getId(), null, record);
        return record.getId();
    }

    @Override
    @LogRecord(
            success = "更新技能：{{#id}}",
            fail = "更新技能失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = BizChangeOperationType.UPDATE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void update(String id, AgentSkillSaveRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        AgentSkillDO record = loadById(id);
        AgentSkillDO before = BeanUtil.copyProperties(record, AgentSkillDO.class);

        // 技能标识不跟着改：正文里可能被别的技能按名引用，改名等于换了一个技能
        if (requestParam.getName() != null) {
            record.setName(requireName(requestParam.getName()));
        }
        if (requestParam.getDescription() != null) {
            record.setDescription(requireDescription(requestParam.getDescription()));
        }
        if (requestParam.getContent() != null) {
            record.setContent(requireContent(requestParam.getContent()));
        }
        if (requestParam.getToolIds() != null) {
            record.setToolIds(validateToolIds(requestParam.getToolIds()));
        }
        if (requestParam.getSortOrder() != null) {
            record.setSortOrder(requestParam.getSortOrder());
        }
        if (requestParam.getEnabled() != null) {
            record.setEnabled(requestParam.getEnabled() ? 1 : 0);
        }

        agentSkillMapper.updateById(record);
        cacheManager.clearCache();
        bizChangeLogContext.put(id, before, agentSkillMapper.selectById(id));
    }

    @Override
    @LogRecord(
            success = "删除技能：{{#id}}",
            fail = "删除技能失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = BizChangeOperationType.DELETE,
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void delete(String id) {
        AgentSkillDO record = loadById(id);
        agentSkillMapper.deleteById(record.getId());
        cacheManager.clearCache();
        bizChangeLogContext.put(id, record, null);
    }

    @Override
    @LogRecord(
            success = "{{#enabled ? '启用' : '停用'}}技能：{{#id}}",
            fail = "切换技能状态失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = "{{#enabled ? 'ENABLE' : 'DISABLE'}}",
            bizNo = "{{#id}}",
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public void toggleEnabled(String id, boolean enabled) {
        AgentSkillDO record = loadById(id);
        AgentSkillDO before = BeanUtil.copyProperties(record, AgentSkillDO.class);
        record.setEnabled(enabled ? 1 : 0);
        agentSkillMapper.updateById(record);
        cacheManager.clearCache();
        bizChangeLogContext.put(id, before, agentSkillMapper.selectById(id));
    }

    // ------------------------------------------------------------------ F11:4 技能包

    /**
     * 导入技能包：全部校验通过后才插入一行；冲突默认拒绝（零写入）。
     *
     * <p>身份/租户不取自包：manifest 白名单拒绝 {@code tenantId}/{@code createBy} 等未知字段；
     * 行落库时的 {@code tenant_id} 仍由请求线程的平台租户拦截器按当前主体注入。
     */
    @Override
    @LogRecord(
            success = "导入技能包",
            fail = "导入技能包失败：{{#_errorMsg}}",
            type = BizChangeBizType.AGENT_SKILL,
            subType = BizChangeOperationType.CREATE,
            bizNo = BizChangeLogContext.BIZ_ID_EXPRESSION,
            extra = BizChangeLogContext.SNAPSHOT_EXPRESSION,
            condition = BizChangeLogContext.RECORD_CONDITION
    )
    public SkillPackageImportResult importPackage(MultipartFile file) {
        byte[] zipBytes = SkillPackageCodec.readUpload(file);
        SkillPackageData data = SkillPackageCodec.read(zipBytes, objectMapper);
        List<String> toolIds = validateImportedToolIds(data.toolIds());

        // 冲突检查放在全部内容校验之后：坏包/坏工具引用不会先触碰数据库
        boolean duplicated = agentSkillMapper.exists(Wrappers.lambdaQuery(AgentSkillDO.class)
                .eq(AgentSkillDO::getSkillCode, data.skillCode()));
        if (duplicated) {
            throw new SkillPackageException(SkillPackageError.SKILL_CONFLICT, data.skillCode());
        }

        AgentSkillDO record = AgentSkillDO.builder()
                .skillCode(data.skillCode())
                .name(data.name())
                .description(data.description())
                .content(data.content())
                .toolIds(toolIds)
                .sortOrder(data.sortOrder())
                .enabled(data.enabled() ? 1 : 0)
                .build();
        try {
            agentSkillMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // exists 与 insert 之间的并发窗口由 (tenant_id, skill_code) 唯一索引兜底；
            // 驱动异常翻译成具名 409，不冒成 5xx。
            throw new SkillPackageException(SkillPackageError.SKILL_CONFLICT, data.skillCode());
        }
        cacheManager.clearCache();
        bizChangeLogContext.put(record.getId(), null, record);
        return new SkillPackageImportResult(record.getId(), record.getSkillCode());
    }

    /**
     * 导出技能包：只写 manifest.json + skill.md，不带内部 id/租户/创建人/凭据。
     */
    @Override
    public SkillPackageExport exportPackage(String id) {
        if (id == null || id.isBlank()) {
            throw new SkillPackageException(SkillPackageError.SKILL_NOT_FOUND);
        }
        AgentSkillDO record = agentSkillMapper.selectById(id);
        if (record == null) {
            throw new SkillPackageException(SkillPackageError.SKILL_NOT_FOUND);
        }
        List<String> toolIds = record.getToolIds() == null
                ? List.of()
                : record.getToolIds().stream().filter(Objects::nonNull).toList();
        SkillPackageData data = new SkillPackageData(
                record.getSkillCode(),
                record.getName(),
                record.getDescription(),
                toolIds,
                record.getSortOrder() == null ? 0 : record.getSortOrder(),
                record.getEnabled() == null || record.getEnabled() != 0,
                record.getContent() == null ? "" : record.getContent());
        return new SkillPackageExport(record.getSkillCode() + ".zip",
                SkillPackageCodec.write(data, objectMapper));
    }

    /**
     * 导入路径的工具引用校验：与创建共用"意图树可用 MCP 节点"判据，但错误码走技能包 400，
     * 不污染既有 CRUD 的 ClientException 语义。
     */
    private List<String> validateImportedToolIds(List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> available = availableToolIds();
        for (String toolId : toolIds) {
            if (!available.contains(toolId)) {
                throw new SkillPackageException(SkillPackageError.INVALID_PACKAGE,
                        "工具不在意图树的可用 MCP 节点中: " + toolId);
            }
        }
        return List.copyOf(toolIds);
    }

    @Override
    public List<AgentSkillToolOptionVO> listToolOptions() {
        Map<String, String> ownerBySkill = referencedToolOwners();
        List<AgentSkillToolOptionVO> options = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (IntentNode node : intentNodeRegistry.listMcpToolNodes()) {
            String toolId = StrUtil.trimToNull(node.getMcpToolId());
            // 同一工具挂多个意图时只出一个选项，展示名取先遇到的那个节点
            if (toolId == null || !seen.add(toolId)) {
                continue;
            }
            options.add(AgentSkillToolOptionVO.builder()
                    .toolId(toolId)
                    .name(StrUtil.isBlank(node.getName()) ? toolId : node.getName())
                    .description(node.getDescription())
                    .referencedBySkillName(ownerBySkill.get(toolId))
                    .build());
        }
        return options;
    }

    /**
     * 工具到引用它的技能名，多技能共用时保留先遇到的那个
     */
    private Map<String, String> referencedToolOwners() {
        Map<String, String> owners = new HashMap<>();
        List<AgentSkillDO> skills = agentSkillMapper.selectList(
                Wrappers.lambdaQuery(AgentSkillDO.class).orderByAsc(AgentSkillDO::getSortOrder));
        for (AgentSkillDO skill : skills) {
            if (skill.getToolIds() == null) {
                continue;
            }
            skill.getToolIds().forEach(toolId -> owners.putIfAbsent(toolId, skill.getName()));
        }
        return owners;
    }

    /**
     * 工具必须是意图树里已启用的 MCP 节点：这批工具才是接入方真正开放给 Agent 的
     */
    private List<String> validateToolIds(List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> available = availableToolIds();

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String toolId : toolIds) {
            String trimmed = StrUtil.trimToNull(toolId);
            if (trimmed == null) {
                continue;
            }
            if (!available.contains(trimmed)) {
                throw new ClientException("工具不在意图树的可用 MCP 节点中: " + trimmed);
            }
            selected.add(trimmed);
        }
        return List.copyOf(selected);
    }

    /**
     * 当前意图树里可引用的 MCP 工具 ID（创建与导入共用同一判据）。
     */
    private LinkedHashSet<String> availableToolIds() {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        intentNodeRegistry.listMcpToolNodes().forEach(node -> {
            String toolId = StrUtil.trimToNull(node.getMcpToolId());
            if (toolId != null) {
                available.add(toolId);
            }
        });
        return available;
    }

    private String normalizeSkillCode(String skillCode) {
        String trimmed = StrUtil.trimToNull(skillCode);
        Assert.notBlank(trimmed, () -> new ClientException("技能标识不能为空"));
        if (!SKILL_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new ClientException("技能标识只能用小写字母开头的字母、数字与下划线，长度 2~64");
        }
        return trimmed;
    }

    private String requireName(String name) {
        String trimmed = StrUtil.trimToNull(name);
        Assert.notBlank(trimmed, () -> new ClientException("技能名称不能为空"));
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new ClientException("技能名称不能超过 " + NAME_MAX_LENGTH + " 字");
        }
        return trimmed;
    }

    /**
     * 适用场景是模型判断要不要加载正文的唯一依据，留空等于这个技能永远轮不到
     */
    private String requireDescription(String description) {
        String trimmed = StrUtil.trimToNull(description);
        Assert.notBlank(trimmed, () -> new ClientException("适用场景不能为空，模型靠它判断何时该用这个技能"));
        if (trimmed.length() > DESCRIPTION_MAX_LENGTH) {
            throw new ClientException("适用场景不能超过 " + DESCRIPTION_MAX_LENGTH + " 字");
        }
        return trimmed;
    }

    private String requireContent(String content) {
        String trimmed = StrUtil.trimToNull(content);
        Assert.notBlank(trimmed, () -> new ClientException("技能正文不能为空"));
        return trimmed;
    }

    private AgentSkillDO loadById(String id) {
        AgentSkillDO record = agentSkillMapper.selectById(id);
        Assert.notNull(record, () -> new ClientException("技能不存在"));
        return record;
    }

    private AgentSkillVO toVO(AgentSkillDO record, boolean withContent) {
        return AgentSkillVO.builder()
                .id(record.getId())
                .skillCode(record.getSkillCode())
                .name(record.getName())
                .description(record.getDescription())
                .content(withContent ? record.getContent() : null)
                .toolIds(record.getToolIds() == null ? List.of() : record.getToolIds())
                .sortOrder(record.getSortOrder())
                .enabled(record.getEnabled() != null && record.getEnabled() == 1)
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
