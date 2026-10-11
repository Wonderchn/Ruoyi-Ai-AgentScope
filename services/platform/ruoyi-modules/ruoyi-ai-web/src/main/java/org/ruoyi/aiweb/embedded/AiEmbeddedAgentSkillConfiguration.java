package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.controller.AgentSkillController;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillCacheManager;
import com.nageoffer.ai.ragent.rag.service.impl.AgentSkillAdminServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * S2-F11-A1：Skills 管理面（{@code /internal/ai/v1/agent-catalog/agent-skills**}）的内层装配。
 *
 * <p><b>为什么必须显式登记</b>：{@link AgentSkillController} / {@link AgentSkillAdminServiceImpl}
 * / {@link AgentSkillCacheManager} 都在 {@code com.nageoffer.ai.ragent.*} 下，而 platform 的
 * 扫描根是 {@code org.ruoyi}，且本模块族不做根包扫描、由内嵌配置显式列举
 * （{@code AiEmbeddedAgentCatalogConfiguration} 记录过同名教训）。缺这一件，
 * 网关白名单放行了也没有内层 handler ⇒ 404（"放行了但没人接"）。
 *
 * <p><b>为什么随引擎闭包门（{@code ragent.engine.type=agent}）。</b>
 * {@code AgentSkillAdminServiceImpl} 的构造依赖 {@code IntentNodeRegistry}（工具选项/工具白名单
 * 取自意图树 MCP 节点），其唯一实现 {@code DefaultIntentClassifier} 由 <b>S1 引擎闭包批</b>
 * （{@code AiEmbeddedAgentEngineClosureConfiguration}，同三重门内）装配——F11-A1 **不重复迁入**
 * 该闭包、也不用空实现顶替（Spec §4.2 纪律）。因此本面与引擎闭包同门：引擎关时本面整体不装
 * （fail-closed 404/503，不出现"控制器装了、依赖没装"的半装配）；引擎开且 S1 闭包在场的形态下，
 * {@code IntentNodeRegistry} 必然存在。
 *
 * <p><b>为什么还要 {@code p2.enabled=true}</b>：三条 GET 在网关走字节分支，控制器必须自带
 * 交付回执铸造器 {@code DeliveryPermits}——它由 {@code AiEmbeddedRunConfiguration.P2Enabled}
 * （{@code transport=local} + {@code p2.enabled=true}）提供。与 {@code AiEmbeddedFlowConfiguration}
 * 同款门控：缺 P2 交付面时不装，而不是装一个拿不到回执铸造器的控制器。
 *
 * <p><b>授权不在本层</b>：内层前缀 {@code /internal/ai/v1/**} 对外由
 * {@code AiInternalAccessBoundaryFilter} 关成 404；公开面必须经 {@code AiGatewayController}
 * 白名单（{@code /agent-catalog/agent-skills/**} 7 条）+ {@code AiCanonicalAction} 的精确 scope
 * 比较（{@code skill.list/read/write/delete} → {@code ai:skill:*}），且四条动作都过
 * <b>平台管理身份</b>第二道门（{@code PLATFORM_ADMIN_ACTIONS}，Spec §6-P4）。因此内层
 * controller 上<b>不加</b> {@code @SaCheckPermission}——与 {@code AgentProfileController} /
 * {@code FlowWorkflowController} 同形。
 *
 * <p><b>双份定义防范（Spec §4.2）</b>：{@code AgentSkillCacheManager} 同时可能被 S1 引擎块
 * 登记——同类 {@code @Import} 幂等（同名 bean 单一定义），本类因此照常 {@code @Import} 它，
 * 不写 {@code @Bean} 工厂（避免"同类型两个 bean"的注入歧义）；单实例由
 * {@code AiEmbeddedAgentSkillActivationTest} 钉住。{@code AgentSkillRegistry}（读端）
 * 由 S1 闭包拥有，本面**不**登记。{@code BizChangeLogContext}（审计上下文，写路径
 * {@code @LogRecord} 依赖）与 F09 目录面同款登记（同类幂等）。
 *
 * <p>数据面：{@code ai_agent_skill} 已在冻结迁移 {@code V7__unified_ai_domain.sql} 建立
 * （租户唯一键 + NOT NULL + 非空白 CHECK）；权限行见 {@code V33__agent_skill_permissions.sql}
 * （沿用 V27 预留 7137-7140 + 7142）。Mapper（{@code AgentSkillMapper}）由
 * {@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 覆盖
 * （包清单第一项即 {@code com.nageoffer.ai.ragent.rag.dao.mapper}），imports 文件未动。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedAgentSkillConfiguration {

    /**
     * Skills 管理面：{@code transport=local}（经 {@code @ConditionalOnEmbeddedLocal}）
     * × {@code p2.enabled=true}（交付回执铸造器在场）× {@code ragent.engine.type=agent}
     * （S1 引擎闭包提供 {@code IntentNodeRegistry}）三重门内整体装配；任一不满足即整体消失，
     * 不装半个面、也不在关闭时要求协作对象在场（否则"关掉开关的应用起不来"）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnExpression("'${p2.enabled:false}' == 'true' and '${ragent.engine.type:}' == 'agent'")
    @Import({AgentSkillController.class, AgentSkillAdminServiceImpl.class,
            AgentSkillCacheManager.class, BizChangeLogContext.class})
    static class SkillAdmin {
    }
}
