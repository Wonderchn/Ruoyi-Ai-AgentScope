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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库 Chunk 管理接口
 *
 * <p><b>路径面（RW-04-R1）</b>：类级前缀 {@code /internal/ai/v1} 是内层前缀，直接访问被
 * {@code AiInternalAccessBoundaryFilter} 关成 404；公开面经网关白名单为
 * {@code /api/ai/v1/knowledge-base/docs/{docId}/chunks*}——这正是
 * {@code 04-page-map.json:56}「文档分块」页登记的 API 分母。
 *
 * <p>前置条件：必须由内嵌装配显式登记（platform 扫描根 {@code org.ruoyi} 不含本模块）。
 * 授权不在本层：不加 {@code @SaCheckPermission}，能力级权限由网关判定。
 *
 * <p><b>信封必须是整数 {@code code}（RW-04-R3 / D2）。</b>本控制器的 6 条方法此前返回
 * {@code framework.convention.Result}，其 {@code code} 是<b>字符串</b>（成功 {@code "0"}）；
 * 而本地传输 {@code LocalAiGatewayClient.forward()} 对任何非空 body 调
 * {@code requireSingleJsonObject(...)}，其中要求 {@code code} 是<b>整数</b>且等于 HTTP 状态
 * （{@code LocalAiGatewayClient.java:458-471}）。platform 侧没有 {@code ResponseBodyAdvice}
 * 兜底 ⇒ 字符串 code 的信封经网关<b>必然 503</b>（{@code missing envelope code}）。
 * 因此本控制器与 {@code FeedbackSurface}、{@code AgentChatController.stop} 同形，
 * 统一返回 {@link ApiEnvelope}（{@code int code}，成功 200，HTTP 200）。
 *
 * <p><b>GET 还要回执头（D2 的第二半，R 复核补充）。</b>网关对
 * {@code GET} 一律走<b>字节分支</b> {@code forwardBytes()}（{@code AiGatewayController:366-367}
 * 的 {@code method.equals("GET")}）：它对 200 响应**同时**要求
 * ① JSON 体是整数 code（{@code forwardBytes} 内的 {@code requireSingleJsonObject}）；
 * ② {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 是 UUID 形状，缺任一即
 * {@code delivery receipt missing}（{@code LocalAiGatewayClient.java:163-170}）。
 * 所以列表方法照 {@code UploadController} 的 GET 同形铸造回执：
 * {@link DeliveryPermits#enter} → 两个头，由网关在响应提交后
 * {@code POST /authorization/deliveries/release} 释放；失败路径自行 {@code close()}，不留 ACTIVE 许可。
 * <b>批量/写入的 5 条走 JSON 分支，不铸造回执</b>——JSON 分支不会释放许可，
 * 在那里铸造等于制造永久 ACTIVE 的许可泄漏。
 *
 * <p>同包内的 {@code KnowledgeBaseController}/{@code KnowledgeDocumentController}
 * <b>仍是 {@code Result}</b>：它们当前<b>未被白名单放行</b>、也不是 bean（内嵌装配默认关闭），
 * 所以还不构成 D2。**将来一旦放行它们的路由，必须同法改为 {@link ApiEnvelope}（GET 还要回执头）**，
 * 否则会重复同一个 503。这条约束由 {@code LocalWhitelistEnvelopeShapeTest} 兜住。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
@Validated
public class KnowledgeChunkController {

    private final KnowledgeChunkService knowledgeChunkService;

    /**
     * 交付许可铸造器（GET 走字节分支，必须带回执头；见类注释）。
     */
    private final DeliveryPermits deliveryPermits;

    /**
     * 分页查询 Chunk 列表
     *
     * <p>返回 {@code ResponseEntity} 而不是裸信封：本条必须额外带两个交付回执头。
     */
    @GetMapping("/knowledge-base/docs/{doc-id}/chunks")
    public ResponseEntity<ApiEnvelope<IPage<KnowledgeChunkVO>>> pageQuery(
            @PathVariable("doc-id") String docId,
            @Validated KnowledgeChunkPageRequest requestParam) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<KnowledgeChunkVO> page = knowledgeChunkService.pageQuery(docId, requestParam);
        // 先取数据再铸许可：数据侧失败时不留下需要回收的 ACTIVE 许可
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "document.read", "doc:" + docId);
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
     * 新增 Chunk
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks")
    public ApiEnvelope<KnowledgeChunkVO> create(@PathVariable("doc-id") String docId,
                                                @RequestBody KnowledgeChunkCreateRequest request) {
        return ApiEnvelope.ok(knowledgeChunkService.create(docId, request));
    }

    /**
     * 更新 Chunk 内容
     */
    @PutMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public ApiEnvelope<Void> update(@PathVariable("doc-id") String docId,
                                    @PathVariable("chunk-id") String chunkId,
                                    @RequestBody KnowledgeChunkUpdateRequest request) {
        knowledgeChunkService.update(docId, chunkId, request);
        return ApiEnvelope.ok(null);
    }

    /**
     * 删除 Chunk
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public ApiEnvelope<Void> delete(@PathVariable("doc-id") String docId,
                                    @PathVariable("chunk-id") String chunkId) {
        knowledgeChunkService.delete(docId, chunkId);
        return ApiEnvelope.ok(null);
    }

    /**
     * 启用或禁用单条 Chunk
     */
    @PatchMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/enable")
    public ApiEnvelope<Void> enable(@PathVariable("doc-id") String docId,
                                    @PathVariable("chunk-id") String chunkId,
                                    @RequestParam("value") boolean enabled) {
        knowledgeChunkService.enableChunk(docId, chunkId, enabled);
        return ApiEnvelope.ok(null);
    }

    /**
     * 批量启用或禁用 Chunk
     */
    @PatchMapping("/knowledge-base/docs/{doc-id}/chunks/batch-enable")
    public ApiEnvelope<Void> batchEnable(@PathVariable("doc-id") String docId,
                                         @RequestParam("value") boolean enabled,
                                         @RequestBody(required = false) KnowledgeChunkBatchRequest request) {
        knowledgeChunkService.batchToggleEnabled(docId, request, enabled);
        return ApiEnvelope.ok(null);
    }
}
