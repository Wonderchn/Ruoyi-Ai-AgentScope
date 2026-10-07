package org.ruoyi.ai.api.authz;

import org.ruoyi.ai.api.AiExecutionFacts;

import java.time.Duration;
import java.util.Optional;

/**
 * 执行许可与撤权屏障端口。
 *
 * <p>对应原 P1/P2/P3 的跨服务调用：
 * {@code POST /internal/platform/v1/authorization/permits/acquire|release} 与
 * AI 侧的 {@code RevocationBarrierController}。内嵌后它们成为本地接口，但<b>语义一条不减</b>：
 *
 * <ul>
 *   <li>许可按 (tenantId, memberId) 授予，带 lease 与 fence；许可失效只说明"与持有者失联"，
 *       <b>不代表</b>执行成功，调用方不得据此推断结果；</li>
 *   <li>租户屏障处于 PENDING/CLOSED/UNKNOWN 时拒绝新许可；UNKNOWN 一律按拒绝处理；</li>
 *   <li>撤销是"短事务设置屏障 → 等待执行/输出排空 → 提交新版本"的三段式，
 *       不允许在长事务里等 Worker 或网络流结束；</li>
 *   <li>撤权后，已建立的 SSE、下载与正在执行的工具在下一次复核点必须被拒。</li>
 * </ul>
 *
 * <p>本端口只描述契约。数据库行锁、epoch 递增、预算预占等实现细节由 runtime 模块负责，
 * 并保持与 P1/P2/P3 相同的可观测行为。
 */
public interface AiPermitPort {

    /** 租户屏障状态。UNKNOWN 表示无法确认，按拒绝处理。 */
    enum BarrierState {
        OPEN,
        PENDING,
        CLOSED,
        UNKNOWN
    }

    /** 许可（执行凭证）。 */
    record Permit(String permitId, String tenantId, String memberId, long fence, Duration lease) {
    }

    /**
     * 申请执行许可。
     *
     * @param facts  申请者执行事实
     * @param reason 动作标识（用于审计）
     * @return 许可；租户屏障非 OPEN 或主体已撤权时返回 empty
     */
    Optional<Permit> acquire(AiExecutionFacts facts, String reason);

    /**
     * 释放许可。
     *
     * @param permitId 许可标识；未知或已过期的许可不报错（释放是幂等的）
     */
    void release(String permitId);

    /**
     * 查询租户屏障状态。
     */
    BarrierState barrierState(String tenantId);

    /**
     * 复核当前授权是否仍然有效。SSE 续订、下载、工具执行前都要调用。
     *
     * @return 有效时返回 true；撤权、成员停用、版本落后或状态不可得时返回 false
     */
    boolean stillAuthorized(AiExecutionFacts facts);
}
