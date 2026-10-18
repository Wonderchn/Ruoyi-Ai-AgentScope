package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.mcp.admin.AgentMcpAllowListProperties;
import com.nageoffer.ai.ragent.mcp.admin.McpAdminController;
import com.nageoffer.ai.ragent.mcp.admin.McpAdminServiceImpl;
import com.nageoffer.ai.ragent.mcp.admin.McpEndpointAllowList;
import com.nageoffer.ai.ragent.mcp.admin.McpRemoteCatalogClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * F12-M1：MCP 管理面（{@code /internal/ai/v1/mcp/**}）的内层装配。
 *
 * <p><b>为什么必须显式登记</b>：控制器/服务/allow-list 都在
 * {@code com.nageoffer.ai.ragent.*} 下，而 platform 的扫描根是 {@code org.ruoyi}，
 * 本模块族不做根包扫描。缺这一件，网关白名单放行了也没有内层 handler ⇒ 404。
 *
 * <p><b>门控</b>：{@code ai.integration.enabled=true}（外层）
 * × {@code transport=local}（{@code @ConditionalOnEmbeddedLocal}）
 * × {@code p2.enabled=true}（GET 交付回执铸造器 {@code DeliveryPermits} 在场）。
 * 任一不满足即整体消失，不装半个面、也不在关闭时要求协作对象在场。
 *
 * <p><b>Mapper 扫描</b>：三个 Mapper 都在
 * {@code com.nageoffer.ai.ragent.agent.dao.mapper}——已被
 * {@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 覆盖，imports 文件只追加本类一行。
 *
 * <p><b>数据面</b>：{@code ai_mcp_market}/{@code ai_mcp_market_tool}/{@code ai_mcp_tool}
 * 由冻结迁移 {@code V9__legacy_ai_domain_ddl.sql} 建立（本包不改 DDL）；
 * 五个权限动作的 F 行由 {@code V39__mcp_catalog_permissions.sql} 注册（默认不分配）。
 *
 * <p><b>授权不在本层</b>：公开面必须经 {@code AiGatewayController} 白名单（18 条逐条登记）
 * ＋ {@code AiCanonicalAction} 的精确 scope 比较（{@code mcp.list/read/write/delete/test}
 * → {@code ai:mcp:*}，五条全进平台管理身份第二道门）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedMcpAdminConfiguration {

    /**
     * MCP 管理面整体装配：local 形态 + P2 交付面在场时才注册；
     * 关闭时控制器/服务/allow-list 一并消失（fail-closed 404）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @EnableConfigurationProperties(AgentMcpAllowListProperties.class)
    @Import({McpAdminController.class, McpAdminServiceImpl.class,
            McpEndpointAllowList.class, McpRemoteCatalogClient.class})
    static class McpAdmin {
    }
}
