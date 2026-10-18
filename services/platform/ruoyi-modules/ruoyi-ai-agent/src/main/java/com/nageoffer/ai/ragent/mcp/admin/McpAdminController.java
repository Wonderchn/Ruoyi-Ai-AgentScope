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

package com.nageoffer.ai.ragent.mcp.admin;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MCP 管理面内层控制器（F12-M1）。
 *
 * <p><b>只作为内层 handler 存在</b>：类级前缀 {@code /internal/ai/v1/mcp}，公开面是网关
 * 白名单 {@code AiGatewayController.ROUTES} 的 18 条（协调者单写集成）；授权在网关按
 * canonical 动作（{@code mcp.list/read/write/delete/test} → {@code ai:mcp:*}，且五条都过
 * 平台管理身份第二道门），因此本层<b>不加</b> {@code @SaCheckPermission}。
 *
 * <p><b>三件套</b>：
 * <ol>
 *   <li>类级内部前缀（拿得到委托主体、不出内层边界）；</li>
 *   <li>{@link ApiEnvelope} 整数 code 信封（非空响应体必须是单 JSON + int code）；</li>
 *   <li>GET 走网关字节分支 ⇒ 自铸 {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation}
 *       两个回执头；写面走 JSON 分支，<b>不铸</b>回执（铸造会泄漏 ACTIVE 许可）。</li>
 * </ol>
 *
 * <p>数据侧失败先于许可铸造：详情/导出取数失败时不留下需要回收的 ACTIVE 许可。
 * 具名 4xx（404/409/400/403）由本类 {@code @ExceptionHandler} 映射，
 * {@code HTTP status == body.code}，不落到全局 500 兜底。
 */
@RestController
@RequestMapping("/internal/ai/v1/mcp")
@RequiredArgsConstructor
public class McpAdminController {

    private final McpAdminService mcpAdminService;
    private final DeliveryPermits deliveryPermits;

    // ------------------------------------------------------------------ 市场

