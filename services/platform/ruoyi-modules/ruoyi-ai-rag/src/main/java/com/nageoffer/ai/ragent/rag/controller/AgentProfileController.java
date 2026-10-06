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
import com.nageoffer.ai.ragent.rag.controller.request.AgentProfileSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentPromptSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentProfileListVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.service.AgentProfileAdminService;
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
 * 智能体管理控制器
 *
 * <p>W4-9：本控制器只作为**内层** handler 存在（{@code /internal/ai/v1/agent-catalog/**}）。
 * 公开面是网关白名单（{@code AiGatewayController.ROUTES} 的 {@code /agent-catalog/**}），
 * 授权在网关按 scope 强制（{@code AiGatewayController:274-277}），因此本层**不加**
 * {@code @SaCheckPermission}——与 {@code AiResourceController} / {@code AgentChatController} 同形。
 *
 * <p>返回类型由 ragent {@code Result}（字符串 code）改为 {@link ApiEnvelope}（{@code int code}）：
 * 内嵌传输对非空响应体要求"单 JSON 对象 + 整数 code 与状态一致"
 * （{@code LocalAiGatewayClient:97/146/164-165/175}），保持 {@code Result} 会被 fail-closed。
 * 同族先例见 {@code AgentChatController:135}。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/ai/v1/agent-catalog")
public class AgentProfileController {

    private final AgentProfileAdminService agentProfileAdminService;

    /**
     * 查询智能体列表
     */
    @GetMapping("/agents")
    public ResponseEntity<ApiEnvelope<AgentProfileListVO>> list() {
        return ResponseEntity.ok(ApiEnvelope.ok(agentProfileAdminService.list()));
    }

    /**
     * 创建智能体
     */
    @PostMapping("/agents")
    public ResponseEntity<ApiEnvelope<String>> create(@RequestBody AgentProfileSaveRequest requestParam) {
        return ResponseEntity.ok(ApiEnvelope.ok(agentProfileAdminService.create(requestParam)));
    }

    /**
     * 更新智能体名称与描述
     */
    @PutMapping("/agents/{id}")
    public ResponseEntity<ApiEnvelope<Void>> update(@PathVariable String id,
                                                    @RequestBody AgentProfileSaveRequest requestParam) {
        agentProfileAdminService.update(id, requestParam);
        return ResponseEntity.ok(ApiEnvelope.ok(null));
    }

    /**
     * 删除智能体
     */
    @DeleteMapping("/agents/{id}")
    public ResponseEntity<ApiEnvelope<Void>> delete(@PathVariable String id) {
        agentProfileAdminService.delete(id);
        return ResponseEntity.ok(ApiEnvelope.ok(null));
    }

    /**
     * 激活智能体，立即对全部会话生效
     */
    @PostMapping("/agents/{id}/activate")
    public ResponseEntity<ApiEnvelope<Void>> activate(@PathVariable String id) {
        agentProfileAdminService.activate(id);
        return ResponseEntity.ok(ApiEnvelope.ok(null));
    }

    /**
     * 查询该智能体的槽位配置，含槽位元数据与当前架构下的生效判定
     */
    @GetMapping("/agents/{id}/prompts")
    public ResponseEntity<ApiEnvelope<AgentPromptConfigVO>> prompts(@PathVariable String id) {
        return ResponseEntity.ok(ApiEnvelope.ok(agentProfileAdminService.loadPrompts(id)));
    }

    /**
     * 保存单个槽位，内容留空即恢复回落内置智能体
     */
    @PutMapping("/agents/{id}/prompts/{slotKey}")
    public ResponseEntity<ApiEnvelope<Void>> savePrompt(@PathVariable String id,
                                                        @PathVariable String slotKey,
                                                        @RequestBody AgentPromptSaveRequest requestParam) {
        agentProfileAdminService.savePrompt(id, slotKey, requestParam);
        return ResponseEntity.ok(ApiEnvelope.ok(null));
    }

    /**
     * 查询内置智能体的槽位内容，供「从默认复制」
     */
    @GetMapping("/agents/prompt-slots/{slotKey}/default")
    public ResponseEntity<ApiEnvelope<String>> defaultPrompt(@PathVariable String slotKey) {
        return ResponseEntity.ok(ApiEnvelope.ok(agentProfileAdminService.defaultPrompt(slotKey)));
    }
}
