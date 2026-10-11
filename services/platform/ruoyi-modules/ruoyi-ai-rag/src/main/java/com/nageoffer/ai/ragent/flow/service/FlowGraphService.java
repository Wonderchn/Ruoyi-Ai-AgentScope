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

package com.nageoffer.ai.ragent.flow.service;

import com.nageoffer.ai.ragent.flow.controller.request.FlowComponentSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowEdgeSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowNodeSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowComponentVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowEdgeVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowNodeVO;

import java.util.List;

/**
 * AIFlow 图定义服务（F13-A1 op0 余项：节点/边/组件库 CRUD）。
 *
 * <p>资源边界（与 {@link FlowWorkflowService} 同口径的两个独立维度）：
 * <ul>
 *   <li>节点/边：所属工作流必须过 {@code owner + tenant + 未删} 校验（「须作用于本人工作流」，
 *       E6-RW17 §5 第 6 条），各自再带 {@code tenant + 未删} 谓词；</li>
 *   <li>组件库：DDL 无 user_id 列 ⇒ 边界是 <b>tenant</b>（租户级库），不是 owner。</li>
 * </ul>
 * 本切片为可运行的最小 CRUD 骨架：图校验（环/可达性/IO schema）、跨实体引用完整性、
 * 并发控制（沿用 WP-028 模式）均不在本切片，见波报残余风险。
 */
public interface FlowGraphService {

    /** 本人工作流下的节点列表（按内部 id 升序）。 */
    List<FlowNodeVO> listNodes(String workflowUuid);

    /** 在本人工作流下新增节点。 */
    FlowNodeVO createNode(String workflowUuid, FlowNodeSaveRequest request);

    /** 更新本人工作流下的节点（标题/备注/位置/配置/组件引用）。 */
    FlowNodeVO updateNode(String workflowUuid, String nodeUuid, FlowNodeSaveRequest request);

    /** 逻辑删除本人工作流下的节点。 */
    void deleteNode(String workflowUuid, String nodeUuid);

    /** 本人工作流下的边列表（按内部 id 升序）。 */
    List<FlowEdgeVO> listEdges(String workflowUuid);

    /** 在本人工作流下新增边。 */
    FlowEdgeVO createEdge(String workflowUuid, FlowEdgeSaveRequest request);

    /** 更新本人工作流下的边（端点/锚点）。 */
    FlowEdgeVO updateEdge(String workflowUuid, String edgeUuid, FlowEdgeSaveRequest request);

    /** 逻辑删除本人工作流下的边。 */
    void deleteEdge(String workflowUuid, String edgeUuid);

    /** 本租户组件库列表（display_order 升序）。 */
    List<FlowComponentVO> listComponents();

    /** 新增组件库条目。 */
    FlowComponentVO createComponent(FlowComponentSaveRequest request);

    /** 更新组件库条目。 */
    FlowComponentVO updateComponent(String uuid, FlowComponentSaveRequest request);

    /** 逻辑删除组件库条目。 */
    void deleteComponent(String uuid);
}
