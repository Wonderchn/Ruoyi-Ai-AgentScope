package org.ruoyi.common.tenant.audit;

import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.core.utils.StringUtils;

/**
 * 审计行归属判定（R-2-R4，维护者裁决 §7.1）。
 *
 * <p>本类是「一条审计行该记在谁名下」的<b>唯一</b>判定点。规则只有两条：
 *
 * <ol>
 *   <li><b>归属已确认</b>（{@code tenantVerified == true} 且租户号非空、且不是保留值）
 *       ⇒ 记在<b>该真实租户</b>名下；</li>
 *   <li>其余全部情形 ⇒ 记 {@link TenantConstants#PLATFORM_AUDIT_TENANT_ID}
 *       （「未确认真实租户归属」），并<b>记录原因</b>。</li>
 * </ol>
 *
 * <p><b>为什么要有 {@code tenantVerified}</b>：裁决要求「请求自行声明的租户不能直接成为可信归属」。
 * 审计事件里的租户号有两个来源——<b>服务端会话</b>（如操作日志取登录令牌里的租户）与
 * <b>请求自行声明</b>（如登录请求体里的租户）。前者可信，后者只有在服务端实际核验过
 * （登录成功、注册成功、注销/踢下线的令牌解析）之后才可信。发布方在入队前把这个事实
 * 写进事件，消费方只认这个事实，不猜。
 *
 * <p><b>保留值不是租户</b>：它不会被 {@code sys_tenant} 认领，也不得作为动态切租户目标；
 * 本类把它当作"无归属"的表示，而不是"某个租户"。
 */
public final class PlatformAuditAttribution {

    private PlatformAuditAttribution() {
    }

    /**
     * 判定一条审计行的归属。
     *
     * @param declaredTenantId 事件里携带的租户号（可为空）
     * @param tenantVerified   服务端是否已核验该租户归属
     * @return 真实租户号，或 {@link TenantConstants#PLATFORM_AUDIT_TENANT_ID}
     */
    public static String resolve(String declaredTenantId, boolean tenantVerified) {
        if (tenantVerified
                && StringUtils.isNotBlank(declaredTenantId)
                && !isPlatformAudit(declaredTenantId)) {
            return declaredTenantId;
        }
        return TenantConstants.PLATFORM_AUDIT_TENANT_ID;
    }

    /**
     * 判定归属，并把"为什么没有归属"的原因一并返回（供审计入口记录）。
     */
    public static Attribution resolveWithReason(String declaredTenantId, boolean tenantVerified) {
        if (tenantVerified) {
            if (StringUtils.isBlank(declaredTenantId)) {
                return new Attribution(TenantConstants.PLATFORM_AUDIT_TENANT_ID, "已核验但租户号为空");
            }
            if (isPlatformAudit(declaredTenantId)) {
                return new Attribution(TenantConstants.PLATFORM_AUDIT_TENANT_ID, "声明值即保留值，不是真实租户");
            }
            return new Attribution(declaredTenantId, null);
        }
        if (StringUtils.isBlank(declaredTenantId)) {
            return new Attribution(TenantConstants.PLATFORM_AUDIT_TENANT_ID, "事件未携带租户");
        }
        return new Attribution(TenantConstants.PLATFORM_AUDIT_TENANT_ID, "租户归属未经服务端核验");
    }

    /**
     * <b>登录审计状态是否意味着"服务端已经核验过租户归属"</b>（R-2-R4）。
     *
     * <p>只有这三种状态是在服务端真的解析/核验过主体之后才发布的：
     * <ul>
     *   <li>{@code LOGIN_SUCCESS}——认证策略在该租户下加载到了用户；</li>
     *   <li>{@code LOGOUT}——租户来自登录令牌本身（会话事实，不是请求声明）；</li>
     *   <li>{@code REGISTER}——注册流程接受了该租户（空白租户会被拒，见 F3）。</li>
     * </ul>
     * 其余（重试超限、密码错误、验证码过期/错误、账号被封）都发生在核验<b>之前</b>，
     * 那时的租户只是<b>请求自行声明</b>的值，按裁决不得直接成为可信归属。
     */
    public static boolean verifiedByStatus(String status) {
        return Constants.LOGIN_SUCCESS.equals(status)
                || Constants.LOGOUT.equals(status)
                || Constants.REGISTER.equals(status);
    }

    /** 该租户号是否是平台无归属审计标记。 */
    public static boolean isPlatformAudit(String tenantId) {
        return TenantConstants.PLATFORM_AUDIT_TENANT_ID.equals(tenantId);
    }

    /**
     * 归属判定结果。
     *
     * @param tenantId 实际要写进 {@code tenant_id} 的值
     * @param reason   无归属时的原因（有归属时为 {@code null}）
     */
    public record Attribution(String tenantId, String reason) {

        public boolean unattributed() {
            return isPlatformAudit(tenantId);
        }
    }
}
