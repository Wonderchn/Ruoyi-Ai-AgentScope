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

package com.nageoffer.ai.ragent.rag.binding;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefLookup;
import com.nageoffer.ai.ragent.rag.binding.AgentProfileBindingService.RefType;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentSkillDO;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * P3-BIND：绑定引用的**生产适配**（既有资产面在线核引用）。
 *
 * <table>
 *   <tr><th>类型</th><th>核验源</th><th>语义</th></tr>
 *   <tr><td>SKILL</td><td>{@code ai_agent_skill}</td><td>存在且 {@code enabled=1}</td></tr>
 *   <tr><td>MCP</td><td>{@code ai_intent_node.mcp_tool_id}</td><td>意图树存在该工具且 {@code enabled=1}</td></tr>
 *   <tr><td>MODEL</td><td>—</td><td><b>fail-closed</b>：模型目录不在本模块（`ModelSelector` 属 infra-ai），
 *       本层无法核验 ⇒ 一律拒绝；接线需 runtime 侧目录（另议），接线前"宁拒不放行"</td></tr>
 *   <tr><td>ORCHESTRATION</td><td>—</td><td>由服务层闭集校验，不到本层</td></tr>
 * </table>
 */
@Component
@RequiredArgsConstructor
public class DefaultBindingRefLookup implements RefLookup {

    private final AgentSkillMapper agentSkillMapper;
    private final IntentNodeMapper intentNodeMapper;

    @Override
    public boolean existsEnabled(String tenantId, RefType type, String refId) {
        if (type == null || !StringUtils.hasText(refId)) {
            return false;
        }
        return switch (type) {
            case SKILL -> agentSkillMapper.selectCount(Wrappers.lambdaQuery(AgentSkillDO.class)
                    .eq(AgentSkillDO::getSkillCode, refId)
                    .eq(AgentSkillDO::getEnabled, 1)) > 0;
            case MCP -> intentNodeMapper.selectCount(Wrappers.lambdaQuery(IntentNodeDO.class)
                    .eq(IntentNodeDO::getMcpToolId, refId)
                    .eq(IntentNodeDO::getEnabled, 1)) > 0;
            case MODEL -> false;
            case ORCHESTRATION -> false;
        };
    }
}
