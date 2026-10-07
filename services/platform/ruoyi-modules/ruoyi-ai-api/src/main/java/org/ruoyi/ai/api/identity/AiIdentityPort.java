package org.ruoyi.ai.api.identity;

import org.ruoyi.ai.api.AiExecutionFacts;

import java.util.Optional;

/**
 * 当前执行事实的来源端口。
 *
 * <p>由 platform 侧实现（读取已验证的登录态与当前成员事实），AI 模块只消费。
 * 这一对方向是内嵌的关键：以前是 AI 通过 {@code PlatformAuthorizationClient} 回调 platform 的
 * {@code /internal/platform/v1/authorization/current}，现在退化为一次本地方法调用。
 *
 * <p>实现约定（全部为安全语义，不是性能优化）：
 * <ul>
 *   <li>只依据已验证的登录态构造事实；请求 body/header 里的 tenantId/userId/membershipId 不参与；</li>
 *   <li>租户停用、成员停用或不存在时返回 {@link Optional#empty()}，调用方必须拒绝，不得回落到默认租户；</li>
 *   <li>policyVersion/aclVersion 必须来自权威存储；取不到时返回 empty（不是返回 0 或 1）；</li>
 *   <li>不返回空 scopes 表示"全部允许"——空集合就是空授权，检索与资源读取必须返回空或拒绝。</li>
 * </ul>
 */
public interface AiIdentityPort {

    /**
     * 解析当前请求的执行事实。
     *
     * @return 可用时返回事实；未登录、租户/成员不可用或版本不可得时返回 empty
     */
    Optional<AiExecutionFacts> currentFacts();
}
