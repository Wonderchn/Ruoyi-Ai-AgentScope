package org.ruoyi.system.service;

import org.ruoyi.common.core.constant.HttpStatus;
import org.ruoyi.common.core.exception.ServiceException;

/**
 * 认证业务失败的稳定安全拒绝（G-53 / RW-14）。
 *
 * <p>背景：认证失败原先抛 {@code UserException}（{@code BaseException} 子类），
 * 经 {@code GlobalExceptionHandler#handleBaseException} 统一变成 {@code code=500} 的
 * "请求处理失败" —— 客户端<b>无法区分"被拒"与"服务坏了"</b>。</p>
 *
 * <p>口径（本类是该口径的唯一出口）：</p>
 * <ul>
 *     <li><b>稳定</b>：所有认证业务失败（账号不存在、口令错误、账号停用、验证码失效、重试锁定）
 *         返回同一个 {@link #DENIED_CODE} 与同一句 {@link #DENIED_MESSAGE}，不随失败原因变化；</li>
 *     <li><b>安全</b>：不泄露账号存在性 —— 客户端响应里没有任何可区分"用户不存在"与"口令错误"的字段；</li>
 *     <li><b>不削弱</b>：拒绝仍然是拒绝（既有 {@code if (supplier.get())} 判定与重试计数、锁定策略不变），
 *         也不会把失败改成成功或空态。</li>
 * </ul>
 *
 * <p>落地方式刻意不动公共面：{@link ServiceException} 是 common 现有类型，
 * 现成的 {@code GlobalExceptionHandler#handleServiceException} 会把 {@link ServiceException#getCode()}
 * 放进 {@code R.code}（HTTP 仍为 200 的 R 信封）。{@link #DENIED_CODE} 取 401，
 * 与通用失败码 {@code R.FAIL=500} 明确区分。</p>
 *
 * <p>文案只出现在服务端日志（异常信封对外被统一替换为安全文案），因此不含账号/口令等敏感内容。</p>
 */
public final class AuthDenial {

    /**
     * 稳定安全拒绝码（HTTP 401 语义）：客户端据此区分"被拒"与"服务端错误"。
     */
    public static final int DENIED_CODE = HttpStatus.UNAUTHORIZED;

    /**
     * 对外固定、与失败原因无关的安全文案。
     */
    public static final String DENIED_MESSAGE = "认证失败";

    private AuthDenial() {
    }

    /**
     * 构造稳定拒绝异常。调用方一律 {@code throw AuthDenial.denied();}。
     */
    public static ServiceException denied() {
        return new ServiceException(DENIED_MESSAGE, DENIED_CODE);
    }

    /**
     * 判断异常是否为稳定认证拒绝（供测试与上层归因使用，不作为放行依据）。
     */
    public static boolean isDenial(Throwable throwable) {
        return throwable instanceof ServiceException serviceException
            && Integer.valueOf(DENIED_CODE).equals(serviceException.getCode());
    }
}
