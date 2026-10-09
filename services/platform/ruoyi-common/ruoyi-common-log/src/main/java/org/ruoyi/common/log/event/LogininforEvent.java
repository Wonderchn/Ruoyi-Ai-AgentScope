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
     * 租户ID
     */
    private String tenantId;

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
