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
import com.nageoffer.ai.ragent.flow.controller.request.FlowComponentSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowEdgeSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowNodeSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowComponentVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowEdgeVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowNodeVO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowComponentDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowEdgeDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowNodeDO;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowWorkflowDO;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowComponentMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowEdgeMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowNodeMapper;
import com.nageoffer.ai.ragent.flow.dao.mapper.AiFlowWorkflowMapper;
import com.nageoffer.ai.ragent.flow.service.FlowGraphService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AIFlow 图定义服务实现（节点/边/组件）。
 *
 * <p><b>资源边界（与 {@code FlowWorkflowServiceImpl} 同形，两个独立维度都必须出现）</b>：
 * <ol>
 *   <li>写入目标工作流 <b>先</b>过 {@code uuid + user_id + tenant_id + is_deleted=0} 的
 *       本人工作流解析——越权与不存在同形「工作流不存在」（不泄漏他租户/他人资源存在性）；</li>
 *   <li>节点/边各自再带 {@code workflow_id + tenant_id + is_deleted=0} 谓词；
 *       组件库没有 user_id 列（DDL 事实）⇒ 组件边界是 tenant。</li>
 * </ol>
 * 谓词全部进 SQL（绑定参数 {@code {0}}，非字符串拼接）；0 行更新即抛，不静默成功。
 *
 * <p><b>不降级到默认租户</b>：主体缺失即抛（{@code PrincipalContext.require()}），
 * 不写 {@code '000000'} 兜底（与裁决 §7 的教训一致；MyBatis-Plus 租户行拦截器
 * 对四张表自动加租户谓词，与本层显式谓词构成双层）。
 *
 * <p><b>不物理删除</b>：删除置 {@code is_deleted=1}，所有读路径恒带 {@code is_deleted=0}。
 *
 * <p><b>本切片刻意不做</b>（记入波报残余风险，留后续切片）：图校验（环/不可达/IO schema）、
 * 节点→组件的引用完整性、边的端点存在性、并发编辑控制（WP-028 模式）。当前为最小可运行
 * CRUD 骨架。
 */
@Service
@RequiredArgsConstructor
public class FlowGraphServiceImpl implements FlowGraphService {

    /** 租户谓词；与 FlowWorkflowServiceImpl 同形（{0} 为绑定参数，非字符串拼接）。 */
    private static final String TENANT_PREDICATE = "tenant_id = {0}";

    /** 属主谓词（工作流解析用）。 */
    private static final String OWNER_PREDICATE = "user_id = {0}";

    private static final int NOT_DELETED = 0;
    private static final int DELETED = 1;
    private static final int ENABLED = 1;
    private static final double DEFAULT_POSITION = 0.0;
    private static final long NO_COMPONENT = 0L;

    /** 节点 input_config 列 NOT NULL 且无默认值：缺省兜底空 JSON 对象。 */
    private static final String EMPTY_JSON_OBJECT = "{}";

    private final AiFlowWorkflowMapper workflowMapper;
    private final AiFlowNodeMapper nodeMapper;
    private final AiFlowEdgeMapper edgeMapper;
    private final AiFlowComponentMapper componentMapper;

    private static ExecutionPrincipal principal() {
        return PrincipalContext.require();
    }

    /**
     * 当前主体 userId（列是 bigint）。解析失败直接拒绝，不做 0 兜底
     * —— 兜底会让谓词命中 user_id = 0 的意外行（与 FlowWorkflowServiceImpl 同一教训）。
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
     * 解析「本人 + 本租户 + 未删」的工作流；越权与不存在同形（not found），不区分。
     */
    private AiFlowWorkflowDO resolveWorkflowOrThrow(String workflowUuid) {
        if (StrUtil.isBlank(workflowUuid)) {
            throw new ClientException("工作流 uuid 不能为空");
        }
        AiFlowWorkflowDO row = workflowMapper.selectOne(new LambdaQueryWrapper<AiFlowWorkflowDO>()
                .eq(AiFlowWorkflowDO::getUuid, workflowUuid)
                .apply(OWNER_PREDICATE, requireUserId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowWorkflowDO::getIsDeleted, NOT_DELETED));
        if (row == null) {
            throw new ClientException("工作流不存在");
        }
        return row;
    }

