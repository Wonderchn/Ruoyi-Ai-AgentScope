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

package com.nageoffer.ai.ragent.flow.controller;

import com.nageoffer.ai.ragent.flow.controller.request.FlowComponentSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowEdgeSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowNodeSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowComponentVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowEdgeVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowNodeVO;
import com.nageoffer.ai.ragent.flow.service.FlowGraphService;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AIFlow 图定义接口（F13-A1 op0 余项：节点 / 边 / 组件库 CRUD）。
 *
 * <p><b>与 {@code FlowWorkflowController} 同形</b>（该先例的全部约束在此逐条成立）：
 * <ul>
 *   <li><b>路径面</b>：类级前缀 {@code /internal/ai/v1}，直接访问被
 *       {@code AiInternalAccessBoundaryFilter} 关成 404；公开面经网关白名单
 *       {@code /api/ai/v1/flows/{uuid}/nodes|edges、/api/ai/v1/flow-components}；</li>
 *   <li><b>装配三件套</b>：本类不在 platform 扫描根下 ⇒ 由 {@code AiEmbeddedFlowConfiguration}
 *       显式 {@code @Import}；Mapper 包已在 {@code AiEmbeddedMapperConfiguration} 的
 *       {@code @MapperScan} 数组内（同包新增）；路由在 {@code AiGatewayController.ROUTES} 逐条登记；</li>
 *   <li><b>信封</b>：全部返回整数 code 的 {@link ApiEnvelope}（字符串 code 经网关必然 503）；</li>
 *   <li><b>GET 走字节分支</b>：三条 GET 必须铸交付回执头，失败路径自行 {@code close()}；
 *       写面走 JSON 分支，<b>不铸回执</b>（JSON 分支铸造 = 永久 ACTIVE 许可泄漏）；</li>
 *   <li><b>授权不在本层</b>：读图 = {@code flow.read}，写图 = {@code flow.node.write}
 *       （→ {@code ai:flow:design}，V32/7203 注册）由网关按 canonical 动作判定；
 *       本层只做资源边界（本人工作流 + 本租户 / 组件库租户级）。</li>
 * </ul>
 *
 * <p><b>本切片边界</b>：最小可运行 CRUD 骨架。图校验（环/可达性/IO schema）、
 * 跨实体引用完整性、并发控制不在本层，见波报残余风险。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
@Validated
public class FlowGraphController {

    private final FlowGraphService flowGraphService;

    /** 交付许可铸造器（GET 走字节分支，必须带回执头；见类注释）。 */
    private final DeliveryPermits deliveryPermits;

    // ------------------------------------------------------------------ 节点

