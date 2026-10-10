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
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库控制器
 * 提供知识库的增删改查等基础操作接口
 *
 * <p><b>路径面（RW-04-R1）</b>：类级前缀 {@code /internal/ai/v1} 是<b>内层</b>前缀，
 * 直接访问由 {@code AiInternalAccessBoundaryFilter} 关成 404。公开面必须经
 * {@code AiGatewayController} 白名单转发，即 {@code /api/ai/v1/knowledge-base/**}
 * （单数 = admin 管理面；复数 {@code /knowledge-bases/**} 是 AI 资源面
 * {@code AiResourceController}，两者不同面、不冲突）。
 *
 * <p>前置条件：本控制器要成为 bean，必须由内嵌装配显式登记
 * （platform 扫描根是 {@code org.ruoyi}，{@code com.nageoffer.ai.ragent.*} 不在扫描范围）。
 *
 * <p><b>授权不在本层</b>：与 {@code AiResourceController}/{@code AgentChatController} 同形，
 * 内层 controller <b>不加</b> {@code @SaCheckPermission}——能力级权限由网关按
 * {@code AiCanonicalAction} 判定，资源级判定在 AI 侧授权域。这里只做"缺执行主体即拒绝"
 * （{@code PrincipalContext.require()}）与租户条件读写。
 *
 * <p><b>信封必须是整数 {@code code}（RW-04-R8）。</b>本控制器的 5 条方法此前返回
 * {@code framework.convention.Result}，其 {@code code} 是<b>字符串</b>（成功 {@code "0"}）；
 * 而本地传输 {@code LocalAiGatewayClient.forward()} 对任何非空 body 调
 * {@code requireSingleJsonObject(...)}，其中要求 {@code code} 是<b>整数</b>且等于 HTTP 状态
 * （{@code LocalAiGatewayClient.java:458-473}）。platform 侧没有 {@code ResponseBodyAdvice}
 * 兜底 ⇒ 字符串 code 的信封经网关<b>必然 503</b>（{@code missing envelope code}）。
 * 因此统一返回 {@link ApiEnvelope}（{@code int code}，成功 200，HTTP 200），与同包的
 * {@code KnowledgeChunkController} 同形。信封形状由
 * {@code LocalWhitelistEnvelopeShapeTest} 钉住。
 *
 * <p><b>GET 还要回执头。</b>网关对 {@code GET} 一律走<b>字节分支</b>
 * {@code forwardBytes()}（{@code AiGatewayController:366-367} 的 {@code method.equals("GET")}）：
 * 它对 200 响应<b>同时</b>要求 ① JSON 体是整数 code；②
 * {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 是 UUID 形状
 * （{@code LocalAiGatewayClient.java:163-170}），缺任一即 {@code delivery receipt missing}。
 * 所以两条 GET 返回 {@code ResponseEntity<ApiEnvelope<…>>} 并在体内铸许可；
 * <b>三条写操作走 JSON 分支，不铸回执</b>——JSON 分支不会释放许可，
 * 在那里铸造等于制造永久 ACTIVE 的许可泄漏。
 *
 * <p><b>本控制器自 S2-F05-A1 起已放行（RW-04-R8 改造 + F05 闭包闭合）。</b>RW-04-R8 的信封改造
 * 只消除了"一经登记路由就必然 503"这一项；S2-F05-A1 把 {@code FullAdmin} 的构造闭包补齐、
 * 并在 {@code AiGatewayController.ROUTES} 逐条登记了本面 5 条（公开前缀
 * {@code /api/ai/v1/knowledge-base/**}）。"放行 = 必须整数信封"因此由
 * {@code LocalWhitelistEnvelopeShapeTest#knowledgeAdminFacesAreReleasedAndCounted}
 * 逐条钉住（该判据此前钉的是"仍未放行"）。
 */
@RestController
@RequestMapping("/internal/ai/v1")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 交付许可铸造器（GET 走网关字节分支，必须带回执头；见类注释）。
     */
    private final DeliveryPermits deliveryPermits;

    /**
     * 创建知识库
     */
    @PostMapping("/knowledge-base")
    public ApiEnvelope<String> createKnowledgeBase(@RequestBody KnowledgeBaseCreateRequest requestParam) {
        return ApiEnvelope.ok(knowledgeBaseService.create(requestParam));
    }

    /**
     * 重命名知识库
     */
    @PutMapping("/knowledge-base/{kb-id}")
    public ApiEnvelope<Void> renameKnowledgeBase(@PathVariable("kb-id") String kbId,
                                                 @RequestBody KnowledgeBaseUpdateRequest requestParam) {
        knowledgeBaseService.rename(kbId, requestParam);
        return ApiEnvelope.ok(null);
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/knowledge-base/{kb-id}")
    public ApiEnvelope<Void> deleteKnowledgeBase(@PathVariable("kb-id") String kbId) {
        knowledgeBaseService.delete(kbId);
        return ApiEnvelope.ok(null);
    }

    /**
     * 查询知识库详情
     *
     * <p>返回 {@code ResponseEntity} 而不是裸信封：本条是 GET，走网关字节分支，
     * 必须额外带两个交付回执头（见类注释）。
     */
    @GetMapping("/knowledge-base/{kb-id}")
    public ResponseEntity<ApiEnvelope<KnowledgeBaseVO>> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
        ExecutionPrincipal principal = PrincipalContext.require();
        KnowledgeBaseVO knowledgeBase = knowledgeBaseService.queryById(kbId);
        // 先取数据再铸许可：数据侧失败时不留下需要回收的 ACTIVE 许可
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "kb.read", "kb:" + kbId);
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(knowledgeBase));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 分页查询知识库列表
     *
     * <p>同 {@link #queryKnowledgeBase}：GET 走字节分支，要带回执头。
     */
    @GetMapping("/knowledge-base")
    public ResponseEntity<ApiEnvelope<IPage<KnowledgeBaseVO>>> pageQuery(KnowledgeBasePageRequest requestParam) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<KnowledgeBaseVO> page = knowledgeBaseService.pageQuery(requestParam);
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "kb.list", "kb:list");
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
}
