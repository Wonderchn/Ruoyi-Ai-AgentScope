package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.controller.AgentProfileController;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.service.impl.AgentProfileAdminServiceImpl;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/**
 * W4-9（F09 only）：Agent 定义与 Prompt 管理面的内层装配。
 *
 * <p><b>为什么必须显式登记</b>：这些类都在 {@code com.nageoffer.ai.ragent.*}，平台扫描根是
 * {@code org.ruoyi}，且本模块族的装配纪律是"不做根包扫描、由内嵌配置显式列举"。K1（W3-4）的
 * {@code McpEmbeddedServerConfiguration} 用的是同款形态。
 *
 * <p><b>⚠️ 装配在场 ≠ 生效（W4-T0-46 实测教训，本类据此修正）</b>：
 * {@code AiEmbeddedAgentEngineConfiguration} 里出现的 {@code AgentPromptResolver} /
 * {@code AgentPromptCacheManager} / {@code AgentSkillRegistry} / {@code IntentNodeRegistry}
 * 是 {@code EngineChainReadiness.REQUIRED} 的<b>启动自检类型清单</b>（用于断言"该链就绪时这些
 * 类型必须在场"），<b>不是</b> {@code @Import} 清单；真正的 import 位于该文件内一个被
 * {@code ai.integration.transport=local} 与 {@code ragent.engine.type=agent}
 * <b>双重门控</b>的嵌套块中。本形态（{@code dev,embedded}）两个开关都未设 ⇒ 这些组件
 * <b>在 v6 里也不是 bean</b>（6046 实 beans 转储计数全 0）。因此本类必须自己把 F09 需要的
 * 运行期组件一并登记，否则 {@code AgentProfileAdminServiceImpl} 构造参数会找不到
 * {@code AgentPromptResolver}，应用 {@code APPLICATION FAILED TO START}。
 *
 * <p><b>F11（Skills）本批刻意不装</b>：{@code AgentSkillAdminServiceImpl} 依赖
 * {@code IntentNodeRegistry}，其唯一实现 {@code DefaultIntentClassifier} 又需要
 * {@code LLMService} / {@code PromptTemplateLoader} / {@code IntentTreeCacheManager} 等，
 * 该闭包同样未装配；盲装会反复重启试错。已另立卡（task-13 结论 / W4-T0-46），
 * 届时算清闭包再装。因此本类<b>不</b> import {@code AgentSkillController}、
 * {@code AgentSkillAdminServiceImpl}、{@code AgentSkillCacheManager}。
 *
 * <p><b>授权不在本层</b>：内层前缀 {@code /internal/ai/v1/**} 对外由
 * {@code AiInternalAccessBoundaryFilter} 关成 404；公开面必须经 {@code AiGatewayController}
 * 白名单（本批只登记 {@code /agent-catalog/agents/**} 8 条）+ {@code AiCanonicalAction} 的
 * scope 比较（{@code AiGatewayController:274-277}）。因此内层 controller 上<b>不加</b>
 * {@code @SaCheckPermission}——与 {@code AiResourceController} / {@code AgentChatController} 同形。
 *
 * <p>数据面：{@code ai_agent_profile} / {@code ai_agent_prompt} 已在冻结迁移
 * {@code V7__unified_ai_domain.sql} 建立；权限行见 {@code V27__agent_catalog_permissions.sql}
 * （本批只播 F09 的 5 条 + 1 条页面 C 行）。Mapper（{@code AgentProfileMapper} /
 * {@code AgentPromptMapper}）由 {@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan}
 * 覆盖（包清单第一项即 {@code com.nageoffer.ai.ragent.rag.dao.mapper}）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
@Import({
        AgentProfileController.class,
        AgentProfileAdminServiceImpl.class,
        // 运行期读端：v6 里不是 bean（见类注释的"装配在场 ≠ 生效"段），必须由本类登记。
        AgentPromptResolver.class,
        AgentPromptCacheManager.class,
        // 该类自带 @Configuration + @ConfigurationProperties(prefix = "ragent.engine")：
        // 用 @Import 注册即可。**不要**再写 @EnableConfigurationProperties(同类型)——
        // 那会注册第二个同类型 bean（BRIEF §4 已登记该坑）。
        OrchestrationProperties.class,
        // 审计上下文：AgentProfileAdminServiceImpl 的构造依赖之一，v6 里也不是 bean。
        BizChangeLogContext.class,
        // 公共模板域只读仓库（P1.3a）：R12 卡6 起作为「取槽位默认值」的模板域回退依赖
        // （内置默认存于 __public_template__ 域，租户过滤链看不到），必须登记为 bean。
        PublicTemplateRepository.class
})
public class AiEmbeddedAgentCatalogConfiguration {
}