    // ------------------------------------------------------------------ 节点

    @Override
    public List<FlowNodeVO> listNodes(String workflowUuid) {
        AiFlowWorkflowDO workflow = resolveWorkflowOrThrow(workflowUuid);
        return nodeMapper.selectList(new LambdaQueryWrapper<AiFlowNodeDO>()
                        .eq(AiFlowNodeDO::getWorkflowId, workflow.getId())
                        .apply(TENANT_PREDICATE, requireTenantId())
                        .eq(AiFlowNodeDO::getIsDeleted, NOT_DELETED)
                        .orderByAsc(AiFlowNodeDO::getId))
                .stream().map(FlowGraphServiceImpl::toVo).toList();
    }

    @Override
    public FlowNodeVO createNode(String workflowUuid, FlowNodeSaveRequest request) {
        AiFlowWorkflowDO workflow = resolveWorkflowOrThrow(workflowUuid);
        AiFlowNodeDO row = new AiFlowNodeDO();
        row.setUuid(IdUtil.fastSimpleUUID());
        row.setWorkflowId(workflow.getId());
        row.setTitle(request.getTitle());
        row.setRemark(request.getRemark() == null ? "" : request.getRemark());
        row.setWorkflowComponentId(request.getWorkflowComponentId() == null
                ? NO_COMPONENT : request.getWorkflowComponentId());
        row.setInputConfig(StrUtil.isBlank(request.getInputConfig())
                ? EMPTY_JSON_OBJECT : request.getInputConfig());
        row.setNodeConfig(request.getNodeConfig());
        row.setPositionX(request.getPositionX() == null ? DEFAULT_POSITION : request.getPositionX());
        row.setPositionY(request.getPositionY() == null ? DEFAULT_POSITION : request.getPositionY());
        row.setUserId(requireUserId());
        row.setIsDeleted(NOT_DELETED);
        row.setTenantId(requireTenantId());
        nodeMapper.insert(row);
        return toVo(row);
    }

