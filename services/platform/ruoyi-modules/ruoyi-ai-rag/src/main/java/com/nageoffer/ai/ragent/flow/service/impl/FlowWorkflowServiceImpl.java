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

package com.nageoffer.ai.ragent.flow.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.flow.controller.request.FlowWorkflowQueryRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowWorkflowSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowComponentVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowWorkflowVO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowComponentDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowWorkflowDO;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowComponentMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowWorkflowMapper;
import com.nageoffer.ai.ragent.flow.service.FlowWorkflowService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AIFlow 工作流定义服务实现。
 *
 * <p><b>资源边界（两个独立维度，都必须出现）</b>：
 * <ol>
 *   <li>{@code tenant_id = 当前主体 tenant} —— 跨租户同 uuid 与"不存在"同外显；</li>
 *   <li>{@code user_id = 当前主体 user} —— 「我的工作流」只返回本人的。</li>
 * </ol>
 * 这两个谓词分别覆盖维护者验收里"不能读到别的租户的工作流"与
 * "我的工作流搜索只返回本人的"两条，且<b>不是</b>在读到之后再过滤 ——
 * 谓词进 SQL，越权行不会离开数据库。
 *
 * <p><b>不降级到默认租户</b>：主体缺失即 {@link PrincipalContext#require()} 抛出，
 * 不写 {@code '000000'} 兜底（与裁决 §7 的教训一致）。
 *
 * <p><b>不物理删除</b>：删除置 {@code is_deleted=1}，与 DDL 注释"逻辑删除 默认0不删除"一致。
 * 所有读路径恒带 {@code is_deleted = 0}。
 *
 * <p><b>发布/公开面（F13-M1）的第三条边界口径</b>：公开 = <b>同租户、跨用户、只读</b>。
 * 公开搜索/详情固定 {@code is_public=1 AND is_enable=1 AND is_deleted=0 AND tenant_id=当前}，
 * 刻意<b>不带</b> owner 谓词（因此同租户另一用户可见），也<b>永不</b>跨租户可见；
 * 写面（enable/set-public）仍按 owner + tenant 限域，0 行即失败。
 */
@Service
@RequiredArgsConstructor
public class FlowWorkflowServiceImpl implements FlowWorkflowService {

    /** 租户谓词；与 KnowledgeChunkServiceImpl 同形（{0} 为绑定参数，非字符串拼接）。 */
    private static final String TENANT_PREDICATE = "tenant_id = {0}";

    /** 属主谓词（「我的工作流」）。 */
    private static final String OWNER_PREDICATE = "user_id = {0}";

    private static final int NOT_DELETED = 0;
    private static final int DELETED = 1;
    private static final int ENABLED = 1;
    private static final int NOT_ENABLED = 0;
    private static final int PUBLIC = 1;
    private static final int NOT_PUBLIC = 0;

    private final AiFlowWorkflowMapper workflowMapper;

    /** 组件库是租户级库（无 user_id 列）；公开组件列表复用同一 Mapper。 */
    private final AiFlowComponentMapper componentMapper;

    private static ExecutionPrincipal principal() {
        return PrincipalContext.require();
    }

    /**
     * 当前主体 userId。
     *
     * <p>{@code ExecutionPrincipal.userId()} 是 String，列是 bigint。
     * 解析失败<b>直接拒绝</b>，不做 0 兜底 —— 兜底会让谓词命中 {@code user_id = 0}
     * 的意外行，把越权变成"看起来没数据"。
     */
    private static Long requireUserId() {
        String raw = principal().userId();
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new ClientException("主体 userId 非数值，拒绝查询");
        }
    }

    private static String requireTenantId() {
        return principal().tenantId();
    }

    /**
     * 本人 + 本租户 + 未删除的单行读取。
     *
     * <p>越权与不存在返回<b>同一个</b>结果（not found），不区分 —— 否则
     * "存在但不属于你"与"不存在"的差异本身会泄漏他租户的资源存在性。
     */
    private AiFlowWorkflowDO selectMineOrThrow(String uuid) {
        if (StrUtil.isBlank(uuid)) {
            throw new ClientException("uuid 不能为空");
        }
        AiFlowWorkflowDO row = workflowMapper.selectOne(new LambdaQueryWrapper<AiFlowWorkflowDO>()
                .eq(AiFlowWorkflowDO::getUuid, uuid)
                .apply(OWNER_PREDICATE, requireUserId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED));
        if (row == null) {
            throw new ClientException("工作流不存在");
        }
        return row;
    }

    private static FlowWorkflowVO toVo(AiFlowWorkflowDO row) {
        FlowWorkflowVO vo = new FlowWorkflowVO();
        vo.setUuid(row.getUuid());
        vo.setTitle(row.getTitle());
        vo.setRemark(row.getRemark());
        vo.setIsPublic(row.getIsPublic());
        vo.setIsEnable(row.getIsEnable());
        vo.setUserId(row.getUserId());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    @Override
    public IPage<FlowWorkflowVO> searchMine(FlowWorkflowQueryRequest request) {
        FlowWorkflowQueryRequest query = (request == null) ? new FlowWorkflowQueryRequest() : request;
        LambdaQueryWrapper<AiFlowWorkflowDO> wrapper = new LambdaQueryWrapper<AiFlowWorkflowDO>()
                // 先钉住两个边界谓词，再叠加可选过滤：可选条件永远不能放宽边界
                .apply(OWNER_PREDICATE, requireUserId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED)
                .eq(query.getIsEnable() != null, AiFlowWorkflowDO::getIsEnable, query.getIsEnable())
                .like(StrUtil.isNotBlank(query.getKeyword()),
                        AiFlowWorkflowDO::getTitle, query.getKeyword())
                .orderByDesc(AiFlowWorkflowDO::getUpdateTime);

        Page<AiFlowWorkflowDO> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<AiFlowWorkflowDO> result = workflowMapper.selectPage(page, wrapper);
        return result.convert(FlowWorkflowServiceImpl::toVo);
    }

    @Override
    public FlowWorkflowVO getMine(String uuid) {
        return toVo(selectMineOrThrow(uuid));
    }

    @Override
    public FlowWorkflowVO create(FlowWorkflowSaveRequest request) {
        AiFlowWorkflowDO row = new AiFlowWorkflowDO();
        row.setUuid(IdUtil.fastSimpleUUID());
        row.setTitle(request.getTitle());
        row.setRemark(request.getRemark());
        row.setIsEnable(request.getIsEnable() == null ? ENABLED : request.getIsEnable());
        // 新建不公开：公开是 op1 的事，本卡不放行公开开关
        row.setIsPublic(NOT_PUBLIC);
        row.setIsDeleted(NOT_DELETED);
        row.setUserId(requireUserId());
        row.setTenantId(requireTenantId());
        workflowMapper.insert(row);
        return toVo(selectMineOrThrow(row.getUuid()));
    }

    @Override
    public FlowWorkflowVO updateBasicInfo(String uuid, FlowWorkflowSaveRequest request) {
        AiFlowWorkflowDO existing = selectMineOrThrow(uuid);
        AiFlowWorkflowDO patch = new AiFlowWorkflowDO();
        patch.setId(existing.getId());
        patch.setTitle(request.getTitle());
        patch.setRemark(request.getRemark());
        if (request.getIsEnable() != null) {
            patch.setIsEnable(request.getIsEnable());
        }
        // 按 id + 两个边界谓词更新：0 行即视为越权/不存在，不静默成功
        int updated = workflowMapper.update(patch, new LambdaQueryWrapper<AiFlowWorkflowDO>()
                .eq(AiFlowWorkflowDO::getId, existing.getId())
                .apply(OWNER_PREDICATE, requireUserId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("工作流更新未生效");
        }
        return toVo(selectMineOrThrow(uuid));
    }

    @Override
    public void delete(String uuid) {
        AiFlowWorkflowDO existing = selectMineOrThrow(uuid);
        AiFlowWorkflowDO patch = new AiFlowWorkflowDO();
        patch.setId(existing.getId());
        patch.setIsDeleted(DELETED);
        int updated = workflowMapper.update(patch, new LambdaQueryWrapper<AiFlowWorkflowDO>()
                .eq(AiFlowWorkflowDO::getId, existing.getId())
                .apply(OWNER_PREDICATE, requireUserId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("工作流删除未生效");
        }
    }

    // ------------------------------------------------------------------ 发布/公开（F13-M1）

    /**
     * 启用/停用：写面仍按「id + owner + tenant + 未删」限域（与 update/delete 同形）。
     *
     * <p><b>只写 is_enable</b>：公开标志保持不变 —— 维护者批的语义是
     * 「启用与否不改变公开标志」，两条开关互不联动。
     */
    @Override
    public void enable(String uuid, boolean enabled) {
        AiFlowWorkflowDO existing = selectMineOrThrow(uuid);
        AiFlowWorkflowDO patch = new AiFlowWorkflowDO();
        patch.setId(existing.getId());
        patch.setIsEnable(enabled ? ENABLED : NOT_ENABLED);
        int updated = workflowMapper.update(patch, new LambdaQueryWrapper<AiFlowWorkflowDO>()
                .eq(AiFlowWorkflowDO::getId, existing.getId())
                .apply(OWNER_PREDICATE, requireUserId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("工作流启用状态更新未生效");
        }
    }

    /**
     * 设置/取消公开：写面仍按「id + owner + tenant + 未删」限域。
     *
     * <p><b>只写 is_public</b>：启用标志保持不变（见 {@link #enable}）。
     */
    @Override
    public void setPublic(String uuid, boolean isPublic) {
        AiFlowWorkflowDO existing = selectMineOrThrow(uuid);
        AiFlowWorkflowDO patch = new AiFlowWorkflowDO();
        patch.setId(existing.getId());
        patch.setIsPublic(isPublic ? PUBLIC : NOT_PUBLIC);
        int updated = workflowMapper.update(patch, new LambdaQueryWrapper<AiFlowWorkflowDO>()
                .eq(AiFlowWorkflowDO::getId, existing.getId())
                .apply(OWNER_PREDICATE, requireUserId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("工作流公开状态更新未生效");
        }
    }

    /**
     * 公开搜索：<b>同租户、跨用户、只读</b>。
     *
     * <p>可见性谓词固定为
     * {@code tenant_id=当前 AND is_public=1 AND is_enable=1 AND is_deleted=0}，
     * <b>没有</b> {@code user_id} 谓词（同租户另一用户可见）。
     * 入参的 {@code isEnable} 被<b>刻意忽略</b>：公开面恒只列启用项，
     * 客户端不能用它放宽/替换可见性条件。跨租户由 tenant 谓词排除。
     *
     * <p>返回视图不含所有者 id：公开只读面不暴露 {@code userId}。
     */
    @Override
    public IPage<FlowWorkflowVO> searchPublic(FlowWorkflowQueryRequest request) {
        FlowWorkflowQueryRequest query = (request == null) ? new FlowWorkflowQueryRequest() : request;
        LambdaQueryWrapper<AiFlowWorkflowDO> wrapper = new LambdaQueryWrapper<AiFlowWorkflowDO>()
                // 先钉住边界与公开可见性谓词，再叠加可选过滤：可选条件永远不能放宽边界
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsPublic, PUBLIC)
                .eq(AiFlowWorkflowDO::getIsEnable, ENABLED)
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED)
                .like(StrUtil.isNotBlank(query.getKeyword()),
                        AiFlowWorkflowDO::getTitle, query.getKeyword())
                .orderByDesc(AiFlowWorkflowDO::getUpdateTime);

        Page<AiFlowWorkflowDO> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<AiFlowWorkflowDO> result = workflowMapper.selectPage(page, wrapper);
        return result.convert(FlowWorkflowServiceImpl::toPublicVo);
    }

    /**
     * 公开详情：与公开搜索同一组可见性谓词
     * （{@code uuid + tenant + is_public=1 + is_enable=1 + is_deleted=0}）。
     *
     * <p>不存在/停用/私有/软删/跨租户一律返回 {@code null}，由控制器映射为
     * <b>同一个 404 文案</b>——不区分「不存在」与「存在但不可见」，不泄露存在性。
     */
    @Override
    public FlowWorkflowVO getPublic(String uuid) {
        if (StrUtil.isBlank(uuid)) {
            return null;
        }
        AiFlowWorkflowDO row = workflowMapper.selectOne(new LambdaQueryWrapper<AiFlowWorkflowDO>()
                .eq(AiFlowWorkflowDO::getUuid, uuid)
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsPublic, PUBLIC)
                .eq(AiFlowWorkflowDO::getIsEnable, ENABLED)
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED));
        return row == null ? null : toPublicVo(row);
    }

    /**
     * 公开组件库：当前租户中启用且未删除的组件（只读，无 owner 维度 —— 组件表没有 user_id）。
     */
    @Override
    public List<FlowComponentVO> listPublicComponents() {
        return componentMapper.selectList(new LambdaQueryWrapper<AiFlowComponentDO>()
                        .apply(TENANT_PREDICATE, requireTenantId())
                        .eq(AiFlowComponentDO::getIsEnable, ENABLED)
                        .eq(AiFlowComponentDO::getIsDeleted, NOT_DELETED)
                        .orderByAsc(AiFlowComponentDO::getDisplayOrder)
                        .orderByAsc(AiFlowComponentDO::getId))
                .stream().map(FlowWorkflowServiceImpl::toComponentVo).toList();
    }

    /**
     * 公开视图：与 {@link #toVo} 相同的公开字段，但 <b>{@code userId} 置空</b>。
     *
     * <p>公开面不暴露所有者 id；租户 id 本来就从不外泄。字段集合保持与
     * {@code FlowWorkflowVO} 一致（uuid/title/remark/isPublic/isEnable/createTime/updateTime），
     * 不新增公开 DTO（本切片白名单只允许新增操作符 VO）。
     */
    private static FlowWorkflowVO toPublicVo(AiFlowWorkflowDO row) {
        FlowWorkflowVO vo = new FlowWorkflowVO();
        vo.setUuid(row.getUuid());
        vo.setTitle(row.getTitle());
        vo.setRemark(row.getRemark());
        vo.setIsPublic(row.getIsPublic());
        vo.setIsEnable(row.getIsEnable());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    private static FlowComponentVO toComponentVo(AiFlowComponentDO row) {
        FlowComponentVO vo = new FlowComponentVO();
        vo.setUuid(row.getUuid());
        vo.setName(row.getName());
        vo.setTitle(row.getTitle());
        vo.setRemark(row.getRemark());
        vo.setDisplayOrder(row.getDisplayOrder());
        vo.setIsEnable(row.getIsEnable());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }
}
