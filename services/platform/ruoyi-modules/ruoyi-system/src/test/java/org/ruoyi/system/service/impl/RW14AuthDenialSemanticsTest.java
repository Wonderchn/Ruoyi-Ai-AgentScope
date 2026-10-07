/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ruoyi.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.constant.HttpStatus;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.enums.LoginType;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.exception.user.UserException;
import org.ruoyi.common.log.event.LoginClientFacts;
import org.ruoyi.common.log.event.LogininforEvent;
import org.ruoyi.common.web.config.properties.CaptchaProperties;
import org.ruoyi.common.web.handler.GlobalExceptionHandler;
import org.ruoyi.system.domain.vo.SysClientVo;
import org.ruoyi.system.domain.vo.SysUserVo;
import org.ruoyi.system.mapper.SysUserMapper;
import org.ruoyi.system.rw14.Rw14TestContext;
import org.ruoyi.system.service.AuthDenial;
import org.ruoyi.system.service.SysLoginService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G-53（RW-14）认证语义：认证业务失败必须给出<b>稳定安全拒绝码</b>，
 * 且"账号不存在 / 口令错误 / 账号停用 / 重试锁定"在客户端完全不可区分。
 *
 * <p>旧行为：认证失败抛 {@code UserException}（{@code BaseException}），经
 * {@code GlobalExceptionHandler#handleBaseException} 变成 {@code code=500} 的通用文案 ——
 * 客户端无法区分"被拒"与"服务坏了"（本类用真实异常处理器把 401/500 差异钉住）。</p>
 */
@Tag("dev")
class RW14AuthDenialSemanticsTest {

    private static final String CORRECT_PASSWORD = "correct-password";

    private static final String TENANT_A = "T-A";

    private static final String CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final SysUserMapper userMapper = mock(SysUserMapper.class);

    private final SysLoginService loginService = new SysLoginService(null, null, null, null, null, null, null);

    private final CaptchaProperties captchaProperties = new CaptchaProperties();

    private PasswordAuthStrategy strategy;

    @BeforeEach
    void setUp() {
        Rw14TestContext.reset();
        ReflectionTestUtils.setField(loginService, "maxRetryCount", 3);
        ReflectionTestUtils.setField(loginService, "lockTime", 10);
        captchaProperties.setEnable(false);
        strategy = new PasswordAuthStrategy(captchaProperties, loginService, userMapper);
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void unknownAccountAndWrongPasswordAreIndistinguishable() {
        ServiceException unknownAccount = assertThrows(ServiceException.class,
            () -> strategy.login(loginBody(TENANT_A, "nobody", "any-password"), client()));

        when(userMapper.selectVoOne(any())).thenReturn(user("alice", BCrypt.hashpw(CORRECT_PASSWORD), "0"));
        ServiceException wrongPassword = assertThrows(ServiceException.class,
            () -> strategy.login(loginBody(TENANT_A, "alice", "wrong-password"), client()));

        assertDenial(unknownAccount);
        assertDenial(wrongPassword);
        // 客户端可见面完全一致：同类型、同 code、同 msg（不含账号/原因信息）
        assertSame(unknownAccount.getClass(), wrongPassword.getClass());
        assertEquals(unknownAccount.getCode(), wrongPassword.getCode());
        assertEquals(unknownAccount.getMessage(), wrongPassword.getMessage());
        assertNoAccountLeak(unknownAccount.getMessage(), "alice");
        assertNoAccountLeak(wrongPassword.getMessage(), "alice");
    }

    @Test
    void lockedAccountUsesTheSameStableDenial() {
        Rw14TestContext.redisErrorCount(3);
        when(userMapper.selectVoOne(any())).thenReturn(null);

        ServiceException locked = assertThrows(ServiceException.class,
            () -> strategy.login(loginBody(TENANT_A, "alice", "whatever-password"), client()));

        assertDenial(locked);
        // 审计仍保留真实原因（重试超限），只是客户端看不到
        assertEquals("user.password.retry.limit.exceed", lastAuditMessage());
    }

    @Test
    void disabledAccountWithCorrectPasswordIsDeniedNotSuccessful() {
        when(userMapper.selectVoOne(any())).thenReturn(user("alice", BCrypt.hashpw(CORRECT_PASSWORD), "1"));

        ServiceException blocked = assertThrows(ServiceException.class,
            () -> strategy.login(loginBody(TENANT_A, "alice", CORRECT_PASSWORD), client()));

        assertDenial(blocked);
        assertEquals("user.blocked", lastAuditMessage());
    }

    @Test
    void denialNeverReturnsSuccessOrEmptyResult() {
        when(userMapper.selectVoOne(any())).thenReturn(null);

        ServiceException denied = assertThrows(ServiceException.class,
            () -> strategy.login(loginBody(TENANT_A, "nobody", "any-password"), client()));

        assertDenial(denied);
        // 确实走完了凭据校验流程（非空洞断言）；且异常类型是稳定拒绝而非 SaToken/其它异常 ——
        // 说明没有走到 LoginHelper.login（即没有生成任何凭据/token）
        verify(userMapper, times(1)).selectVoOne(any());
        assertSame(ServiceException.class, denied.getClass());
    }

    @Test
    void denialIsStableAcrossBothTenants() {
        for (String tenant : List.of("T-A", "T-B")) {
            when(userMapper.selectVoOne(any())).thenReturn(null);
            ServiceException denied = assertThrows(ServiceException.class,
                () -> strategy.login(loginBody(tenant, "somebody", "some-password"), client()));
            assertDenial(denied);
        }
        List<LogininforEvent> events = Rw14TestContext.publishedLoginEvents();
        assertEquals(2, events.size(), () -> "每个租户各一条失败审计: " + events.size());
        assertEquals("T-A", events.get(0).getTenantId());
        assertEquals("T-B", events.get(1).getTenantId());
        assertEquals(Constants.LOGIN_FAIL, events.get(0).getStatus());
        // 失败审计同样带客户端事实快照（事件入队前捕获）
        assertNotNull(events.get(0).getClientFacts());
    }

    @Test
    void unknownAccountPathStillPerformsEquivalentBcryptWork() {
        String dummy = PasswordAuthStrategy.DUMMY_PASSWORD_HASH;
        assertTrue(dummy.startsWith("$2a$10$"), "假哈希必须是 cost=10 的 BCrypt 值");
        assertEquals(60, dummy.length());
        assertFalse(BCrypt.checkpw(CORRECT_PASSWORD, dummy));
        assertFalse(BCrypt.checkpw("", dummy));

        when(userMapper.selectVoOne(any())).thenReturn(null);
        long startedAt = System.nanoTime();
        assertThrows(ServiceException.class,
            () -> strategy.login(loginBody(TENANT_A, "nobody", "any-password"), client()));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        // 主证据是上面的哈希有效性断言；这里只做"耗时不小于一次 BCrypt"的旁证
        assertTrue(elapsedMillis >= 10, () -> "账号不存在路径应执行等价 BCrypt 校验, elapsedMs=" + elapsedMillis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownAccountAndWrongPasswordIncrementTheSameRetryCounter() {
        when(userMapper.selectVoOne(any())).thenReturn(null);
        assertThrows(ServiceException.class, () -> strategy.login(loginBody(TENANT_A, "nobody", "any-password"), client()));
        assertEquals(1, capturedRetryCount(), "账号不存在也必须计入重试次数");

        clearInvocations(Rw14TestContext.redisBucket());
        when(userMapper.selectVoOne(any())).thenReturn(user("alice", BCrypt.hashpw(CORRECT_PASSWORD), "0"));
        assertThrows(ServiceException.class, () -> strategy.login(loginBody(TENANT_A, "alice", "wrong-password"), client()));
        assertEquals(1, capturedRetryCount(), "口令错误计入重试次数，与账号不存在一致");
    }

    @Test
    void successfulCredentialCheckIsNotAffected() {
        Rw14TestContext.redisErrorCount(null);
        clearInvocations(Rw14TestContext.redisBucket());

        loginService.checkLogin(LoginType.PASSWORD, TENANT_A, "alice", () -> false);

        // 校验通过：不抛异常、不产生失败审计、不写重试计数，并清理错误计数（原逻辑保留）
        assertTrue(Rw14TestContext.publishedLoginEvents().isEmpty());
        verify(Rw14TestContext.redisBucket(), never()).set(any(), any(Duration.class));
        verify(Rw14TestContext.redisBucket(), times(1)).delete();
    }

    @Test
    void handlerMapsDenialTo401WhileLegacyBaseExceptionStays500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");

        R<Void> denied = handler.handleServiceException(AuthDenial.denied(), request);
        assertEquals(HttpStatus.UNAUTHORIZED, denied.getCode(), "认证拒绝必须是稳定的 401");
        assertEquals("请求处理失败", denied.getMsg(), "对外仍是安全文案");
        assertNull(denied.getData());

        R<Void> legacy = handler.handleBaseException(new UserException("user.not.exists", "alice"), request);
        assertEquals(R.FAIL, legacy.getCode(), "旧路径（BaseException 信封）无法把拒绝与 500 区分开");
    }

    @Test
    void denialContractConstantsAreStable() {
        assertEquals(401, AuthDenial.DENIED_CODE);
        assertEquals(HttpStatus.UNAUTHORIZED, AuthDenial.DENIED_CODE);
        assertEquals("认证失败", AuthDenial.DENIED_MESSAGE);
        assertTrue(AuthDenial.isDenial(AuthDenial.denied()));
        assertFalse(AuthDenial.isDenial(new UserException("user.blocked", "alice")));
        assertFalse(AuthDenial.isDenial(new IllegalStateException("boom")));
    }

    @Test
    void failedLoginAuditCarriesCapturedClientFacts() {
        when(userMapper.selectVoOne(any())).thenReturn(null);
        MockHttpServletRequest live = new MockHttpServletRequest("POST", "/auth/login");
        live.addHeader("User-Agent", CHROME_UA);
        live.setRemoteAddr("172.16.0.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(live));

        assertThrows(ServiceException.class, () -> strategy.login(loginBody(TENANT_A, "nobody", "any-password"), client()));

        LogininforEvent event = Rw14TestContext.publishedLoginEvents().get(0);
        LoginClientFacts facts = event.getClientFacts();
        assertNotNull(facts);
        assertEquals("172.16.0.9", facts.ip());
        assertEquals("Chrome", facts.browser());
        assertEquals(Constants.LOGIN_FAIL, event.getStatus());
    }

    @SuppressWarnings("unchecked")
    private int capturedRetryCount() {
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(Rw14TestContext.redisBucket(), times(1)).set(value.capture(), any(Duration.class));
        return (Integer) value.getValue();
    }

    private void assertDenial(ServiceException exception) {
        assertEquals(AuthDenial.DENIED_CODE, exception.getCode(), () -> "code=" + exception.getCode());
        assertEquals(AuthDenial.DENIED_MESSAGE, exception.getMessage());
        assertTrue(AuthDenial.isDenial(exception));
    }

    private void assertNoAccountLeak(String message, String username) {
        assertFalse(message.contains(username), () -> "拒绝文案不得包含账号: " + message);
        assertFalse(message.contains("不存在"), () -> "拒绝文案不得泄露账号存在性: " + message);
        assertFalse(message.contains("密码"), () -> "拒绝文案不得提示口令信息: " + message);
    }

    private String lastAuditMessage() {
        List<LogininforEvent> events = Rw14TestContext.publishedLoginEvents();
        assertFalse(events.isEmpty(), "失败必须写入登录审计事件");
        return events.get(events.size() - 1).getMessage();
    }

    private SysUserVo user(String username, String passwordHash, String status) {
        SysUserVo user = new SysUserVo();
        user.setUserName(username);
        user.setPassword(passwordHash);
        user.setStatus(status);
        user.setTenantId(TENANT_A);
        user.setUserId(1L);
        return user;
    }

    private SysClientVo client() {
        SysClientVo client = new SysClientVo();
        client.setClientId("client-1");
        client.setClientKey("pc");
        client.setDeviceType("web");
        client.setStatus("0");
        return client;
    }

    private String loginBody(String tenantId, String username, String password) {
        return "{\"clientId\":\"client-1\",\"grantType\":\"password\",\"tenantId\":\"" + tenantId
            + "\",\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
