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

package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.flow.controller.FlowWorkflowController;
import com.nageoffer.ai.ragent.flow.service.impl.FlowWorkflowServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * AIFlow 工作流定义面（{@code /internal/ai/v1/flows**}）的内嵌装配（F13-SLICE-1）。
 *
 * <p><b>为什么需要这一组。</b>{@link FlowWorkflowController} 在
 * {@code com.nageoffer.ai.ragent.flow.controller} 下，而 platform 的扫描根是
 * {@code org.ruoyi} ⇒ 内嵌形态里它<b>从来不是 bean</b>。这不是"接口写错"，是"没装配"。
 * 与 {@code AiEmbeddedKnowledgeAdminConfiguration} 对 {@code KnowledgeChunkController}
 * 的处置同形。
 *
 * <p><b>装配三件套（缺一即"服务端写好但客户端到不了"）</b>：
 * <ol>
 *   <li><b>本类</b>显式 {@code @Import} 登记实现类与控制器，并进入
 *       {@code AutoConfiguration.imports}；</li>
 *   <li>Mapper 包 {@code com.nageoffer.ai.ragent.flow.dao.mapper} 必须追加到
 *       {@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 数组
 *       —— 那里的包清单是固定数组，新包不会被自动带到；</li>
 *   <li>{@code AiGatewayController.ROUTES} 逐条登记四条公开路由。</li>
 * </ol>
 *
 * <p><b>闭包已逐项核过</b>：{@code FlowWorkflowServiceImpl} 只依赖
 * {@code AiFlowWorkflowMapper}（由 (2) 覆盖）与 {@code PrincipalContext}（线程局部，非 bean）；
 * 控制器另依赖 {@code DeliveryPermits}（由 {@code AiEmbeddedRunConfiguration} 的
 * {@code P2Enabled} 提供）⇒ 除上面两项外<b>无需补任何 bean</b>。
 * 闭包未闭合时不装（fail-closed），不做"控制器装了、依赖没装"的半装配。
 *
 * <p><b>为什么跟随 {@code p2.enabled} 门控。</b>两条 GET 在网关走<b>字节分支</b>，
 * 必须铸造交付回执头，而 {@code DeliveryPermits} 由 {@code p2.enabled=true} 提供。
 * 不跟随的话，{@code p2.enabled=false} 的部署会在<b>启动阶段</b>因缺 bean 失败；
 * 跟随则表现为"交付面不在 ⇒ 该面不装"，fail-closed 且可解释。
 *
 * <p><b>授权不在本层</b>：内层前缀对外由 {@code AiInternalAccessBoundaryFilter} 关成 404，
 * 公开面必须过网关白名单 + canonical 动作比较，因此内层 controller
 * 不加 {@code @SaCheckPermission}（与 {@code KnowledgeChunkController}/{@code UploadController} 同形）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedFlowConfiguration {

    /**
     * 工作流定义 CRUD 面：闭包已闭合（见类注释），随内嵌 local 传输 + p2 交付面装配。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @Import({FlowWorkflowServiceImpl.class, FlowWorkflowController.class})
    static class FlowDefinitionCrud {
    }
}
