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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeBatchRequest;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
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
 * 意图树控制器
 * 提供意图节点树的查询、创建、更新和删除功能
 *
 * <p><b>路径面（RW-22-R1）</b>：类级前缀 {@code /internal/ai/v1} 是内层前缀，直接访问被
 * {@code AiInternalAccessBoundaryFilter} 关成 404；公开面经网关白名单为
 * {@code /api/ai/v1/intent-tree/**}。此前本类<b>没有</b>类级 {@code @RequestMapping}，
 * 路径是裸的 {@code /intent-tree/...}，而 {@code DelegatedPrincipalFilter.PROTECTED_PREFIX}
 * 恰是 {@code /internal/ai/v1} —— 裸路径拿不到委托主体，等于对任何人都不可用（与 WP-034 /
 * RW-04-R1 同形的缺陷）。
 *
 * <p><b>信封（RW-22-R1）</b>：返回 {@link ApiEnvelope}（<b>整数</b> {@code code}）。
 * 此前返回平台 {@code Result}，其 {@code code} 是<b>字符串</b> {@code "0"}；网关
 * {@code LocalAiGatewayClient.requireSingleJsonObject} 强制"单 JSON 对象 + 整数 code 等于
 * HTTP 状态"，字符串 code 会被判成"缺少包络 code"并收敛为 <b>503</b>
 * （本项目已有两次实测定案：{@code AiEmbeddedFeedbackConfiguration}、{@code AgentChatController.stop}）。
 * 即"只归位不换信封 = 放行了但不可用"，故两者必须同批。
 *
 * <p><b>为什么把原本 {@code void} 的三个方法也改成 {@code ApiEnvelope<Void>}</b>：
 * {@code void} 返回空体，虽然能过网关的空体豁免，但同一控制器的成功响应会分裂成
 * "空体"与"包络"两种形状；统一成整数 code 的包络后，客户端只需一条解析规则。
 *
 * <p><b>授权不在本层</b>：不加 {@code @SaCheckPermission}，能力级权限由网关按
 * "公开路由 → canonical 动作"判定（动作复用 {@code config.read}/{@code config.publish}，
 * 不新增 canonical 动作）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
public class IntentTreeController {

    private final IntentTreeService intentTreeService;

    /**
     * 获取完整的意图节点树
     */
    @GetMapping("/intent-tree/trees")
    public ApiEnvelope<List<IntentNodeTreeVO>> tree() {
        return ApiEnvelope.ok(intentTreeService.getFullTree());
    }

    /**
     * 创建意图节点
     */
    @PostMapping("/intent-tree")
    public ApiEnvelope<String> createNode(@RequestBody IntentNodeCreateRequest requestParam) {
        return ApiEnvelope.ok(intentTreeService.createNode(requestParam));
    }

    /**
     * 更新意图节点
     */
    @PutMapping("/intent-tree/{id}")
    public ApiEnvelope<Void> updateNode(@PathVariable String id, @RequestBody IntentNodeUpdateRequest requestParam) {
        intentTreeService.updateNode(id, requestParam);
        return ApiEnvelope.ok(null);
    }

    /**
     * 删除意图节点
     */
    @DeleteMapping("/intent-tree/{id}")
    public ApiEnvelope<Void> deleteNode(@PathVariable String id) {
        intentTreeService.deleteNode(id);
        return ApiEnvelope.ok(null);
    }

    /**
     * 批量启用节点
     */
    @PostMapping("/intent-tree/batch/enable")
    public ApiEnvelope<Void> batchEnable(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchEnableNodes(requestParam.getIds());
        return ApiEnvelope.ok(null);
    }

    /**
     * 批量停用节点
     */
    @PostMapping("/intent-tree/batch/disable")
    public ApiEnvelope<Void> batchDisable(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchDisableNodes(requestParam.getIds());
        return ApiEnvelope.ok(null);
    }

    /**
     * 批量删除节点
     */
    @PostMapping("/intent-tree/batch/delete")
    public ApiEnvelope<Void> batchDelete(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchDeleteNodes(requestParam.getIds());
        return ApiEnvelope.ok(null);
    }
}