    @GetMapping("/markets")
    public ResponseEntity<ApiEnvelope<IPage<McpMarketVO>>> pageMarkets(McpPageRequest request) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<McpMarketVO> result = mcpAdminService.pageMarkets(request);
        return permitted(principal, "mcp.list", "mcp:market:list", result);
    }

    @GetMapping("/markets/all")
    public ResponseEntity<ApiEnvelope<List<McpMarketVO>>> allMarkets(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<McpMarketVO> result = mcpAdminService.listMarkets(keyword, status);
        return permitted(principal, "mcp.list", "mcp:market:all", result);
    }

    @GetMapping("/markets/export")
    public ResponseEntity<ApiEnvelope<String>> exportMarkets(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String result = mcpAdminService.exportMarkets(keyword, status);
        return permitted(principal, "mcp.read", "mcp:market:export", result);
    }

    @GetMapping("/markets/{id}")
    public ResponseEntity<ApiEnvelope<McpMarketVO>> marketDetail(@PathVariable long id) {
        ExecutionPrincipal principal = PrincipalContext.require();
        McpMarketVO result = mcpAdminService.getMarket(id);
        return permitted(principal, "mcp.read", "mcp:market:" + id, result);
    }

    @GetMapping("/markets/{id}/tools")
    public ResponseEntity<ApiEnvelope<IPage<McpMarketToolVO>>> marketTools(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<McpMarketToolVO> result = mcpAdminService.pageMarketTools(id, current, size);
        return permitted(principal, "mcp.list", "mcp:market:" + id + ":tools", result);
    }

    @PostMapping("/markets/{id}/refresh")
    public ApiEnvelope<McpRefreshResult> refreshMarket(@PathVariable long id) {
        return ApiEnvelope.ok(mcpAdminService.refreshMarket(id));
    }

    @PostMapping("/markets/{id}/status")
    public ApiEnvelope<Void> marketStatus(@PathVariable long id, @RequestParam String status) {
        mcpAdminService.updateMarketStatus(id, status);
        return ApiEnvelope.ok(null);
    }

    @DeleteMapping("/markets/{ids}")
    public ApiEnvelope<Integer> deleteMarkets(@PathVariable Long[] ids) {
        return ApiEnvelope.ok(mcpAdminService.deleteMarkets(List.of(ids)));
    }

    // ------------------------------------------------------------------ 工具

    @GetMapping("/tools")
    public ResponseEntity<ApiEnvelope<IPage<McpToolVO>>> pageTools(McpPageRequest request) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<McpToolVO> result = mcpAdminService.pageTools(request);
        return permitted(principal, "mcp.list", "mcp:tool:list", result);
    }

    @GetMapping("/tools/all")
    public ResponseEntity<ApiEnvelope<List<McpToolVO>>> allTools(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<McpToolVO> result = mcpAdminService.listTools(keyword, type, status);
        return permitted(principal, "mcp.list", "mcp:tool:all", result);
    }

    @GetMapping("/tools/options")
    public ResponseEntity<ApiEnvelope<List<McpToolVO>>> toolOptions() {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<McpToolVO> result = mcpAdminService.listEnabledTools();
        return permitted(principal, "mcp.list", "mcp:tool:options", result);
    }

    @GetMapping("/tools/export")
    public ResponseEntity<ApiEnvelope<String>> exportTools(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        ExecutionPrincipal principal = PrincipalContext.require();
        String result = mcpAdminService.exportTools(keyword, type, status);
        return permitted(principal, "mcp.read", "mcp:tool:export", result);
    }

    @GetMapping("/tools/{id}")
    public ResponseEntity<ApiEnvelope<McpToolVO>> toolDetail(@PathVariable long id) {
        ExecutionPrincipal principal = PrincipalContext.require();
        McpToolVO result = mcpAdminService.getTool(id);
        return permitted(principal, "mcp.read", "mcp:tool:" + id, result);
    }

    @PostMapping("/tools/{id}/status")
    public ApiEnvelope<Void> toolStatus(@PathVariable long id, @RequestParam String status) {
        mcpAdminService.updateToolStatus(id, status);
        return ApiEnvelope.ok(null);
    }

    @PostMapping("/tools/{id}/test")
    public ApiEnvelope<McpTestResult> testTool(@PathVariable long id) {
        return ApiEnvelope.ok(mcpAdminService.testTool(id));
    }

    @DeleteMapping("/tools/{ids}")
    public ApiEnvelope<Integer> deleteTools(@PathVariable Long[] ids) {
        return ApiEnvelope.ok(mcpAdminService.deleteTools(List.of(ids)));
    }

    // ------------------------------------------------------------------ 市场工具

    @PostMapping("/market-tools/{id}/load")
    public ApiEnvelope<Void> loadMarketTool(@PathVariable long id) {
        mcpAdminService.loadMarketTool(id);
        return ApiEnvelope.ok(null);
    }

    /**
     * 批量加载：请求体 {@code {"ids":[...]}}、响应 {@code {"loaded":n}}
     * （前端冻结契约；逐条隔离，一条失败不影响其它条）。
     */
    @PostMapping("/market-tools/batch-load")
    public ApiEnvelope<McpBatchLoadResult> batchLoadMarketTools(
            @RequestBody(required = false) McpBatchLoadRequest request) {
        List<Long> ids = request == null ? null : request.getIds();
        return ApiEnvelope.ok(new McpBatchLoadResult(mcpAdminService.batchLoadMarketTools(ids)));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * GET 交付回执：先取数再铸许可（数据侧失败不留 ACTIVE 许可）；
     * 响应头与许可一一对应，写失败时关闭许可。
     */
    private <T> ResponseEntity<ApiEnvelope<T>> permitted(
            ExecutionPrincipal principal, String action, String operation, T data) {
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, action, operation);
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(data));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    @ExceptionHandler(McpAdminException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleAdminError(McpAdminException e) {
        return ResponseEntity.status(e.httpStatus())
                .header("Cache-Control", "no-store")
                .body(ApiEnvelope.error(e.httpStatus(), e.getMessage(), e.errorCode()));
    }

    @ExceptionHandler(McpEndpointNotAllowedException.class)
    public ResponseEntity<ApiEnvelope<Map<String, Object>>> handleEndpointNotAllowed(
            McpEndpointNotAllowedException e) {
        return ResponseEntity.status(403)
                .header("Cache-Control", "no-store")
                .body(ApiEnvelope.error(403, e.getMessage(), e.errorCode()));
    }
}
