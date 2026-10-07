package org.ruoyi.common.log.event;

import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.ruoyi.common.core.utils.ServletUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录审计客户端事实（不可变快照，G-53 / RW-14）。
 *
 * <p>登录审计原先在 {@code @Async} 监听器里读取 {@link HttpServletRequest}：请求线程结束后
 * 容器可能已回收/复位该对象，异步线程再读就会拿到错值或直接抛异常（历史上观察到 request recycled）。
 * 现在改为<b>入队前</b>在请求线程内一次性捕获不可变事实，异步侧只消费本记录，不再接触请求对象。</p>
 *
 * <p>缺 UA（脚本/服务端集成常不发）时降级为 {@link #UNKNOWN}，<b>不抛异常</b>：
 * hutool 5.8.40 的 {@code UserAgentUtil.parse(null/""/"   ")} 返回 {@code null}，
 * 旧实现随后调用 {@code userAgent.getOs().getName()} 会 NPE 并让整条审计记录静默丢失。</p>
 *
 * <p>本记录只承载"事实"，不含任何密钥、口令或凭据；捕获时已做控制字符清洗与长度截断。</p>
 *
 * @param ip        客户端地址（审计落库用完整值）
 * @param browser   浏览器名；无法解析时为 {@link #UNKNOWN}
 * @param os        操作系统名；无法解析时为 {@link #UNKNOWN}
 * @param clientId  客户端标识（{@code clientid} 请求头）；缺失时为 {@code null}
 * @param userAgent 原始 UA 头（已清洗/截断，仅供排查）
 */
public record LoginClientFacts(String ip, String browser, String os, String clientId, String userAgent)
    implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 无法解析时的占位值（与既有 UserActionListener 的降级口径一致）
     */
    public static final String UNKNOWN = "Unknown";

    /**
     * 捕获的头部值上限，避免超长/恶意头进入事件与审计表
     */
    public static final int MAX_HEADER_LENGTH = 256;

    private static final String USER_AGENT_HEADER = "User-Agent";

    /**
     * 在请求线程内捕获不可变事实。绝不保存请求对象本身。
     *
     * @param request 当前请求；允许为 {@code null}（无请求上下文，例如非 HTTP 触发）
     * @return 不可变快照；请求缺失时返回 {@link #unknown()}
     */
    public static LoginClientFacts capture(HttpServletRequest request) {
        if (request == null) {
            return unknown();
        }
        String userAgentHeader = sanitizeHeader(request.getHeader(USER_AGENT_HEADER));
        String clientId = sanitizeHeader(request.getHeader(LoginHelper.CLIENT_KEY));
        String ip = sanitizeHeader(ServletUtils.getClientIP(request));
        if (StringUtils.isBlank(ip)) {
            ip = UNKNOWN;
        }
        UserAgent userAgent = StringUtils.isBlank(userAgentHeader) ? null : UserAgentUtil.parse(userAgentHeader);
        return new LoginClientFacts(ip, nameOf(userAgent, true), nameOf(userAgent, false), clientId, userAgentHeader);
    }

    /**
     * 无客户端事实可用时的兜底快照（例如事件未携带快照）。
     */
    public static LoginClientFacts unknown() {
        return new LoginClientFacts(UNKNOWN, UNKNOWN, UNKNOWN, null, null);
    }

    /**
     * 日志/对外输出用的脱敏 IP：IPv4 掩掉最后一段、IPv6 掩掉最后一段分组，其余原样返回。
     * 审计落库仍使用完整 {@link #ip()}（登录日志页面与取证依赖完整值）。
     */
    public String maskedIp() {
        if (StringUtils.isBlank(ip) || UNKNOWN.equals(ip)) {
            return StringUtils.blankToDefault(ip, UNKNOWN);
        }
        int lastColon = ip.lastIndexOf(':');
        if (lastColon > 0 && ip.indexOf('.') < 0) {
            return ip.substring(0, lastColon + 1) + "*";
        }
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) {
            return ip.substring(0, lastDot + 1) + "*";
        }
        return ip;
    }

    private static String nameOf(UserAgent userAgent, boolean browser) {
        if (userAgent == null) {
            return UNKNOWN;
        }
        Object name = browser
            ? (userAgent.getBrowser() == null ? null : userAgent.getBrowser().getName())
            : (userAgent.getOs() == null ? null : userAgent.getOs().getName());
        if (name == null) {
            return UNKNOWN;
        }
        String value = name.toString().trim();
        return value.isEmpty() ? UNKNOWN : value;
    }

    /**
     * 清洗捕获值：去掉 CR/LF 等控制字符（防日志伪造）、去首尾空白、截断超长值。
     */
    private static String sanitizeHeader(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(Math.min(raw.length(), MAX_HEADER_LENGTH));
        for (int i = 0; i < raw.length() && builder.length() < MAX_HEADER_LENGTH; i++) {
            char ch = raw.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                continue;
            }
            builder.append(ch);
        }
        String cleaned = builder.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