    /**
     * 工作流节点列表（读图；动作 flow.read）。
     */
    @GetMapping("/flows/{uuid}/nodes")
    public ResponseEntity<ApiEnvelope<List<FlowNodeVO>>> listNodes(@PathVariable("uuid") String uuid) {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<FlowNodeVO> nodes = flowGraphService.listNodes(uuid);
        // 先取数据再铸许可：数据侧失败时不留下需要回收的 ACTIVE 许可
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "flow.read", "flow:" + uuid);
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(nodes));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 新增节点（写图；动作 flow.node.write）。
     */
    @PostMapping("/flows/{uuid}/nodes")
    public ApiEnvelope<FlowNodeVO> createNode(@PathVariable("uuid") String uuid,
                                              @Validated @RequestBody FlowNodeSaveRequest request) {
        return ApiEnvelope.ok(flowGraphService.createNode(uuid, request));
    }

    /**
     * 更新节点（写图；动作 flow.node.write）。
     */
    @PutMapping("/flows/{uuid}/nodes/{nodeUuid}")
    public ApiEnvelope<FlowNodeVO> updateNode(@PathVariable("uuid") String uuid,
                                              @PathVariable("nodeUuid") String nodeUuid,
                                              @Validated @RequestBody FlowNodeSaveRequest request) {
        return ApiEnvelope.ok(flowGraphService.updateNode(uuid, nodeUuid, request));
    }

    /**
     * 逻辑删除节点（写图；动作 flow.node.write）。
     */
    @DeleteMapping("/flows/{uuid}/nodes/{nodeUuid}")
    public ApiEnvelope<Void> deleteNode(@PathVariable("uuid") String uuid,
                                        @PathVariable("nodeUuid") String nodeUuid) {
        flowGraphService.deleteNode(uuid, nodeUuid);
        return ApiEnvelope.ok(null);
    }

    // ------------------------------------------------------------------ 边

    /**
     * 工作流边列表（读图；动作 flow.read）。
     */
    @GetMapping("/flows/{uuid}/edges")
    public ResponseEntity<ApiEnvelope<List<FlowEdgeVO>>> listEdges(@PathVariable("uuid") String uuid) {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<FlowEdgeVO> edges = flowGraphService.listEdges(uuid);
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "flow.read", "flow:" + uuid);
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(edges));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 新增边（写图；动作 flow.node.write）。
     */
    @PostMapping("/flows/{uuid}/edges")
    public ApiEnvelope<FlowEdgeVO> createEdge(@PathVariable("uuid") String uuid,
                                              @Validated @RequestBody FlowEdgeSaveRequest request) {
        return ApiEnvelope.ok(flowGraphService.createEdge(uuid, request));
    }

    /**
     * 更新边（写图；动作 flow.node.write）。
     */
    @PutMapping("/flows/{uuid}/edges/{edgeUuid}")
    public ApiEnvelope<FlowEdgeVO> updateEdge(@PathVariable("uuid") String uuid,
                                              @PathVariable("edgeUuid") String edgeUuid,
                                              @Validated @RequestBody FlowEdgeSaveRequest request) {
        return ApiEnvelope.ok(flowGraphService.updateEdge(uuid, edgeUuid, request));
    }

    /**
     * 逻辑删除边（写图；动作 flow.node.write）。
     */
    @DeleteMapping("/flows/{uuid}/edges/{edgeUuid}")
    public ApiEnvelope<Void> deleteEdge(@PathVariable("uuid") String uuid,
                                        @PathVariable("edgeUuid") String edgeUuid) {
        flowGraphService.deleteEdge(uuid, edgeUuid);
        return ApiEnvelope.ok(null);
    }

    // ------------------------------------------------------------------ 组件库

    /**
     * 组件库列表（租户级；读图面动作 flow.read）。
     */
    @GetMapping("/flow-components")
    public ResponseEntity<ApiEnvelope<List<FlowComponentVO>>> listComponents() {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<FlowComponentVO> components = flowGraphService.listComponents();
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "flow.read", "flow:components");
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(components));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 新增组件库条目（写图；动作 flow.node.write）。
     */
    @PostMapping("/flow-components")
    public ApiEnvelope<FlowComponentVO> createComponent(
            @Validated @RequestBody FlowComponentSaveRequest request) {
        return ApiEnvelope.ok(flowGraphService.createComponent(request));
    }

    /**
     * 更新组件库条目（写图；动作 flow.node.write）。
     */
    @PutMapping("/flow-components/{uuid}")
    public ApiEnvelope<FlowComponentVO> updateComponent(@PathVariable("uuid") String uuid,
                                                        @Validated @RequestBody FlowComponentSaveRequest request) {
        return ApiEnvelope.ok(flowGraphService.updateComponent(uuid, request));
    }

    /**
     * 逻辑删除组件库条目（写图；动作 flow.node.write）。
     */
    @DeleteMapping("/flow-components/{uuid}")
    public ApiEnvelope<Void> deleteComponent(@PathVariable("uuid") String uuid) {
        flowGraphService.deleteComponent(uuid);
        return ApiEnvelope.ok(null);
    }
}
