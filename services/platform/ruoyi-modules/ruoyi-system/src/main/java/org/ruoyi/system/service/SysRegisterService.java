package org.ruoyi.system.service;

import cn.hutool.core.convert.Convert;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.constant.GlobalConstants;
import org.ruoyi.common.core.domain.model.RegisterBody;
import org.ruoyi.common.core.enums.UserType;
import org.ruoyi.common.core.exception.user.CaptchaException;
import org.ruoyi.common.core.exception.user.CaptchaExpireException;
import org.ruoyi.common.core.exception.user.UserException;
import org.ruoyi.common.core.utils.MessageUtils;
import org.ruoyi.common.core.utils.ServletUtils;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.log.event.LoginClientFacts;
import org.ruoyi.common.log.event.LogininforEvent;
import org.ruoyi.common.redis.utils.RedisUtils;
import org.ruoyi.common.tenant.audit.PlatformAuditAttribution;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.web.config.properties.CaptchaProperties;
import org.ruoyi.system.domain.SysUser;
import org.ruoyi.system.domain.bo.SysUserBo;
import org.ruoyi.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

/**
 * 注册校验方法
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Service
public class SysRegisterService {

    private final ISysUserService userService;
    private final SysUserMapper userMapper;
    private final CaptchaProperties captchaProperties;
    private final ISysConfigService configService;

    /**
     * 注册默认角色配置 key，值为角色 ID；未配置或为空则不绑定角色。
     */
    private static final String DEFAULT_ROLE_CONFIG_KEY = "sys.register.defaultRoleId";

    /**
     * 注册
     */
    public void register(RegisterBody registerBody) {
        String tenantId = registerBody.getTenantId();
        String username = registerBody.getUsername();
        String password = registerBody.getPassword();
        // 校验用户类型是否存在
        String userType = UserType.getUserType(registerBody.getUserType()).getUserType();

        boolean captchaEnabled = captchaProperties.getEnable();
        // 验证码开关
        if (captchaEnabled) {
            validateCaptcha(tenantId, username, registerBody.getCode(), registerBody.getUuid());
        }
        SysUserBo sysUser = new SysUserBo();
        sysUser.setUserName(username);
        sysUser.setNickName(username);
        sysUser.setPassword(BCrypt.hashpw(password));
        sysUser.setUserType(userType);

        boolean exist = TenantHelper.dynamic(tenantId, () -> {
            return userMapper.exists(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUserName, sysUser.getUserName()));
        });
        if (exist) {
            throw new UserException("user.register.save.error", username);
        }
        // R-2-R3：注册是"未登录"路径，主体租户只能来自请求体（tenantId），
        // 而 registerUser / bindDefaultRole 都要写带 tenant_id 的表（sys_user）或按租户读
        // （tenantIdsOfUsers → sys_user）。此前它们跑在 dynamic() 块<b>外</b>，缺上下文时会被
        // fail-closed 的租户行拦截器拒绝；这里把这段写入整体放进<b>同一个</b>显式租户作用域。
        // 注意：不在此处再嵌套 dynamic()——嵌套的 clearDynamic() 会提前清掉外层作用域。
        boolean regFlag = TenantHelper.dynamic(tenantId, () -> {
            boolean ok = userService.registerUser(sysUser, tenantId);
            if (ok) {
                // 绑定默认角色（未配置则跳过，不影响注册流程）
                bindDefaultRole(tenantId, sysUser.getUserId());
            }
            return ok;
        });
        if (!regFlag) {
            throw new UserException("user.register.error");
        }
        recordLogininfor(tenantId, username, Constants.REGISTER, MessageUtils.message("user.register.success"));
    }

    /**
     * 读取配置 sys.register.defaultRoleId 并为新用户绑定默认角色。
     * 配置为空或角色 ID 无效时静默跳过。
     *
     * <p><b>R-2-R3</b>：本方法<b>必须在调用方已建立的租户作用域内</b>执行——
     * 它按租户读配置、并通过 {@code insertUserAuth} 读写该租户的用户数据。
     * 这里<b>不再</b>自开 {@code TenantHelper.dynamic(...)}：嵌套的
     * {@code clearDynamic()} 会把外层的租户作用域一并清掉，导致同一方法里后续的
     * 数据库操作重新落入"无上下文"。</p>
     */
    private void bindDefaultRole(String tenantId, Long userId) {
        if (userId == null) {
            return;
        }
        Long defaultRoleId = Convert.toLong(configService.selectConfigByKey(DEFAULT_ROLE_CONFIG_KEY), null);
        if (defaultRoleId == null) {
            return;
        }
        userService.insertUserAuth(userId, new Long[]{defaultRoleId});
    }

    /**
     * 校验验证码
     *
     * @param username 用户名
     * @param code     验证码
     * @param uuid     唯一标识
     */
    public void validateCaptcha(String tenantId, String username, String code, String uuid) {
        String verifyKey = GlobalConstants.CAPTCHA_CODE_KEY + StringUtils.blankToDefault(uuid, "");
        String captcha = RedisUtils.getCacheObject(verifyKey);
        RedisUtils.deleteObject(verifyKey);
        if (captcha == null) {
            recordLogininfor(tenantId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"));
            throw new CaptchaExpireException();
        }
        if (!StringUtils.equalsIgnoreCase(code, captcha)) {
            recordLogininfor(tenantId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error"));
            throw new CaptchaException();
        }
    }

    /**
     * 记录登录信息
     *
     * <p>G-53（RW-14）：客户端事实在事件入队前捕获为不可变快照，异步监听器不再读取请求对象。</p>
     *
     * @param tenantId 租户ID
     * @param username 用户名
     * @param status   状态
     * @param message  消息内容
     */
    private void recordLogininfor(String tenantId, String username, String status, String message) {
        LogininforEvent logininforEvent = new LogininforEvent();
        logininforEvent.setTenantId(tenantId);
        logininforEvent.setUsername(username);
        logininforEvent.setStatus(status);
        logininforEvent.setMessage(message);
        // R-2-R4：注册成功（REGISTER）意味着该租户已被注册流程接受；验证码类失败发生在核验之前。
        logininforEvent.setTenantVerified(PlatformAuditAttribution.verifiedByStatus(status));
        logininforEvent.setClientFacts(LoginClientFacts.capture(ServletUtils.getRequest()));
        SpringUtils.context().publishEvent(logininforEvent);
    }

}
