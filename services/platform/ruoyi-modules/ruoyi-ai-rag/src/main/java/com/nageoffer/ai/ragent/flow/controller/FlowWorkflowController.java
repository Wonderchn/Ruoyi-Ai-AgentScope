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
import com.nageoffer.ai.ragent.flow.controller.vo.FlowComponentVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowOperatorVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowPublicInputsVO;
import com.nageoffer.ai.ragent.flow.controller.vo.FlowWorkflowVO;
import com.nageoffer.ai.ragent.flow.service.FlowGraphService;
import com.nageoffer.ai.ragent.flow.service.FlowWorkflowService;
import com.nageoffer.ai.ragent.flow.support.FlowOperatorCatalog;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AIFlow 工作流定义接口（F13 op3 完整 + op0 的「工作流定义增删改查」部分）。
 *
 * <p><b>本卡范围</b>：op3「基础信息更新；我的工作流搜索」+ op0 的工作流定义增删改查，
 * 以及 F13-M1 的 op1 发布/公开面（enabled / public / 公开搜索、详情、操作符、公开组件库）。
 * op0 的「节点与边编辑」「组件库管理」在 {@code FlowGraphController} 上，不在本类。
 *
 * <p><b>公开语义</b>（F13-M1，维护者 B 批项 5）：公开 = 同租户、跨用户、只读。
 * 可见性谓词固定 {@code is_public=1 AND is_enable=1 AND is_deleted=0 AND tenant_id=当前租户}；
 * 跨租户永不因 {@code is_public} 可见。公开详情不可见时返回同一个 404 文案，
 * 不区分「不存在」与「存在但不可见」。
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
 * 所以所有成功的 GET 都照 {@code UploadController} 的 GET 同形铸造回执：
 * {@link DeliveryPermits#enter} → 两个头，由网关在响应提交后释放；失败路径自行 {@code close()}，
 * 不留 ACTIVE 许可。<b>写入的几条走 JSON 分支，不铸造回执</b> —— JSON 分支不会释放许可，
 * 在那里铸造等于制造永久 ACTIVE 的许可泄漏。公开详情的 404 分支在铸造<b>之前</b>返回，
 * 因此失败时不会留下许可。
 *
 * <p><b>⑤ 授权不在本层。</b>本控制器不加 {@code @SaCheckPermission}：能力级权限由网关按
 * canonical 动作判定（{@code flow.list} / {@code flow.read} / {@code flow.write} /
 * {@code flow.delete} / {@code flow.publish}），本层只负责资源边界
 * （写面：本人 + 本租户；公开读面：本租户 + 公开可见性），二者不可互相替代。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
@Validated
public class FlowWorkflowController {

    private final FlowWorkflowService flowWorkflowService;

    /** 公开输入 schema 读取（F13:4；只读图面，不经过写面 owner 谓词）。 */
    private final FlowGraphService flowGraphService;

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

    // ------------------------------------------------------------------ 公开只读面（F13-M1）

    /**
     * 公开工作流搜索（动作 {@code flow.list}）：<b>同租户、跨用户、只读</b>。
     *
     * <p>可见性谓词由服务层固定（{@code is_public=1 AND is_enable=1 AND is_deleted=0
     * AND tenant_id=当前}），入参只有 {@code current/size/keyword}；返回视图不含所有者 id。
     */
    @GetMapping("/flows/public/search")
    public ResponseEntity<ApiEnvelope<IPage<FlowWorkflowVO>>> searchPublic(
            @Validated FlowWorkflowQueryRequest requestParam) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<FlowWorkflowVO> page = flowWorkflowService.searchPublic(requestParam);
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "flow.list", "flow:public");
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
     * 公开条件操作符目录（动作 {@code flow.list}）：静态 12 项 {@code {name,desc}}，无 DB 访问。
     */
    @GetMapping("/flows/public/operators")
    public ResponseEntity<ApiEnvelope<List<FlowOperatorVO>>> publicOperators() {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<FlowOperatorVO> operators = FlowOperatorCatalog.operators();
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "flow.list", "flow:operators");
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(operators));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 公开组件库（动作 {@code flow.read}）：当前租户 {@code is_enable=1 AND is_deleted=0} 的组件。
     */
    @GetMapping("/flows/public/component/list")
    public ResponseEntity<ApiEnvelope<List<FlowComponentVO>>> publicComponents() {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<FlowComponentVO> components = flowWorkflowService.listPublicComponents();
        DeliveryPermits.Permit permit =
                deliveryPermits.enter(principal, "flow.read", "flow:components:public");
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
     * 公开工作流 START 输入 schema（动作 {@code flow.read}，F13:4）。
     *
     * <p>可见性与公开详情同一组谓词；不可见时返回<b>同一个</b> 404 文案
     * 「工作流不存在」，且在铸造交付许可<b>之前</b>返回 —— 失败路径不留 ACTIVE 许可。
     * 成功时按类注释 ④ 铸造整数 {@link ApiEnvelope} + 两个回执头。
     */
    @GetMapping("/flows/public/{uuid}/inputs")
    public ResponseEntity<ApiEnvelope<?>> getPublicInputs(@PathVariable("uuid") String uuid) {
        ExecutionPrincipal principal = PrincipalContext.require();
        FlowPublicInputsVO vo = flowGraphService.getPublicStartInputs(uuid);
        if (vo == null) {
            return ResponseEntity.status(404)
                    .header("Cache-Control", "no-store")
                    .body(ApiEnvelope.error(404, "工作流不存在", "RESOURCE_NOT_FOUND_OR_FORBIDDEN"));
        }
        DeliveryPermits.Permit permit =
                deliveryPermits.enter(principal, "flow.read", "flow:public:inputs:" + uuid);
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
     * 公开工作流详情（动作 {@code flow.read}）：与公开搜索同一组可见性谓词。
     *
     * <p><b>404 语义</b>：不存在/停用/私有/软删/跨租户一律返回 404 + 同一文案
     * 「工作流不存在」，不区分「不存在」与「存在但不可见」，不泄露存在性。
     * 先取数、后铸许可：不可见时直接返回 404，不留下 ACTIVE 交付许可。
     */
    @GetMapping("/flows/public/{uuid}")
    public ResponseEntity<ApiEnvelope<?>> getPublic(@PathVariable("uuid") String uuid) {
        ExecutionPrincipal principal = PrincipalContext.require();
        FlowWorkflowVO vo = flowWorkflowService.getPublic(uuid);
        if (vo == null) {
            return ResponseEntity.status(404)
                    .header("Cache-Control", "no-store")
                    .body(ApiEnvelope.error(404, "工作流不存在", "RESOURCE_NOT_FOUND_OR_FORBIDDEN"));
        }
        DeliveryPermits.Permit permit =
                deliveryPermits.enter(principal, "flow.read", "flow:public:" + uuid);
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

    // ------------------------------------------------------------------ 发布写面（F13-M1，动作 flow.publish）

    /**
     * 启用/停用工作流（动作 {@code flow.publish}；query {@code enabled=true|false}）。
     *
     * <p>限本人 + 本租户 + 未删；0 行即失败（越权与不存在同形）。
     * <b>不改变公开标志</b>。走 JSON 分支 ⇒ 不铸回执。
     */
    @PostMapping("/flows/{uuid}/enabled")
    public ApiEnvelope<Void> setEnabled(@PathVariable("uuid") String uuid,
                                        @RequestParam("enabled") boolean enabled) {
        flowWorkflowService.enable(uuid, enabled);
        return ApiEnvelope.ok(null);
    }

    /**
     * 设置/取消公开（动作 {@code flow.publish}；query {@code isPublic=true|false}）。
     *
     * <p>限本人 + 本租户 + 未删；0 行即失败。<b>不改变启用标志</b>。
     * 走 JSON 分支 ⇒ 不铸回执。
     */
    @PostMapping("/flows/{uuid}/public")
    public ApiEnvelope<Void> setPublic(@PathVariable("uuid") String uuid,
                                       @RequestParam("isPublic") boolean isPublic) {
        flowWorkflowService.setPublic(uuid, isPublic);
        return ApiEnvelope.ok(null);
    }
}
