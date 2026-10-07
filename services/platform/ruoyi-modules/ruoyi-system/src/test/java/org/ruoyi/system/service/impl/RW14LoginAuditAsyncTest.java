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

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.common.core.constant.Constants;
import org.ruoyi.common.core.utils.ServletUtils;
import org.ruoyi.common.log.event.LoginClientFacts;
import org.ruoyi.common.log.event.LogininforEvent;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.system.domain.bo.SysLogininforBo;
import org.ruoyi.system.domain.vo.SysClientVo;
import org.ruoyi.system.mapper.SysLogininforMapper;
import org.ruoyi.system.rw14.Rw14TestContext;
import org.ruoyi.system.service.ISysClientService;
import org.ruoyi.system.service.SysLoginService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G-53（RW-14）登录审计：异步侧不得再读取请求对象。
 *
 * <p>对照的旧行为（现已由编译期消除）：{@code SysLogininforServiceImpl.recordLogininfor}
 * 在 {@code @Async} 线程里 {@code event.getRequest().getHeader("User-Agent")} 并
 * {@code UserAgentUtil.parse(...).getOs().getName()} —— 请求结束后容器回收请求对象，
 * 轻则读到错值，重则（缺 UA 时 hutool 5.8.40 返回 null）NPE 让整条审计静默丢失。</p>
 */
@Tag("dev")
class RW14LoginAuditAsyncTest {

    private static final String CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final SysLogininforMapper baseMapper = mock(SysLogininforMapper.class);

    private final ISysClientService clientService = mock(ISysClientService.class);

    private SysLogininforServiceImpl service;

    @BeforeEach
    void setUp() {
        Rw14TestContext.reset();
        service = spy(new SysLogininforServiceImpl(baseMapper, clientService));
        doNothing().when(service).insertLogininfor(any(SysLogininforBo.class));
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void captureKeepsImmutableFactsAndParsesBrowserAndOs() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader("User-Agent", CHROME_UA);
        request.addHeader(LoginHelper.CLIENT_KEY, "client-1");
        request.setRemoteAddr("10.1.2.3");

        LoginClientFacts facts = LoginClientFacts.capture(request);

        assertEquals("10.1.2.3", facts.ip());
        assertEquals("client-1", facts.clientId());
        assertEquals("Chrome", facts.browser());
        assertTrue(facts.os().startsWith("Windows"), () -> "os=" + facts.os());
        assertEquals(CHROME_UA, facts.userAgent());
        // 脱敏只影响日志面，审计落库用完整 IP
        assertEquals("10.1.2.*", facts.maskedIp());
    }

    /**
     * G-53 核心：请求结束后（请求对象被回收）异步审计仍然写入，且事实与请求线程捕获的一致。
     * 用"任何访问都抛 request recycled"的请求对象证明异步侧一次都没有碰它。
     */
    @Test
    void auditStillWritesAfterRequestRecycledAndNeverTouchesRequest() {
        MockHttpServletRequest live = new MockHttpServletRequest("POST", "/auth/login");
        live.addHeader("User-Agent", CHROME_UA);
        live.addHeader(LoginHelper.CLIENT_KEY, "client-1");
        live.setRemoteAddr("10.1.2.3");
        LoginClientFacts captured = LoginClientFacts.capture(live);

        SysClientVo client = new SysClientVo();
        client.setClientKey("pc");
        client.setDeviceType("web");
        when(clientService.queryByClientId("client-1")).thenReturn(client);

        LogininforEvent event = new LogininforEvent();
        event.setTenantId("T1");
        event.setUsername("alice");
        event.setStatus(Constants.LOGIN_SUCCESS);
        event.setMessage("登录成功");
        event.setClientFacts(captured);

        // 请求已结束：容器回收该对象，任何读取都会炸
        RequestContextHolder.resetRequestAttributes();
        HttpServletRequest recycled = mock(HttpServletRequest.class);
        when(recycled.getHeader(anyString())).thenThrow(new IllegalStateException("request recycled"));
        when(recycled.getRemoteAddr()).thenThrow(new IllegalStateException("request recycled"));
        when(recycled.getRequestURI()).thenThrow(new IllegalStateException("request recycled"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(recycled));

        service.recordLogininfor(event);

        SysLogininforBo stored = capturedBo(1);
        assertEquals("T1", stored.getTenantId());
        assertEquals("10.1.2.3", stored.getIpaddr());
        assertEquals("Chrome", stored.getBrowser());
        assertTrue(stored.getOs().startsWith("Windows"), () -> "os=" + stored.getOs());
        assertEquals("pc", stored.getClientKey());
        assertEquals("web", stored.getDeviceType());
        assertEquals(Constants.SUCCESS, stored.getStatus());
        assertEquals("登录成功", stored.getMsg());
        assertEquals("client-1", captured.clientId());
        // 异步侧没有读取任何请求对象（真机里就是"request recycled"的复现面）
        verifyNoInteractions(recycled);
    }

    @Test
    void duplicateOrBracketedStatusesAreMappedLikeBefore() {
        when(clientService.queryByClientId(anyString())).thenReturn(null);

        service.recordLogininfor(event("T1", "u", Constants.LOGIN_SUCCESS, "ok"));
        service.recordLogininfor(event("T1", "u", Constants.LOGOUT, "bye"));
        service.recordLogininfor(event("T1", "u", Constants.REGISTER, "new"));
        service.recordLogininfor(event("T1", "u", Constants.LOGIN_FAIL, "bad"));

        List<SysLogininforBo> stored = capturedBos(4);
        assertEquals(Constants.SUCCESS, stored.get(0).getStatus());
        assertEquals(Constants.SUCCESS, stored.get(1).getStatus());
        assertEquals(Constants.SUCCESS, stored.get(2).getStatus());
        assertEquals(Constants.FAIL, stored.get(3).getStatus());
    }

    @Test
    void missingUserAgentDegradesToUnknownAndStillWrites() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setRemoteAddr("10.1.2.3");

        LoginClientFacts facts = LoginClientFacts.capture(request);

        assertEquals("10.1.2.3", facts.ip());
        assertEquals(LoginClientFacts.UNKNOWN, facts.browser());
        assertEquals(LoginClientFacts.UNKNOWN, facts.os());
        assertNull(facts.clientId());

        LogininforEvent event = new LogininforEvent();
        event.setTenantId("T2");
        event.setUsername("script");
        event.setStatus(Constants.LOGIN_FAIL);
        event.setMessage("认证失败");
        event.setClientFacts(facts);

        service.recordLogininfor(event);

        SysLogininforBo stored = capturedBo(1);
        assertEquals(LoginClientFacts.UNKNOWN, stored.getBrowser());
        assertEquals(LoginClientFacts.UNKNOWN, stored.getOs());
        assertEquals(Constants.FAIL, stored.getStatus());
        assertEquals("T2", stored.getTenantId());
    }

