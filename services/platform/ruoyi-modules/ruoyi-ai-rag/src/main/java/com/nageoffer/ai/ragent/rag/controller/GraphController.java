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
import com.nageoffer.ai.ragent.rag.controller.vo.GraphStatusVO;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphViewVO;
import com.nageoffer.ai.ragent.rag.core.graph.GraphProjectionService;
import com.nageoffer.ai.ragent.rag.core.graph.GraphQueryService;
import com.nageoffer.ai.ragent.rag.core.graph.GraphStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识图谱管理面（F08:0–3）：内层前缀 {@code /internal/ai/v1/admin/kg}。
 *
 * <p>公开面由 {@code AiGatewayController.ROUTES} 逐条放行；内层前缀对外被边界过滤器关成 404。
 * 三个 GET 走网关字节分支，必须返回整数 {@link ApiEnvelope}（code=200）并由
 * {@code AiEmbeddedAdminDeliveryConfiguration} 铸造交付回执；POST 重建走 JSON 分支，不铸回执。
 *
 * <p>动作映射：status→config.read，graph/labels→kb.read，rebuild→kb.write；零新增 canonical 动作。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/ai/v1/admin/kg")
@ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
public class GraphController {

    private final GraphQueryService graphQueryService;
    private final GraphStatusService graphStatusService;
    private final GraphProjectionService graphProjectionService;

    /**
     * 拉取图谱子图（只读主库投影，零 LightRAG 外呼）。
     */
    @GetMapping("/graph")
    public ResponseEntity<ApiEnvelope<GraphViewVO>> graph(
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) String kbId,
            @RequestParam(required = false) String collection,
            @RequestParam(required = false) String doc,
            @RequestParam(defaultValue = "2") int depth,
            @RequestParam(defaultValue = "200") int limit) {
        return reply(graphQueryService.getGraph(entity, kbId, collection, doc, depth, limit));
    }

    /**
     * 检索实体标签，供可视化搜索框；keyword 为空取热门标签。
     */
    @GetMapping("/labels")
    public ResponseEntity<ApiEnvelope<List<String>>> labels(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit) {
        return reply(graphQueryService.searchEntities(keyword, limit));
    }

    /**
     * 图谱后端连通性与投影状态。
     */
    @GetMapping("/status")
    public ResponseEntity<ApiEnvelope<GraphStatusVO>> status() {
        return reply(graphStatusService.status());
    }

    /**
     * 按 KB 或文档重建投影（二选一）。
     */
    @PostMapping("/projection/rebuild")
    public ApiEnvelope<GraphProjectionService.RebuildResult> rebuild(
            @RequestParam(required = false) String kbId,
            @RequestParam(required = false) String docId) {
        return ApiEnvelope.ok(graphProjectionService.rebuild(kbId, docId));
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> reply(T data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiEnvelope.ok(data));
    }
}
