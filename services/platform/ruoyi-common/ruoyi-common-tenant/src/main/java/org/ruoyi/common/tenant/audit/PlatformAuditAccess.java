package org.ruoyi.common.tenant.audit;

import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.satoken.utils.LoginHelper;

/**
 * 平台审计域访问凭据（R-2-R4，维护者裁决 §7.1 执行范围第 4 条）。
 *
 * <p>无归属审计行（{@code tenant_id = __platform_audit__}）<b>不属于任何租户</b>：
 * 租户用户、租户管理员、以及真实 {@code 000000} 租户的管理员都不得读取、导出、删除或清理它们。
 * 平台超管必须在一个<b>明确、可审计</b>的平台管理上下文中访问——本类就是那个上下文：
 *
 * <ul>
 *   <li>只能由 {@link #requirePlatformSuperAdmin()} 取得，非超管直接拒绝；</li>
 *   <li>取得即写一条可检索的审计日志（谁、什么范围）；</li>
 *   <li>平台审计读接口<b>要求传入本对象</b>，类型系统保证调用方必须过这道门；</li>
 *   <li>超管在普通租户作用域下仍按该租户隔离——本对象<b>不</b>改变任何环境上下文，
 *       它只是打开"平台审计域"这一个显式读接口，不放大普通读接口的可见范围。</li>
 * </ul>
 */
@Slf4j
public final class PlatformAuditAccess {

    private static final PlatformAuditAccess GRANTED = new PlatformAuditAccess();

    private PlatformAuditAccess() {
    }

    /**
     * 取得平台审计域访问凭据。<b>仅平台超管可取得</b>；每次取得都留一条审计日志。
     *
     * @throws ServiceException 非平台超管
     */
    public static PlatformAuditAccess requirePlatformSuperAdmin() {
        if (!LoginHelper.isSuperAdmin()) {
            log.warn("平台审计域访问被拒绝：调用方不是平台超管 userId={}", LoginHelper.getUserId());
            throw new ServiceException("平台审计域仅限平台超管访问");
        }
        log.warn("平台审计域访问：平台超管 userId={}，范围=无归属审计行 tenant_id={}",
                LoginHelper.getUserId(), TenantConstants.PLATFORM_AUDIT_TENANT_ID);
        return GRANTED;
    }
}