    @Test
    void blankUserAgentHeaderAlsoDegradesInsteadOfThrowing() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader("User-Agent", "   ");
        request.setRemoteAddr("10.9.9.9");

        LoginClientFacts facts = LoginClientFacts.capture(request);

        assertEquals(LoginClientFacts.UNKNOWN, facts.browser());
        assertEquals(LoginClientFacts.UNKNOWN, facts.os());
    }

    @Test
    void withoutRequestContextCaptureIsUnknownAndAuditStillWrites() {
        RequestContextHolder.resetRequestAttributes();
        LoginClientFacts facts = LoginClientFacts.capture(ServletUtils.getRequest());

        assertEquals(LoginClientFacts.UNKNOWN, facts.ip());
        assertEquals(LoginClientFacts.UNKNOWN, facts.browser());
        assertEquals(LoginClientFacts.UNKNOWN, facts.os());

        service.recordLogininfor(event("T1", "no-request", Constants.LOGIN_FAIL, "bad"));

        SysLogininforBo stored = capturedBo(1);
        assertEquals(LoginClientFacts.UNKNOWN, stored.getIpaddr());
        assertEquals(Constants.FAIL, stored.getStatus());
    }

    @Test
    void eventWithoutFactsDegradesSafelyInsteadOfDroppingTheRow() {
        LogininforEvent event = event("T1", "legacy-publisher", Constants.LOGIN_FAIL, "bad");
        event.setClientFacts(null);

        service.recordLogininfor(event);

        SysLogininforBo stored = capturedBo(1);
        assertEquals(LoginClientFacts.UNKNOWN, stored.getIpaddr());
        assertEquals(LoginClientFacts.UNKNOWN, stored.getBrowser());
        assertEquals("legacy-publisher", stored.getUserName());
    }

    @Test
    void twoTenantsKeepTheirOwnAuditScope() {
        service.recordLogininfor(event("T-A", "alice", Constants.LOGIN_SUCCESS, "ok"));
        service.recordLogininfor(event("T-B", "bob", Constants.LOGIN_FAIL, "bad"));

        List<SysLogininforBo> stored = capturedBos(2);
        assertEquals("T-A", stored.get(0).getTenantId());
        assertEquals("alice", stored.get(0).getUserName());
        assertEquals("T-B", stored.get(1).getTenantId());
        assertEquals("bob", stored.get(1).getUserName());
        assertEquals(Constants.SUCCESS, stored.get(0).getStatus());
        assertEquals(Constants.FAIL, stored.get(1).getStatus());
    }

    @Test
    void auditFailureIsContainedAndLoggedInsteadOfBreakingCaller() {
        doNothing().doThrow(new IllegalStateException("db down")).when(service)
            .insertLogininfor(any(SysLogininforBo.class));

        service.recordLogininfor(event("T1", "alice", Constants.LOGIN_SUCCESS, "ok"));
        // 第二次插入抛错：异步方法必须自行吞掉（审计是旁路，不能反过来打断登录）
        service.recordLogininfor(event("T1", "alice", Constants.LOGIN_SUCCESS, "ok"));

        verify(service, times(2)).insertLogininfor(any(SysLogininforBo.class));
    }

    /**
     * 端到端（模块内）：生产点在事件入队前捕获事实 → 请求结束 → 异步侧仍按捕获值写审计。
     */
    @Test
    void producerCapturesFactsBeforePublishAndAuditSurvivesRequestEnd() {
        SysClientVo client = new SysClientVo();
        client.setClientKey("pc");
        client.setDeviceType("web");
        when(clientService.queryByClientId("client-9")).thenReturn(client);

        MockHttpServletRequest live = new MockHttpServletRequest("POST", "/auth/login");
        live.addHeader("User-Agent", CHROME_UA);
        live.addHeader(LoginHelper.CLIENT_KEY, "client-9");
        live.setRemoteAddr("192.168.31.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(live));

        SysLoginService loginService = new SysLoginService(null, null, null, null, null, null, null);
        loginService.recordLogininfor("T-1", "carol", Constants.LOGIN_SUCCESS, "登录成功");

        LogininforEvent published = (LogininforEvent) Rw14TestContext.lastPublishedEvent();
        assertNotNull(published, "生产点应发布 LogininforEvent");
        LoginClientFacts facts = published.getClientFacts();
        assertNotNull(facts, "事实必须在入队前捕获");
        assertEquals("192.168.31.7", facts.ip());
        assertEquals("client-9", facts.clientId());
        assertEquals("Chrome", facts.browser());

        // 请求结束 + 请求对象被回收
        RequestContextHolder.resetRequestAttributes();
        HttpServletRequest recycled = mock(HttpServletRequest.class);
        when(recycled.getHeader(anyString())).thenThrow(new IllegalStateException("request recycled"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(recycled));

        service.recordLogininfor(published);

        SysLogininforBo stored = capturedBo(1);
        assertEquals("192.168.31.7", stored.getIpaddr());
        assertEquals("Chrome", stored.getBrowser());
        assertEquals("pc", stored.getClientKey());
        assertEquals("T-1", stored.getTenantId());
        verifyNoInteractions(recycled);
    }

    @Test
    void auditLogLineMasksIpAndStripsControlCharacters() {
        LoginClientFacts facts = new LoginClientFacts("10.2.3.4", "Chrome", "Windows", "c1", CHROME_UA);
        LogininforEvent event = event("T1", "alice\r\n[2026-10-07] forged line", Constants.LOGIN_FAIL,
            "bad\ninjected");

        String line = SysLogininforServiceImpl.auditLogLine(facts, "内网IP", event);

        assertTrue(line.startsWith("[10.2.3.*]内网IP[alice[2026-10-07] forged line][Error][badinjected]"),
            () -> "line=" + line);
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertFalse(line.contains("10.2.3.4"));
    }

    @Test
    void maskedIpKeepsForensicValueInDatabaseButNotInLogs() {
        LoginClientFacts ipv4 = new LoginClientFacts("8.8.8.8", "b", "o", null, null);
        LoginClientFacts ipv6 = new LoginClientFacts("2001:db8:1:2::7", "b", "o", null, null);
        LoginClientFacts unknown = LoginClientFacts.unknown();

        assertEquals("8.8.8.*", ipv4.maskedIp());
        assertEquals("2001:db8:1:2::*", ipv6.maskedIp());
        assertEquals(LoginClientFacts.UNKNOWN, unknown.maskedIp());
        assertEquals("8.8.8.8", ipv4.ip());
    }

    @Test
    void oversizedAndControlCharacterHeadersAreTruncatedAndSanitized() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader("User-Agent", "A".repeat(400) + "\r\nX-Injected: 1");
        request.addHeader(LoginHelper.CLIENT_KEY, "c\r\n1");
        request.setRemoteAddr("10.0.0.1");

        LoginClientFacts facts = LoginClientFacts.capture(request);

        assertEquals(LoginClientFacts.MAX_HEADER_LENGTH, facts.userAgent().length());
        assertFalse(facts.userAgent().contains("\r"));
        assertFalse(facts.userAgent().contains("\n"));
        assertEquals("c1", facts.clientId());
    }

    private LogininforEvent event(String tenantId, String username, String status, String message) {
        LogininforEvent event = new LogininforEvent();
        event.setTenantId(tenantId);
        event.setUsername(username);
        event.setStatus(status);
        event.setMessage(message);
        event.setClientFacts(LoginClientFacts.unknown());
        return event;
    }

    private SysLogininforBo capturedBo(int times) {
        return capturedBos(times).get(times - 1);
    }

    private List<SysLogininforBo> capturedBos(int times) {
        ArgumentCaptor<SysLogininforBo> captor = ArgumentCaptor.forClass(SysLogininforBo.class);
        verify(service, times(times)).insertLogininfor(captor.capture());
        return captor.getAllValues();
    }
}
