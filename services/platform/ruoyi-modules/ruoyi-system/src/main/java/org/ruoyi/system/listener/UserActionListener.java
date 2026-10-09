package org.ruoyi.system.listener;

import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.convert.Convert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.CacheConstants;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.domain.dto.UserOnlineDTO;
import org.ruoyi.common.core.utils.MessageUtils;
import org.ruoyi.common.core.utils.ServletUtils;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.core.utils.ip.AddressUtils;
import org.ruoyi.common.log.event.LoginClientFacts;
import org.ruoyi.common.log.event.LogininforEvent;
import org.ruoyi.common.redis.utils.RedisUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.service.SysLoginService;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户行为 侦听器的实现
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Component
@Slf4j
public class UserActionListener implements SaTokenListener {

    private final SysLoginService loginService;

    /**
     * 每次登录时触发
     */
    @Override
    public void doLogin(String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {
        // WP-039（T4）F-8 修复 + G-53（RW-14）：HTTP 不要求客户端发 User-Agent（脚本/服务端集成常不发），
        // 原实现直接 UserAgentUtil.parse(header) 再 userAgent.getBrowser().getName()，
        // 缺 UA 时会 NPE 并把**登录打成 500**（T8 实测：HttpClient 默认不发 UA → 500，补 UA 立刻 200）。
        // 现在把 UA/IP/client 一次性捕获成不可变快照：在线用户与登录审计共用同一份事实，
        // 缺 UA 降级为 Unknown，且异步审计不再读取请求对象。
        LoginClientFacts facts = LoginClientFacts.capture(ServletUtils.getRequest());
        String ip = facts.ip();
        UserOnlineDTO dto = new UserOnlineDTO();
        dto.setIpaddr(ip);
        dto.setLoginLocation(AddressUtils.getRealAddressByIP(ip));
        dto.setBrowser(facts.browser());
        dto.setOs(facts.os());
        dto.setLoginTime(System.currentTimeMillis());
        dto.setTokenId(tokenValue);
        String username = (String) loginParameter.getExtra(LoginHelper.USER_NAME_KEY);
        String tenantId = (String) loginParameter.getExtra(LoginHelper.TENANT_KEY);
        dto.setUserName(username);
        dto.setClientKey((String) loginParameter.getExtra(LoginHelper.CLIENT_KEY));
        dto.setDeviceType(loginParameter.getDeviceType());
        dto.setDeptName((String) loginParameter.getExtra(LoginHelper.DEPT_NAME_KEY));
        TenantHelper.dynamic(tenantId, () -> {
            if(loginParameter.getTimeout() == -1) {
                RedisUtils.setCacheObject(CacheConstants.ONLINE_TOKEN_KEY + tokenValue, dto);
            } else {
                RedisUtils.setCacheObject(CacheConstants.ONLINE_TOKEN_KEY + tokenValue, dto, Duration.ofSeconds(loginParameter.getTimeout()));
            }
        });
        // 记录登录日志（事实已在入队前捕获）
        LogininforEvent logininforEvent = new LogininforEvent();
        logininforEvent.setTenantId(tenantId);
        // R-2-R4：doLogin 在认证成功之后才触发，该租户已由认证策略核验 ⇒ 可信归属。
        logininforEvent.setTenantVerified(true);
        logininforEvent.setUsername(username);
        logininforEvent.setStatus(Constants.LOGIN_SUCCESS);
        logininforEvent.setMessage(MessageUtils.message("user.login.success"));
        logininforEvent.setClientFacts(facts);
        SpringUtils.context().publishEvent(logininforEvent);
        // 更新登录信息
        loginService.recordLoginInfo((Long) loginParameter.getExtra(LoginHelper.USER_KEY), ip);
        log.info("{}", authenticationEventSummary("login", loginId));
    }

    /**
     * 每次注销时触发
     */
    @Override
    public void doLogout(String loginType, Object loginId, String tokenValue) {
        String tenantId = Convert.toStr(StpUtil.getExtra(tokenValue, LoginHelper.TENANT_KEY));
        TenantHelper.dynamic(tenantId, () -> {
            RedisUtils.deleteObject(CacheConstants.ONLINE_TOKEN_KEY + tokenValue);
        });
        log.info("{}", authenticationEventSummary("logout", loginId));
    }

    /**
     * 每次被踢下线时触发
     */
    @Override
    public void doKickout(String loginType, Object loginId, String tokenValue) {
        String tenantId = Convert.toStr(StpUtil.getExtra(tokenValue, LoginHelper.TENANT_KEY));
        TenantHelper.dynamic(tenantId, () -> {
            RedisUtils.deleteObject(CacheConstants.ONLINE_TOKEN_KEY + tokenValue);
        });
        log.info("{}", authenticationEventSummary("kickout", loginId));
    }

    /**
     * 每次被顶下线时触发
     */
    @Override
    public void doReplaced(String loginType, Object loginId, String tokenValue) {
        String tenantId = Convert.toStr(StpUtil.getExtra(tokenValue, LoginHelper.TENANT_KEY));
        TenantHelper.dynamic(tenantId, () -> {
            RedisUtils.deleteObject(CacheConstants.ONLINE_TOKEN_KEY + tokenValue);
        });
        log.info("{}", authenticationEventSummary("replaced", loginId));
    }

    /**
     * 每次被封禁时触发
     */
    @Override
    public void doDisable(String loginType, Object loginId, String service, int level, long disableTime) {
    }

    /**
     * 每次被解封时触发
     */
    @Override
    public void doUntieDisable(String loginType, Object loginId, String service) {
    }

    /**
     * 每次打开二级认证时触发
     */
    @Override
    public void doOpenSafe(String loginType, String tokenValue, String service, long safeTime) {
    }

    /**
     * 每次创建Session时触发
     */
    @Override
    public void doCloseSafe(String loginType, String tokenValue, String service) {
    }

    /**
     * 每次创建Session时触发
     */
    @Override
    public void doCreateSession(String id) {
    }

    /**
     * 每次注销Session时触发
     */
    @Override
    public void doLogoutSession(String id) {
    }

    /**
     * 每次Token续期时触发
     */
    @Override
    public void doRenewTimeout(String loginType, Object loginId, String tokenValue, long timeout) {
    }

    /** The logging boundary cannot accept a token, making accidental formatting impossible. */
    static String authenticationEventSummary(String event, Object loginId) {
        return "authentication event=" + event + ", userId=" + loginId;
    }
}
