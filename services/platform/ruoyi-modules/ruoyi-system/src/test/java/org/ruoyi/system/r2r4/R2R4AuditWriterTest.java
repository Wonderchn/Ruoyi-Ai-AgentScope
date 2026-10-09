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

package org.ruoyi.system.r2r4;

import io.github.linpeilie.Converter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.ruoyi.common.core.constant.TenantConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.domain.model.RegisterBody;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.log.event.LogininforEvent;
import org.ruoyi.common.log.event.OperLogEvent;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.tenant.audit.PlatformAuditAccess;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.system.domain.SysLogininfor;
import org.ruoyi.system.domain.SysOperLog;
import org.ruoyi.system.domain.bo.SysLogininforBo;
import org.ruoyi.system.domain.bo.SysUserBo;
import org.ruoyi.system.domain.bo.SysOperLogBo;
import org.ruoyi.system.mapper.SysLogininforMapper;
import org.ruoyi.system.mapper.SysUserMapper;
import org.ruoyi.system.mapper.SysOperLogMapper;
import org.ruoyi.system.service.ISysClientService;
import org.ruoyi.system.service.ISysConfigService;
import org.ruoyi.system.service.ISysUserService;
import org.ruoyi.system.service.SysRegisterService;
import org.ruoyi.system.service.impl.SysLogininforServiceImpl;
import org.ruoyi.system.service.impl.SysOperLogServiceImpl;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.ruoyi.common.web.config.properties.CaptchaProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-2-R4 验收（裁决 §7.1 执行范围第 1、2、4、5 条）：两个真实审计写入器的归属判定与读取闸门。
 *
 * <p><b>不需要数据库</b>：mapper 是 mock，本类断言的是"写进实体的 tenant_id 到底是什么值"
 * 与"访问闸门是否成立"。真实落库与行数核对在 {@code R2R4PlatformAuditPostgresTest}。
 *
 * <p>为什么必须断言<b>值</b>而不只是"mapper 被调用过"：第三轮复核用"去掉 DDL 默认值"的探针
 * 打穿过"只断言上下文为 null / 只断言调用发生"的写法——本类改为直接核对实体的
 * {@code tenant_id} 字段值，并与"绝不等于 DDL 默认值"的负断言配对。
 */
@Tag("dev")
class R2R4AuditWriterTest {

    private static final String RESERVED = TenantConstants.PLATFORM_AUDIT_TENANT_ID;
    private static final String PROP = "r2r4-tenant-enable";

    private ConfigurableEnvironment environment;

    @BeforeEach
    void setUp() {
        org.ruoyi.system.rw14.Rw14TestContext.install();
        StaticApplicationContext context = (StaticApplicationContext) SpringUtils.context();
        if (context.getBeanFactory().getBeanNamesForType(Converter.class).length == 0) {
            context.getBeanFactory().registerSingleton("converter", new Converter());
        }
        environment = (ConfigurableEnvironment) context.getEnvironment();
        environment.getPropertySources().remove(PROP);
        environment.getPropertySources().addFirst(new MapPropertySource(PROP, Map.of("tenant.enable", "true")));
        TenantHelper.clearDynamic();
    }

    @AfterEach
    void tearDown() {
        TenantHelper.clearDynamic();
        if (environment != null) {
            environment.getPropertySources().remove(PROP);
        }
    }

    // ------------------------------------------------------------------ 操作日志写入器

