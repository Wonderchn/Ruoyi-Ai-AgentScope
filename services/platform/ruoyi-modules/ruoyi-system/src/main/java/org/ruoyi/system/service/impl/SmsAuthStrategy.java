package org.ruoyi.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.constant.GlobalConstants;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.domain.model.LoginUser;
import org.ruoyi.common.core.domain.model.SmsLoginBody;
import org.ruoyi.common.core.enums.LoginType;
import org.ruoyi.common.core.utils.MessageUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.core.utils.ValidatorUtils;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.common.redis.utils.RedisUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.helper.TenantHelper;
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
 * 短信认证策略
 *
 * @author Michelle.Chung
 */
@Slf4j
@Service("sms" + IAuthStrategy.BASE_NAME)
@RequiredArgsConstructor
public class SmsAuthStrategy implements IAuthStrategy {

    private final SysLoginService loginService;
    private final SysUserMapper userMapper;

    @Override
    public LoginVo login(String body, SysClientVo client) {
        SmsLoginBody loginBody = JsonUtils.parseObject(body, SmsLoginBody.class);
        ValidatorUtils.validate(loginBody);
        String tenantId = loginBody.getTenantId();
        String phonenumber = loginBody.getPhonenumber();
        String smsCode = loginBody.getSmsCode();
        LoginUser loginUser = TenantHelper.dynamic(tenantId, () -> {
            SysUserVo user = loadUserByPhonenumberOrNull(phonenumber);
            if (ObjectUtil.isNull(user)) {
                // G-53（RW-14）：账号不存在与验证码错误不可区分 —— 同一拒绝码/文案，同样计入重试次数
                loginService.checkLogin(LoginType.SMS, tenantId, phonenumber, () -> true);
                // checkLogin 契约上必然抛出；兜底绝不返回登录成功
                throw AuthDenial.denied();
            }
            // 重试计数以登录输入（手机号）为键，避免"账号是否存在"影响限流语义
            loginService.checkLogin(LoginType.SMS, tenantId, phonenumber, () -> !validateSmsCode(tenantId, phonenumber, smsCode));
            if (SystemConstants.DISABLE.equals(user.getStatus())) {
                // 账号状态在验证码校验之后检查，避免未验证凭据即可区分账号是否存在/是否停用
                log.info("登录用户：{} 已被停用.", phonenumber);
                loginService.recordLogininfor(tenantId, phonenumber, Constants.LOGIN_FAIL, MessageUtils.message("user.blocked", phonenumber));
                throw AuthDenial.denied();
            }
            // 此处可根据登录用户的数据不同 自行创建 loginUser 属性不够用继承扩展就行了
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
     * 校验短信验证码
     *
     * <p>验证码失效/缺失同样走稳定拒绝码（审计保留真实原因）。</p>
     */
    private boolean validateSmsCode(String tenantId, String phonenumber, String smsCode) {
        String code = RedisUtils.getCacheObject(GlobalConstants.CAPTCHA_CODE_KEY + phonenumber);
        if (StringUtils.isBlank(code)) {
            loginService.recordLogininfor(tenantId, phonenumber, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"));
            throw AuthDenial.denied();
        }
        return code.equals(smsCode);
    }

    /**
     * 按手机号查询账号；<b>不存在不在此处抛出</b>，由调用方走与验证码错误一致的拒绝路径。
     */
    private SysUserVo loadUserByPhonenumberOrNull(String phonenumber) {
        SysUserVo user = userMapper.selectVoOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhonenumber, phonenumber));
        if (ObjectUtil.isNull(user)) {
            log.info("登录用户：{} 不存在.", phonenumber);
        }
        return user;
    }

}
