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

package com.nageoffer.ai.ragent.ingestion.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
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

/**
 * 数据摄入流水线控制层
 *
 * <p><b>路径面（S2-F06-A1）</b>：类级前缀 {@code /internal/ai/v1} 是内层前缀，直接访问被
 * {@code AiInternalAccessBoundaryFilter} 关成 404；公开面经网关白名单为
 * {@code /api/ai/v1/ingestion/pipelines/**}。此前本类<b>没有</b>类级 {@code @RequestMapping}，
 * 路径是裸的 {@code /ingestion/pipelines/...}，而内层转送目标恒为
 * {@code /internal/ai/v1 + subPath} ⇒ 内层路由永远不命中（与 RW-22-R1 的 intent 面、
 * WP-034 会话面同形的缺陷）。
 *
 * <p><b>信封（S2-F06-A1）</b>：返回 {@link ApiEnvelope}（<b>整数</b> {@code code}）。
 * 此前返回 ragent {@code Result}，其 {@code code} 是<b>字符串</b> {@code "0"}；网关
 * {@code LocalAiGatewayClient.requireSingleJsonObject} 强制"单 JSON 对象 + 整数 code 等于
 * HTTP 状态"，字符串 code 会被判成"缺少包络 code"并收敛为 <b>503</b>
 * （既有实测定案：{@code AiEmbeddedFeedbackConfiguration}、{@code AgentChatController.stop}、
 * RW-22-R1 的 intent 三面）。即"只归位不换信封 = 放行了但不可用"，故两者同批。
 *
 * <p><b>GET 的交付回执</b>：两条 GET 走网关字节分支，响应须带
 * {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation}（由
 * {@code AiEmbeddedAdminDeliveryConfiguration.AdminDeliveryAdvice} 铸造，动作 {@code config.read}）
 * ——与本类同批将该控制器并入 assignableTypes，缺它则网关判 "delivery receipt missing" 成 503。
 *
 * <p><b>授权不在本层</b>：不加 {@code @SaCheckPermission}，能力级权限由网关按
 * "公开路由 → canonical 动作"判定（动作复用 {@code config.read}/{@code config.publish}，
 * 不新增 canonical 动作）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
@Validated
public class IngestionPipelineController {

    private final IngestionPipelineService pipelineService;

    /**
     * 创建数据摄入流水线
     */
    @PostMapping("/ingestion/pipelines")
    public ApiEnvelope<IngestionPipelineVO> create(@RequestBody IngestionPipelineCreateRequest request) {
        return ApiEnvelope.ok(pipelineService.create(request));
    }

    /**
     * 更新数据摄入流水线
     */
    @PutMapping("/ingestion/pipelines/{id}")
    public ApiEnvelope<IngestionPipelineVO> update(@PathVariable String id,
                                                   @RequestBody IngestionPipelineUpdateRequest request) {
        return ApiEnvelope.ok(pipelineService.update(id, request));
    }

    /**
     * 获取单个数据摄入流水线详情
     */
    @GetMapping("/ingestion/pipelines/{id}")
    public ApiEnvelope<IngestionPipelineVO> get(@PathVariable String id) {
        return ApiEnvelope.ok(pipelineService.get(id));
    }

    /**
     * 分页查询数据摄入流水线
     */
    @GetMapping("/ingestion/pipelines")
    public ApiEnvelope<IPage<IngestionPipelineVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                        @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                        @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiEnvelope.ok(pipelineService.page(new Page<>(pageNo, pageSize), keyword));
    }

    /**
     * 删除数据摄入流水线
     */
    @DeleteMapping("/ingestion/pipelines/{id}")
    public ApiEnvelope<Void> delete(@PathVariable String id) {
        pipelineService.delete(id);
        return ApiEnvelope.ok(null);
    }
}