    @Test
    @DisplayName("操作日志：会话租户可信 ⇒ 行归属为该真实租户，且在真实租户作用域内写入")
    void operLogWithATrustedTenantIsAttributedToThatTenant() {
        SysOperLogMapper mapper = mock(SysOperLogMapper.class);
        List<SysOperLog> inserted = new ArrayList<>();
        List<String> ambient = new ArrayList<>();
        when(mapper.insert(any(SysOperLog.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            ambient.add(TenantHelper.getTenantId());
            return 1;
        });

        OperLogEvent event = new OperLogEvent();
        event.setTenantId("154726");
        event.setOperIp("not-an-ip");
        new SysOperLogServiceImpl(mapper).recordOper(event);

        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).getTenantId()).isEqualTo("154726");
        assertThat(ambient).as("已确认归属的审计写入仍在真实租户作用域内").containsExactly("154726");
    }

    @Test
    @DisplayName("操作日志：没有可信租户 ⇒ 行归属为保留值，且绝不等于 DDL 默认值")
    void operLogWithoutATrustedTenantIsAttributedToTheReservedMarker() {
        SysOperLogMapper mapper = mock(SysOperLogMapper.class);
        List<SysOperLog> inserted = new ArrayList<>();
        when(mapper.insert(any(SysOperLog.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });

        OperLogEvent event = new OperLogEvent();
        event.setTenantId(null);
        event.setOperIp("not-an-ip");
        new SysOperLogServiceImpl(mapper).recordOper(event);

        assertThat(inserted).as("无归属审计不得丢行").hasSize(1);
        assertThat(inserted.get(0).getTenantId())
                .as("无归属行必须显式写保留值")
                .isEqualTo(RESERVED);
        assertThat(inserted.get(0).getTenantId())
                .as("不得落到真实默认租户 000000")
                .isNotEqualTo(TenantConstants.DEFAULT_TENANT_ID);
    }

    @Test
    @DisplayName("操作日志：写入器拒绝归属为空的行（防退化成依赖 DDL 默认值）")
    void operLogWriterRefusesARowWithoutAttribution() {
        SysOperLogMapper mapper = mock(SysOperLogMapper.class);
        SysOperLogBo bo = new SysOperLogBo();
        // 直接走 insertOperlog 且刻意把声明值设为空白：归属判定会给出保留值，
        // 因此这里验证的是"服务层绝不把空白归属交给 INSERT"这条不变量。
        bo.setTenantId("   ");

        new SysOperLogServiceImpl(mapper).insertOperlog(bo);

        verify(mapper).insert(any(SysOperLog.class));
        // 空白声明值 ⇒ 保留值（不是空白、也不是 000000）
        org.mockito.ArgumentCaptor<SysOperLog> captor =
                org.mockito.ArgumentCaptor.forClass(SysOperLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(RESERVED);
    }

    // ------------------------------------------------------------------ 登录审计写入器

    @Test
    @DisplayName("登录审计：服务端核验过的租户 ⇒ 行归属为该租户")
    void logininforWithAVerifiedTenantIsAttributedToThatTenant() {
        SysLogininforMapper mapper = mock(SysLogininforMapper.class);
        List<SysLogininfor> inserted = new ArrayList<>();
        List<String> ambient = new ArrayList<>();
        when(mapper.insert(any(SysLogininfor.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            ambient.add(TenantHelper.getTenantId());
            return 1;
        });

        LogininforEvent event = new LogininforEvent();
        event.setTenantId("154726");
        event.setTenantVerified(true);
        event.setUsername("u");
        event.setStatus("0");
        new SysLogininforServiceImpl(mapper, mock(ISysClientService.class)).recordLogininfor(event);

        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).getTenantId()).isEqualTo("154726");
        assertThat(ambient).containsExactly("154726");
    }

    @Test
    @DisplayName("登录审计：请求自行声明但未核验的租户 ⇒ 行归属为保留值（不得冒充该租户）")
    void logininforWithAnUnverifiedDeclaredTenantIsAttributedToTheReservedMarker() {
        SysLogininforMapper mapper = mock(SysLogininforMapper.class);
        List<SysLogininfor> inserted = new ArrayList<>();
        when(mapper.insert(any(SysLogininfor.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });

        LogininforEvent event = new LogininforEvent();
        event.setTenantId("154726");
        event.setTenantVerified(false);   // 请求自行声明，服务端没核验过
        event.setUsername("u");
        event.setStatus("1");
        new SysLogininforServiceImpl(mapper, mock(ISysClientService.class)).recordLogininfor(event);

        assertThat(inserted).as("登录审计不得丢行").hasSize(1);
        assertThat(inserted.get(0).getTenantId())
                .as("未核验的声明租户不得成为可信归属")
                .isEqualTo(RESERVED);
        assertThat(inserted.get(0).getTenantId()).isNotEqualTo("154726");
    }

    // ------------------------------------------------------------------ 合法注册 / 嵌套作用域（裁决第 3 条正控制）

    @Test
    @DisplayName("正控制：合法注册的每一个写都在请求租户作用域内完成，且不享有审计例外")
    void registerRunsEveryWriteInsideTheRequestTenantScope() {
        List<String> registerUserTenant = new ArrayList<>();
        List<String> insertUserAuthTenant = new ArrayList<>();

        ISysUserService userService = mock(ISysUserService.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        CaptchaProperties captchaProperties = mock(CaptchaProperties.class);
        ISysConfigService configService = mock(ISysConfigService.class);

        when(captchaProperties.getEnable()).thenReturn(false);
        when(userMapper.exists(any())).thenReturn(false);
        when(userService.registerUser(any(SysUserBo.class), anyString())).thenAnswer(invocation -> {
            registerUserTenant.add(TenantHelper.getTenantId());
            ((SysUserBo) invocation.getArgument(0)).setUserId(5L);
            return true;
        });
        // 默认角色配置有值 ⇒ 会走到 insertUserAuth（这正是嵌套 dynamic 陷阱所在的位置）
        when(configService.selectConfigByKey(anyString())).thenReturn("7");
        Mockito.doAnswer(invocation -> {
            insertUserAuthTenant.add(TenantHelper.getTenantId());
            return null;
        }).when(userService).insertUserAuth(anyLong(), any());

        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));

        RegisterBody body = new RegisterBody();
        body.setTenantId("154726");
        body.setUsername("newuser");
        body.setPassword("pw");
        body.setUserType("sys_user");

        new SysRegisterService(userService, userMapper, captchaProperties, configService).register(body);

        assertThat(registerUserTenant)
                .as("registerUser 的 sys_user 写入必须在请求租户作用域内")
                .containsExactly("154726");
        assertThat(insertUserAuthTenant)
                .as("insertUserAuth 必须仍在同一租户作用域内——内层 dynamic() 的 clearDynamic() 会提前"
                        + "清掉外层作用域，这正是本判据要钉住的嵌套陷阱")
                .containsExactly("154726");
        assertThat(registerUserTenant)
                .as("注册不享有审计例外：不得出现平台无归属标记，也不得依赖 DDL 默认租户")
                .doesNotContain(RESERVED)
                .doesNotContain(TenantConstants.DEFAULT_TENANT_ID);
        assertThat(insertUserAuthTenant)
                .doesNotContain(RESERVED)
                .doesNotContain(TenantConstants.DEFAULT_TENANT_ID);
    }

    @Test
    @DisplayName("正控制：注册路径逐次进入/退出租户作用域，结束后不留上下文")
    void registerLeavesNoTenantContextBehind() {
        ISysUserService userService = mock(ISysUserService.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        CaptchaProperties captchaProperties = mock(CaptchaProperties.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        when(captchaProperties.getEnable()).thenReturn(false);
        when(userMapper.exists(any())).thenReturn(false);
        when(userService.registerUser(any(SysUserBo.class), anyString())).thenAnswer(invocation -> {
            ((SysUserBo) invocation.getArgument(0)).setUserId(9L);
            return true;
        });
        when(configService.selectConfigByKey(anyString())).thenReturn(null);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));

        RegisterBody body = new RegisterBody();
        body.setTenantId("154726");
        body.setUsername("u");
        body.setPassword("pw");
        body.setUserType("sys_user");
        new SysRegisterService(userService, userMapper, captchaProperties, configService).register(body);

        assertThat(TenantHelper.getTenantId())
                .as("注册结束后不得残留租户作用域（异常后的恢复同理）")
                .isNull();
    }

    // ------------------------------------------------------------------ 读取闸门（裁决第 4 条）

    @Test
    @DisplayName("平台审计读：非超管拿不到凭据（含真实 000000 租户管理员）")
    void platformAuditAccessIsRefusedForNonSuperAdmins() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(false);
            login.when(LoginHelper::getUserId).thenReturn(2099010100000000030L);
            assertThatThrownBy(PlatformAuditAccess::requirePlatformSuperAdmin)
                    .isInstanceOf(ServiceException.class);
        }
    }

    @Test
    @DisplayName("平台审计读：超管可取凭据；服务方法要求凭据，缺失即拒绝")
    void platformAuditReadRequiresTheAccessToken() {
        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            login.when(LoginHelper::getUserId).thenReturn(1L);
            PlatformAuditAccess access = PlatformAuditAccess.requirePlatformSuperAdmin();
            assertThat(access).isNotNull();
        }

        SysOperLogMapper mapper = mock(SysOperLogMapper.class);
        SysOperLogServiceImpl service = new SysOperLogServiceImpl(mapper);
        assertThatThrownBy(() -> service.selectPlatformAudit(null))
                .as("没有凭据就不许读平台审计域")
                .isInstanceOf(ServiceException.class);
        verify(mapper, never()).selectPlatformAudit();
    }

    @Test
    @DisplayName("平台审计读：只返回无归属行，且查询不带任何可伪造的租户参数")
    void platformAuditReadReturnsOnlyUnattributedRows() {
        SysOperLogMapper mapper = mock(SysOperLogMapper.class);
        SysOperLog row = new SysOperLog();
        row.setTenantId(RESERVED);
        when(mapper.selectPlatformAudit()).thenReturn(List.of(row));

        try (MockedStatic<LoginHelper> login = Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            login.when(LoginHelper::getUserId).thenReturn(1L);
            List<?> rows = new SysOperLogServiceImpl(mapper)
                    .selectPlatformAudit(PlatformAuditAccess.requirePlatformSuperAdmin());
            assertThat(rows).hasSize(1);
        }
    }
}