    @Override
    public FlowNodeVO updateNode(String workflowUuid, String nodeUuid, FlowNodeSaveRequest request) {
        AiFlowWorkflowDO workflow = resolveWorkflowOrThrow(workflowUuid);
        AiFlowNodeDO existing = selectNodeOrThrow(workflow.getId(), nodeUuid);

        AiFlowNodeDO patch = new AiFlowNodeDO();
        patch.setId(existing.getId());
        patch.setTitle(request.getTitle());
        if (request.getRemark() != null) {
            patch.setRemark(request.getRemark());
        }
        if (request.getWorkflowComponentId() != null) {
            patch.setWorkflowComponentId(request.getWorkflowComponentId());
        }
        if (StrUtil.isNotBlank(request.getInputConfig())) {
            patch.setInputConfig(request.getInputConfig());
        }
        if (request.getNodeConfig() != null) {
            patch.setNodeConfig(request.getNodeConfig());
        }
        if (request.getPositionX() != null) {
            patch.setPositionX(request.getPositionX());
        }
        if (request.getPositionY() != null) {
            patch.setPositionY(request.getPositionY());
        }
        // 按 id + workflow_id + tenant + 未删更新：0 行即视为越权/不存在，不静默成功
        int updated = nodeMapper.update(patch, new LambdaQueryWrapper<AiFlowNodeDO>()
                .eq(AiFlowNodeDO::getId, existing.getId())
                .eq(AiFlowNodeDO::getWorkflowId, workflow.getId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowNodeDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("节点更新未生效");
        }
        return toVo(mergedNode(existing, request));
    }

    @Override
    public void deleteNode(String workflowUuid, String nodeUuid) {
        AiFlowWorkflowDO workflow = resolveWorkflowOrThrow(workflowUuid);
        AiFlowNodeDO existing = selectNodeOrThrow(workflow.getId(), nodeUuid);
        AiFlowNodeDO patch = new AiFlowNodeDO();
        patch.setId(existing.getId());
        patch.setIsDeleted(DELETED);
        int updated = nodeMapper.update(patch, new LambdaQueryWrapper<AiFlowNodeDO>()
                .eq(AiFlowNodeDO::getId, existing.getId())
                .eq(AiFlowNodeDO::getWorkflowId, workflow.getId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowNodeDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("节点删除未生效");
        }
    }

    private AiFlowNodeDO selectNodeOrThrow(Long workflowId, String nodeUuid) {
        if (StrUtil.isBlank(nodeUuid)) {
            throw new ClientException("节点 uuid 不能为空");
        }
        AiFlowNodeDO row = nodeMapper.selectOne(new LambdaQueryWrapper<AiFlowNodeDO>()
                .eq(AiFlowNodeDO::getUuid, nodeUuid)
                .eq(AiFlowNodeDO::getWorkflowId, workflowId)
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowNodeDO::getIsDeleted, NOT_DELETED));
        if (row == null) {
            throw new ClientException("节点不存在");
        }
        return row;
    }

    /** 更新响应视图：以库中行为底、叠加本次请求已生效的字段（不二次查询）。 */
    private static AiFlowNodeDO mergedNode(AiFlowNodeDO existing, FlowNodeSaveRequest request) {
        AiFlowNodeDO merged = new AiFlowNodeDO();
        merged.setId(existing.getId());
        merged.setUuid(existing.getUuid());
        merged.setWorkflowId(existing.getWorkflowId());
        merged.setUserId(existing.getUserId());
        merged.setCreateTime(existing.getCreateTime());
        merged.setTitle(request.getTitle());
        merged.setRemark(request.getRemark() != null ? request.getRemark() : existing.getRemark());
        merged.setWorkflowComponentId(request.getWorkflowComponentId() != null
                ? request.getWorkflowComponentId() : existing.getWorkflowComponentId());
        merged.setInputConfig(StrUtil.isNotBlank(request.getInputConfig())
                ? request.getInputConfig() : existing.getInputConfig());
        merged.setNodeConfig(request.getNodeConfig() != null
                ? request.getNodeConfig() : existing.getNodeConfig());
        merged.setPositionX(request.getPositionX() != null ? request.getPositionX() : existing.getPositionX());
        merged.setPositionY(request.getPositionY() != null ? request.getPositionY() : existing.getPositionY());
        return merged;
    }

    private static FlowNodeVO toVo(AiFlowNodeDO row) {
        FlowNodeVO vo = new FlowNodeVO();
        vo.setUuid(row.getUuid());
        vo.setWorkflowId(row.getWorkflowId());
        vo.setTitle(row.getTitle());
        vo.setRemark(row.getRemark());
        vo.setWorkflowComponentId(row.getWorkflowComponentId());
        vo.setInputConfig(row.getInputConfig());
        vo.setNodeConfig(row.getNodeConfig());
        vo.setPositionX(row.getPositionX());
        vo.setPositionY(row.getPositionY());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    // ------------------------------------------------------------------ 边

    @Override
    public List<FlowEdgeVO> listEdges(String workflowUuid) {
        AiFlowWorkflowDO workflow = resolveWorkflowOrThrow(workflowUuid);
        return edgeMapper.selectList(new LambdaQueryWrapper<AiFlowEdgeDO>()
                        .eq(AiFlowEdgeDO::getWorkflowId, workflow.getId())
                        .apply(TENANT_PREDICATE, requireTenantId())
                        .eq(AiFlowEdgeDO::getIsDeleted, NOT_DELETED)
                        .orderByAsc(AiFlowEdgeDO::getId))
                .stream().map(FlowGraphServiceImpl::toVo).toList();
    }

    @Override
    public FlowEdgeVO createEdge(String workflowUuid, FlowEdgeSaveRequest request) {
        AiFlowWorkflowDO workflow = resolveWorkflowOrThrow(workflowUuid);
        AiFlowEdgeDO row = new AiFlowEdgeDO();
        row.setUuid(IdUtil.fastSimpleUUID());
        row.setWorkflowId(workflow.getId());
        row.setSourceNodeUuid(request.getSourceNodeUuid());
        row.setSourceHandle(request.getSourceHandle() == null ? "" : request.getSourceHandle());
        row.setTargetNodeUuid(request.getTargetNodeUuid());
        row.setIsDeleted(NOT_DELETED);
        row.setTenantId(requireTenantId());
        edgeMapper.insert(row);
        return toVo(row);
    }

    @Override
    public FlowEdgeVO updateEdge(String workflowUuid, String edgeUuid, FlowEdgeSaveRequest request) {
        AiFlowWorkflowDO workflow = resolveWorkflowOrThrow(workflowUuid);
        AiFlowEdgeDO existing = selectEdgeOrThrow(workflow.getId(), edgeUuid);

        AiFlowEdgeDO patch = new AiFlowEdgeDO();
        patch.setId(existing.getId());
        patch.setSourceNodeUuid(request.getSourceNodeUuid());
        if (request.getSourceHandle() != null) {
            patch.setSourceHandle(request.getSourceHandle());
        }
        patch.setTargetNodeUuid(request.getTargetNodeUuid());
        int updated = edgeMapper.update(patch, new LambdaQueryWrapper<AiFlowEdgeDO>()
                .eq(AiFlowEdgeDO::getId, existing.getId())
                .eq(AiFlowEdgeDO::getWorkflowId, workflow.getId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowEdgeDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("边更新未生效");
        }
        AiFlowEdgeDO merged = new AiFlowEdgeDO();
        merged.setId(existing.getId());
        merged.setUuid(existing.getUuid());
        merged.setWorkflowId(existing.getWorkflowId());
        merged.setCreateTime(existing.getCreateTime());
        merged.setSourceNodeUuid(request.getSourceNodeUuid());
        merged.setSourceHandle(request.getSourceHandle() != null
                ? request.getSourceHandle() : existing.getSourceHandle());
        merged.setTargetNodeUuid(request.getTargetNodeUuid());
        return toVo(merged);
    }

    @Override
    public void deleteEdge(String workflowUuid, String edgeUuid) {
        AiFlowWorkflowDO workflow = resolveWorkflowOrThrow(workflowUuid);
        AiFlowEdgeDO existing = selectEdgeOrThrow(workflow.getId(), edgeUuid);
        AiFlowEdgeDO patch = new AiFlowEdgeDO();
        patch.setId(existing.getId());
        patch.setIsDeleted(DELETED);
        int updated = edgeMapper.update(patch, new LambdaQueryWrapper<AiFlowEdgeDO>()
                .eq(AiFlowEdgeDO::getId, existing.getId())
                .eq(AiFlowEdgeDO::getWorkflowId, workflow.getId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowEdgeDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("边删除未生效");
        }
    }

    private AiFlowEdgeDO selectEdgeOrThrow(Long workflowId, String edgeUuid) {
        if (StrUtil.isBlank(edgeUuid)) {
            throw new ClientException("边 uuid 不能为空");
        }
        AiFlowEdgeDO row = edgeMapper.selectOne(new LambdaQueryWrapper<AiFlowEdgeDO>()
                .eq(AiFlowEdgeDO::getUuid, edgeUuid)
                .eq(AiFlowEdgeDO::getWorkflowId, workflowId)
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowEdgeDO::getIsDeleted, NOT_DELETED));
        if (row == null) {
            throw new ClientException("边不存在");
        }
        return row;
    }

    private static FlowEdgeVO toVo(AiFlowEdgeDO row) {
        FlowEdgeVO vo = new FlowEdgeVO();
        vo.setUuid(row.getUuid());
        vo.setWorkflowId(row.getWorkflowId());
        vo.setSourceNodeUuid(row.getSourceNodeUuid());
        vo.setSourceHandle(row.getSourceHandle());
        vo.setTargetNodeUuid(row.getTargetNodeUuid());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    // ------------------------------------------------------------------ 组件库

    @Override
    public List<FlowComponentVO> listComponents() {
        return componentMapper.selectList(new LambdaQueryWrapper<AiFlowComponentDO>()
                        .apply(TENANT_PREDICATE, requireTenantId())
                        .eq(AiFlowComponentDO::getIsDeleted, NOT_DELETED)
                        .orderByAsc(AiFlowComponentDO::getDisplayOrder)
                        .orderByAsc(AiFlowComponentDO::getId))
                .stream().map(FlowGraphServiceImpl::toVo).toList();
    }

    @Override
    public FlowComponentVO createComponent(FlowComponentSaveRequest request) {
        AiFlowComponentDO row = new AiFlowComponentDO();
        row.setUuid(IdUtil.fastSimpleUUID());
        row.setName(request.getName());
        row.setTitle(request.getTitle() == null ? "" : request.getTitle());
        // DDL 该列 NOT NULL：兜底空串
        row.setRemark(request.getRemark() == null ? "" : request.getRemark());
        row.setDisplayOrder(request.getDisplayOrder() == null ? 0 : request.getDisplayOrder());
        row.setIsEnable(request.getIsEnable() == null ? ENABLED : request.getIsEnable());
        row.setIsDeleted(NOT_DELETED);
        row.setTenantId(requireTenantId());
        componentMapper.insert(row);
        return toVo(row);
    }

    @Override
    public FlowComponentVO updateComponent(String uuid, FlowComponentSaveRequest request) {
        AiFlowComponentDO existing = selectComponentOrThrow(uuid);

        AiFlowComponentDO patch = new AiFlowComponentDO();
        patch.setId(existing.getId());
        patch.setName(request.getName());
        if (request.getTitle() != null) {
            patch.setTitle(request.getTitle());
        }
        if (request.getRemark() != null) {
            patch.setRemark(request.getRemark());
        }
        if (request.getDisplayOrder() != null) {
            patch.setDisplayOrder(request.getDisplayOrder());
        }
        if (request.getIsEnable() != null) {
            patch.setIsEnable(request.getIsEnable());
        }
        int updated = componentMapper.update(patch, new LambdaQueryWrapper<AiFlowComponentDO>()
                .eq(AiFlowComponentDO::getId, existing.getId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowComponentDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("组件更新未生效");
        }
        AiFlowComponentDO merged = new AiFlowComponentDO();
        merged.setId(existing.getId());
        merged.setUuid(existing.getUuid());
        merged.setCreateTime(existing.getCreateTime());
        merged.setName(request.getName());
        merged.setTitle(request.getTitle() != null ? request.getTitle() : existing.getTitle());
        merged.setRemark(request.getRemark() != null ? request.getRemark() : existing.getRemark());
        merged.setDisplayOrder(request.getDisplayOrder() != null
                ? request.getDisplayOrder() : existing.getDisplayOrder());
        merged.setIsEnable(request.getIsEnable() != null ? request.getIsEnable() : existing.getIsEnable());
        return toVo(merged);
    }

    @Override
    public void deleteComponent(String uuid) {
        AiFlowComponentDO existing = selectComponentOrThrow(uuid);
        AiFlowComponentDO patch = new AiFlowComponentDO();
        patch.setId(existing.getId());
        patch.setIsDeleted(DELETED);
        int updated = componentMapper.update(patch, new LambdaQueryWrapper<AiFlowComponentDO>()
                .eq(AiFlowComponentDO::getId, existing.getId())
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowComponentDO::getIsDeleted, NOT_DELETED));
        if (updated != 1) {
            throw new ClientException("组件删除未生效");
        }
    }

    private AiFlowComponentDO selectComponentOrThrow(String uuid) {
        if (StrUtil.isBlank(uuid)) {
            throw new ClientException("组件 uuid 不能为空");
        }
        AiFlowComponentDO row = componentMapper.selectOne(new LambdaQueryWrapper<AiFlowComponentDO>()
                .eq(AiFlowComponentDO::getUuid, uuid)
                .apply(TENANT_PREDICATE, requireTenantId())
                .eq(AiFlowComponentDO::getIsDeleted, NOT_DELETED));
        if (row == null) {
            throw new ClientException("组件不存在");
        }
        return row;
    }

    private static FlowComponentVO toVo(AiFlowComponentDO row) {
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
