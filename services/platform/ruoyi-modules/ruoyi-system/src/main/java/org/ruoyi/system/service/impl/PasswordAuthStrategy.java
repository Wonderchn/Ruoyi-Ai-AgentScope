package org.ruoyi.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.constant.GlobalConstants;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.domain.model.LoginUser;
import org.ruoyi.common.core.domain.model.PasswordLoginBody;
import org.ruoyi.common.core.enums.LoginType;
import org.ruoyi.common.core.utils.MessageUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.core.utils.ValidatorUtils;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.common.redis.utils.RedisUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.web.config.properties.CaptchaProperties;
import org.ruoyi.system.domain.SysUser;
import org.ruoyi.system.domain.vo.LoginVo;
import org.ruoyi.system.domain.vo.SysClientVo;
import org.ruoyi.system.domain.vo.SysUserVo;
import org.ruoyi.system.mapper.SysUserMapper;
import org.ruoyi.system.service.AuthDenial;
import org.ruoyi.system.service.IAuthStrategy;
import org.ruoyi.system.service.SysLoginService;
import org.springframework.stereotype.Service;

/**
 * 密码认证策略
 *
 * @author Michelle.Chung
 */
@Slf4j
@Service("password" + IAuthStrategy.BASE_NAME)
@RequiredArgsConstructor
public class PasswordAuthStrategy implements IAuthStrategy {

    /**
     * 固定的 BCrypt 假哈希（成本 10，明文是一次性随机值，未在任何地方保留）：
     * 账号不存在时也执行一次等价的口令校验，避免用响应耗时区分"账号不存在"与"口令错误"。
     */
    static final String DUMMY_PASSWORD_HASH = "$2a$10$5QbsYAz3uKmRfuP1ESpGbeSgN8iqEduD2llxC/I/5wlr0y4/MjfmO";

    private final CaptchaProperties captchaProperties;
    private final SysLoginService loginService;
    private final SysUserMapper userMapper;

    @Override
    public LoginVo login(String body, SysClientVo client) {
        PasswordLoginBody loginBody = JsonUtils.parseObject(body, PasswordLoginBody.class);
        ValidatorUtils.validate(loginBody);
        String tenantId = loginBody.getTenantId();
        String username = loginBody.getUsername();
        String password = loginBody.getPassword();
        String code = loginBody.getCode();
        String uuid = loginBody.getUuid();

        boolean captchaEnabled = captchaProperties.getEnable();
        // 验证码开关
        if (captchaEnabled) {
            validateCaptcha(tenantId, username, code, uuid);
        }
        LoginUser loginUser = TenantHelper.dynamic(tenantId, () -> {
            SysUserVo user = loadUserByUsernameOrNull(username);
            if (ObjectUtil.isNull(user)) {
                // G-53（RW-14）：账号不存在与口令错误必须不可区分 —— 同一拒绝码/文案，
                // 同样计入重试次数，并且仍然执行一次等价 BCrypt 校验（耗时不可区分）。
                loginService.checkLogin(LoginType.PASSWORD, tenantId, username, () -> {
                    BCrypt.checkpw(password, DUMMY_PASSWORD_HASH);
                    return true;
                });
                // checkLogin 契约上必然抛出（supplier 恒为失败）；此处仅作防御性兜底，绝不返回登录成功
                throw AuthDenial.denied();
            }
            loginService.checkLogin(LoginType.PASSWORD, tenantId, username, () -> !BCrypt.checkpw(password, user.getPassword()));
            if (SystemConstants.DISABLE.equals(user.getStatus())) {
                // 账号状态在凭据校验之后才检查：否则不提供口令也能区分"账号存在且被停用"。
                // 审计保留真实原因，客户端仍只拿到同一个稳定拒绝码。
                log.info("登录用户：{} 已被停用.", username);
                loginService.recordLogininfor(tenantId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.blocked", username));
                throw AuthDenial.denied();
            }
            // 此处可根据登录用户的数据不同 自行创建 loginUser
            return loginService.buildLoginUser(user);
        });
        loginUser.setClientKey(client.getClientKey());
        loginUser.setDeviceType(client.getDeviceType());
        SaLoginParameter model = new SaLoginParameter();
        model.setDeviceType(client.getDeviceType());
        // 自定义分配 不同用户体系 不同 token 授权时间 不设置默认走全局 yml 配置
        // 例如: 后台用户30分钟过期 app用户1天过期
        model.setTimeout(client.getTimeout());
        model.setActiveTimeout(client.getActiveTimeout());
        model.setExtra(LoginHelper.CLIENT_KEY, client.getClientId());
        // 生成token
        LoginHelper.login(loginUser, model);

        LoginVo loginVo = new LoginVo();
        loginVo.setAccessToken(StpUtil.getTokenValue());
        loginVo.setExpireIn(StpUtil.getTokenTimeout());
        loginVo.setClientId(client.getClientId());
        return loginVo;
    }

    /**
     * 校验验证码
     *
     * <p>验证码校验发生在任何账号查询<b>之前</b>，失败与账号是否存在无关；
     * 为保持"所有认证业务失败同一稳定拒绝码"，这里同样抛 {@link AuthDenial#denied()}，
     * 审计消息保留真实原因。</p>
     */
    private void validateCaptcha(String tenantId, String username, String code, String uuid) {
        String verifyKey = GlobalConstants.CAPTCHA_CODE_KEY + StringUtils.blankToDefault(uuid, "");
        String captcha = RedisUtils.getCacheObject(verifyKey);
        RedisUtils.deleteObject(verifyKey);
        if (captcha == null) {
            loginService.recordLogininfor(tenantId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"));
            throw AuthDenial.denied();
        }
        if (!StringUtils.equalsIgnoreCase(code, captcha)) {
            loginService.recordLogininfor(tenantId, username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error"));
            throw AuthDenial.denied();
        }
    }

    /**
     * 按用户名查询账号；<b>不存在不在此处抛出</b>，由调用方走与口令错误一致的拒绝路径。
     */
    private SysUserVo loadUserByUsernameOrNull(String username) {
        SysUserVo user = userMapper.selectVoOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUserName, username));
        if (ObjectUtil.isNull(user)) {
            log.info("登录用户：{} 不存在.", username);
        }
        return user;
    }

}
