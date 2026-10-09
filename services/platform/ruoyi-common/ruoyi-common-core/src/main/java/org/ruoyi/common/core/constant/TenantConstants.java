package org.ruoyi.common.core.constant;

/**
 * 租户常量信息
 *
 * @author Lion Li
 */
public interface TenantConstants {

    /**
     * 超级管理员ID
     */
    Long SUPER_ADMIN_ID = 1L;

    /**
     * 超级管理员角色 roleKey
     */
    String SUPER_ADMIN_ROLE_KEY = "superadmin";

    /**
     * 租户管理员角色 roleKey
     */
    String TENANT_ADMIN_ROLE_KEY = "admin";

    /**
     * 租户管理员角色名称
     */
    String TENANT_ADMIN_ROLE_NAME = "管理员";

    /**
     * 默认租户ID
     */
    String DEFAULT_TENANT_ID = "000000";

    /**
     * <b>平台无归属审计标记</b>（R-2-R4，维护者裁决 §7.1）。
     *
     * <p>含义是「<b>未确认真实租户归属</b>」，<b>不是</b>一个新租户：
     * <ul>
     *   <li>不得为它创建 {@code sys_tenant} 行、登录身份或业务授权；</li>
     *   <li>不得把它当作普通动态切租户目标；</li>
     *   <li>只用于 {@code sys_oper_log} / {@code sys_logininfor} 两张审计表的
     *       <b>无归属审计行</b>，且必须由审计入口<b>显式写入 INSERT</b>，
     *       不得靠 DDL 默认值或"补齐上下文"冒充。</li>
     * </ul>
     *
     * <p>长度 18，适配这两张表既有的 {@code varchar(20)} 列（裁决要求"优先复用现有列"）。
     * 仓内冲突核对：全仓扫描无其它出现（见 R-2-R4 报告）；真实库冲突核对由后续发布准备承担。
     */
    String PLATFORM_AUDIT_TENANT_ID = "__platform_audit__";

}
