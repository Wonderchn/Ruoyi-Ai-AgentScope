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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.flow.controller.request.FlowWorkflowQueryRequest;
import com.nageoffer.ai.ragent.flow.controller.request.FlowWorkflowSaveRequest;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowWorkflowVO;
import com.nageoffer.ai.ragent.flow.service.FlowWorkflowService;
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

/**
 * AIFlow 工作流定义接口（F13 op3 完整 + op0 的「工作流定义增删改查」部分）。
 *
 * <p><b>本卡范围</b>：op3「基础信息更新；我的工作流搜索」+ op0 的工作流定义增删改查。
 * <b>不含</b> op0 的「节点与边编辑」「组件库管理」，也不含 op1 发布/可见性开关的全部公开面。
 *
 * <p><b>① 路径面。</b>类级前缀 {@code /internal/ai/v1} 是内层前缀，直接访问被
 * {@code AiInternalAccessBoundaryFilter} 关成 404；公开面经网关白名单为
 * {@code /api/ai/v1/flows*}。与 {@code KnowledgeChunkController} 同形。
 *
 * <p><b>② 装配三件套。</b>本类在 {@code com.nageoffer.*} 下，<b>不在</b> platform 组件扫描根
 * {@code org.ruoyi}，{@code @Component}/{@code @RestController} 本身不生效 —— 必须由内嵌装配
 * 显式登记（见交付说明的 imports 补丁），并且 Mapper 包要进
 * {@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 数组，最后在
 * {@code AiGatewayController.ROUTES} 逐条登记路由。三件缺一，端点就"服务端写好了但客户端到不了"。
 *
 * <p><b>③ 信封必须是整数 code。</b>{@link ApiEnvelope} 的 {@code code} 是 {@code int}
 * （成功 {@code 200}）。{@code framework.convention.Result} 的 code 是<b>字符串</b>（成功 {@code "0"}），
 * 二者不可混：本地传输对任何非空 body 调 {@code requireSingleJsonObject(...)}，要求 code 是整数，
 * 字符串 code 经网关<b>必然 503</b>（{@code missing envelope code}）。故本控制器全部返回 {@link ApiEnvelope}。
 *
 * <p><b>④ GET 还要交付回执头。</b>网关对 {@code GET} 一律走<b>字节分支</b>，它对 200 响应同时要求
 * ① JSON 体是整数 code；② {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 是 UUID 形状，
 * 缺任一即 {@code delivery receipt missing} ⇒ 再 503 一次。
 * 所以两条 GET 照 {@code UploadController} 的 GET 同形铸造回执：
 * {@link DeliveryPermits#enter} → 两个头，由网关在响应提交后释放；失败路径自行 {@code close()}，
 * 不留 ACTIVE 许可。<b>写入的三条走 JSON 分支，不铸造回执</b> —— JSON 分支不会释放许可，
 * 在那里铸造等于制造永久 ACTIVE 的许可泄漏。
 *
 * <p><b>⑤ 授权不在本层。</b>本控制器不加 {@code @SaCheckPermission}：能力级权限由网关按
 * canonical 动作判定（{@code flow.list} / {@code flow.read} / {@code flow.write} / {@code flow.delete}），
 * 本层只负责资源边界（本人 + 本租户），二者不可互相替代。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
@Validated
public class FlowWorkflowController {

    private final FlowWorkflowService flowWorkflowService;

    /** 交付许可铸造器（GET 走字节分支，必须带回执头；见类注释 ④）。 */
    private final DeliveryPermits deliveryPermits;

    /**
     * 「我的工作流搜索」。
     *
     * <p>只返回当前主体本人的工作流；范围由服务层谓词限定，不由入参指定。
     */
    @GetMapping("/flows")
    public ResponseEntity<ApiEnvelope<IPage<FlowWorkflowVO>>> searchMine(
            @Validated FlowWorkflowQueryRequest requestParam) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<FlowWorkflowVO> page = flowWorkflowService.searchMine(requestParam);
        // 先取数据再铸许可：数据侧失败时不留下需要回收的 ACTIVE 许可
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "flow.list", "flow:mine");
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(page));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 按 uuid 读取本人工作流（op3/op0 的详情读）。
     */
    @GetMapping("/flows/{uuid}")
    public ResponseEntity<ApiEnvelope<FlowWorkflowVO>> getMine(@PathVariable("uuid") String uuid) {
        ExecutionPrincipal principal = PrincipalContext.require();
        FlowWorkflowVO vo = flowWorkflowService.getMine(uuid);
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "flow.read", "flow:" + uuid);
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(vo));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 新增工作流定义（op0 的"新增工作流"；只建定义本身，不建节点/边）。
     *
     * <p>走 JSON 分支 ⇒ <b>不铸回执头</b>（见类注释 ④）。
     */
    @PostMapping("/flows")
    public ApiEnvelope<FlowWorkflowVO> create(@Validated @RequestBody FlowWorkflowSaveRequest request) {
        return ApiEnvelope.ok(flowWorkflowService.create(request));
    }

    /**
     * 更新工作流基础信息（op3 的"基础信息更新"）。
     */
    @PutMapping("/flows/{uuid}")
    public ApiEnvelope<FlowWorkflowVO> updateBasicInfo(@PathVariable("uuid") String uuid,
                                                       @Validated @RequestBody FlowWorkflowSaveRequest request) {
        return ApiEnvelope.ok(flowWorkflowService.updateBasicInfo(uuid, request));
    }

    /**
     * 逻辑删除工作流定义（op0 的"删除工作流"）。
     */
    @DeleteMapping("/flows/{uuid}")
    public ApiEnvelope<Void> delete(@PathVariable("uuid") String uuid) {
        flowWorkflowService.delete(uuid);
        return ApiEnvelope.ok(null);
    }
}
