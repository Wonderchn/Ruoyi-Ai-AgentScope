package org.ruoyi.common.log.event;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录事件
 *
 * <p>G-53（RW-14）：本事件<b>不再携带 {@code HttpServletRequest}</b>。
 * 客户端事实由生产点在请求线程内捕获为 {@link LoginClientFacts} 不可变快照，
 * 异步监听器只消费快照 —— "读已回收请求"在编译期即不可能。</p>
 *
 * @author Lion Li
 */

@Data
public class LogininforEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 租户ID（<b>可能是请求自行声明的值</b>，见 {@link #tenantVerified}）
     */
    private String tenantId;

    /**
     * <b>服务端是否已核验该租户归属</b>（R-2-R4，裁决 §7.1 执行范围第 1 条）。
     *
     * <p>裁决要求"请求自行声明的租户不能直接成为可信归属"。本事件里的 {@code tenantId}
     * 有两个来源：<b>服务端会话</b>（注销/踢下线时从令牌解析）与<b>请求自行声明</b>
     * （登录/注册请求体）。发布方必须在入队前把"服务端到底核验过没有"写成这个事实，
     * 消费方只认这个事实：{@code false} ⇒ 该行的归属记为平台无归属审计标记。
     *
     * <p>默认 {@code false}：漏设等于"未核验"，宁可记为无归属，也不冒充一个租户。
     */
    private boolean tenantVerified;

    /**
     * 用户账号
     */
    private String username;

    /**
     * 登录状态 0成功 1失败
     */
    private String status;

    /**
     * 提示消息
     */
    private String message;

    /**
     * 客户端不可变事实快照（入队前捕获；异步侧不再读取请求对象）
     */
    private LoginClientFacts clientFacts;

    /**
     * 其他参数
     */
    private Object[] args;

}
