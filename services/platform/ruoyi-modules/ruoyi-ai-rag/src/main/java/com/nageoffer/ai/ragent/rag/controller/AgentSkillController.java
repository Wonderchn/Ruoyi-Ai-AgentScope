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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import com.nageoffer.ai.ragent.rag.controller.request.AgentSkillPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.AgentSkillSaveRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentSkillToolOptionVO;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentSkillVO;
import com.nageoffer.ai.ragent.rag.service.AgentSkillAdminService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体技能管理控制器（F11-A1 三件套适配）。
 *
 * <p><b>本控制器只作为内层 handler 存在（{@code /internal/ai/v1/agent-catalog/agent-skills**}）</b>。
 * 公开面是网关白名单（{@code AiGatewayController.ROUTES} 的
 * {@code /agent-catalog/agent-skills/**} 7 条），授权在网关按 canonical 动作与 scope 强制
 * （{@code skill.list/read/write/delete} → {@code ai:skill:*}，且四条都过平台管理身份
 * 第二道门），因此本层<b>不加</b> {@code @SaCheckPermission}——与
 * {@code AgentProfileController} / {@code FlowWorkflowController} 同形。
 *
 * <p><b>三件套（RW-22 教训"只归位不换信封＝放行了但不可用"，缺一不可）：</b>
 * <ol>
 *   <li><b>类级前缀</b> {@code /internal/ai/v1/agent-catalog}（原为裸路径，落在
 *       {@code DelegatedPrincipalFilter.PROTECTED_PREFIX} 之外拿不到委托主体，
 *       且逃出内层边界）；</li>
 *   <li><b>信封</b>：ragent {@code Result}（字符串 code）→ {@link ApiEnvelope}（{@code int code}）
 *       ——内嵌传输对非空响应体强制"单 JSON＋整数 code"，保持 {@code Result} 经网关必然 503；</li>
 *   <li><b>交付 permit</b>：三条 GET 在网关走<b>字节分支</b>
 *       （{@code servletResponse != null && method.equals("GET")}），
 *       {@code LocalAiGatewayClient} 在 200/206 时强制校验
 *       {@code X-AI-Delivery-Permit}/{@code X-AI-Delivery-Operation} 两个回执头，
 *       缺失即 503。故 GET handler 经 {@link DeliveryPermits#enter} 铸造回执；
 *       <b>写面走 JSON 分支，不铸回执</b>——JSON 分支不会释放许可，
 *       在那里铸造等于逐请求泄漏 ACTIVE 许可（与 {@code FlowWorkflowController} 同款）。</li>
 * </ol>
 *
 * <p><b>装配</b>：本类不在 platform 扫描根下 ⇒ 由 {@code AiEmbeddedAgentSkillConfiguration}
 * 显式 {@code @Import}（随 S1 引擎闭包门，因为它依赖 {@code IntentNodeRegistry}）；
 * Mapper（{@code AgentSkillMapper}）由 {@code AiEmbeddedMapperConfiguration} 的
 * {@code @MapperScan} 覆盖。权限行由 {@code V33__agent_skill_permissions.sql} 注册
 * （7137-7140 + 7142）。
 */
@RestController
@RequestMapping("/internal/ai/v1/agent-catalog")
@RequiredArgsConstructor
public class AgentSkillController {

    private final AgentSkillAdminService agentSkillAdminService;

    /** 交付许可铸造器（GET 走字节分支，必须带回执头；见类注释三件套③）。 */
    private final DeliveryPermits deliveryPermits;

    /**
     * 分页查询技能，不返回正文（动作 skill.list）
     */
    @GetMapping("/agent-skills")
    public ResponseEntity<ApiEnvelope<IPage<AgentSkillVO>>> page(AgentSkillPageRequest requestParam) {
        ExecutionPrincipal principal = PrincipalContext.require();
        IPage<AgentSkillVO> skills = agentSkillAdminService.pageQuery(requestParam);
        // 先取数据再铸许可：数据侧失败时不留下需要回收的 ACTIVE 许可
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "skill.list", "agent-catalog:skill:list");
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(skills));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 可引用的工具选项，来自意图树里已启用的 MCP 节点（动作 skill.list）
     */
    @GetMapping("/agent-skills/tool-options")
    public ResponseEntity<ApiEnvelope<List<AgentSkillToolOptionVO>>> toolOptions() {
        ExecutionPrincipal principal = PrincipalContext.require();
        List<AgentSkillToolOptionVO> options = agentSkillAdminService.listToolOptions();
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "skill.list", "agent-catalog:skill:tool-options");
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(options));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 查询技能详情，含正文（动作 skill.read）
     */
    @GetMapping("/agent-skills/{id}")
    public ResponseEntity<ApiEnvelope<AgentSkillVO>> detail(@PathVariable String id) {
        ExecutionPrincipal principal = PrincipalContext.require();
        AgentSkillVO skill = agentSkillAdminService.queryById(id);
        DeliveryPermits.Permit permit = deliveryPermits.enter(principal, "skill.read", "agent-catalog:skill:" + id);
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .header("X-AI-Delivery-Permit", permit.permitId())
                    .header("X-AI-Delivery-Operation", permit.operationId())
                    .body(ApiEnvelope.ok(skill));
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * 创建技能（动作 skill.write）
     */
    @PostMapping("/agent-skills")
    public ApiEnvelope<String> create(@RequestBody AgentSkillSaveRequest requestParam) {
        return ApiEnvelope.ok(agentSkillAdminService.create(requestParam));
    }

    /**
     * 更新技能，技能标识不可改（动作 skill.write）
     */
    @PutMapping("/agent-skills/{id}")
    public ApiEnvelope<Void> update(@PathVariable String id, @RequestBody AgentSkillSaveRequest requestParam) {
        agentSkillAdminService.update(id, requestParam);
        return ApiEnvelope.ok(null);
    }

    /**
     * 删除技能，它解锁的工具若不再挂在其它启用技能下，就恢复常驻可见（动作 skill.delete）
     */
    @DeleteMapping("/agent-skills/{id}")
    public ApiEnvelope<Void> delete(@PathVariable String id) {
        agentSkillAdminService.delete(id);
        return ApiEnvelope.ok(null);
    }

    /**
     * 启用或停用技能（动作 skill.write；参数走 query {@code enabled}）
     */
    @PostMapping("/agent-skills/{id}/enabled")
    public ApiEnvelope<Void> toggleEnabled(@PathVariable String id, @RequestParam boolean enabled) {
        agentSkillAdminService.toggleEnabled(id, enabled);
        return ApiEnvelope.ok(null);
    }
}
