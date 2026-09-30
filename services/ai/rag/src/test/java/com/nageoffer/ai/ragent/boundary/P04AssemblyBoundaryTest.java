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

package com.nageoffer.ai.ragent.boundary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.security.DelegationVerifier;
import com.nageoffer.ai.ragent.framework.security.P04AiExceptionHandler;
import com.nageoffer.ai.ragent.framework.security.P04SecurityConfig;
import com.nageoffer.ai.ragent.framework.security.PlatformAuthorizationClient;
import com.nageoffer.ai.ragent.rag.runtime.AclProvider;
import com.nageoffer.ai.ragent.rag.runtime.JdbcRunStore;
import com.nageoffer.ai.ragent.rag.runtime.RequestHasher;
import com.nageoffer.ai.ragent.rag.runtime.RunAcceptanceController;
import com.nageoffer.ai.ragent.rag.runtime.RunAcceptanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 回归护栏（阻断修复 Spec §2）：<b>默认关闭</b>时，实验包不产生任何受管 bean。
 *
 * <p>为什么这条必须有：真实启动类 {@code RagentApplication} 位于
 * {@code com.nageoffer.ai.ragent}，其默认组件扫描覆盖本轮新增的两个实验包。
 * 一旦某个组件漏加 {@code p04.enabled} 条件，它就会进入真实应用上下文——
 * 而 {@code DelegationVerifier} 构造期要求实验公钥、{@code RunAcceptanceService}
 * 需要主源集不存在的 {@code AclProvider}，真实应用会因此<b>启动失败</b>。
 *
 * <p>本测试对这两个包做组件扫描且<b>不提供任何 {@code p04.*} 属性</b>：
 * 若条件装配被遗漏或回退，这里会因缺属性/缺依赖而抛错，或被断言到残留 bean。
 *
 * <p>扫描显式排除 {@code @SpringBootApplication}：本模块的测试应用
 * （{@code P04AiTestApplication}）就位于被扫的子包里，它一旦被扫进来会连带激活
 * 自动装配（Redis/RocketMQ 等），那是测试自身的干扰，不是本护栏要证明的东西。
 *
 * <p>注意：{@code --spring.config.name=p04ai} 只改配置文件名，<b>不等于</b>激活
 * 名为 {@code p04ai} 的 profile，因此本护栏只认可 {@code p04.enabled} 这一显式开关。
 *
 * <p><b>本类刻意放在 {@code com.nageoffer.ai.ragent.boundary}，而不是实验包内</b>：
 * 测试应用 {@code P04AiTestApplication} 扫描的是 {@code ...rag.runtime} 整个父包，
 * 因此放在其下的任何测试类（连同这里的 {@code @Configuration} 内部类）都会被测试应用
 * 当作生产组件扫进去。实测踩过：{@code PositiveControlSupport} 提供的占位
 * {@code JdbcTemplate} 覆盖了真实数据源，导致集成运行里全部受理请求变成 500。
 */
@Tag("dev")
class P04AssemblyBoundaryTest {

    /**
     * 模拟真实应用的默认组件扫描：只扫本轮新增的两个实验包。
     *
     * <p>两类排除都是为了让扫描等价于"生产 jar 里的类"：
     * <ul>
     *   <li>{@code @SpringBootApplication}：嵌套的测试启动类会连带激活自动装配（Redis 等）；</li>
     *   <li>{@code ...rag.runtime.p04.*}：本模块的测试支撑类（测试控制面、健康探针等）
     *       只存在于测试源集、不进生产 jar，因此不参与"真实应用会不会装配实验组件"的判断。</li>
     * </ul>
     */
    @Configuration
    @ComponentScan(
            basePackages = {
                    "com.nageoffer.ai.ragent.framework.security",
                    "com.nageoffer.ai.ragent.rag.runtime"
            },
            excludeFilters = {
                    @ComponentScan.Filter(
                            type = FilterType.ANNOTATION, classes = SpringBootApplication.class),
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern = "com\\.nageoffer\\.ai\\.ragent\\.rag\\.runtime\\.p04\\..*")
            })
    static class ExperimentPackageScan {
    }

    private static AnnotationConfigApplicationContext scanWithoutAnyP04Properties() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(ExperimentPackageScan.class);
        return context;
    }

    @Test
    @DisplayName("默认关闭：扫描实验包不产生任何实验 bean，也不要求任何 p04.* 属性")
    void disabledByDefaultAssemblesNothing() {
        try (AnnotationConfigApplicationContext context = scanWithoutAnyP04Properties()) {
            // 缺 publicKeyPath / AclProvider / DataSource 时仍能刷新，说明没有组件被无条件装配
            assertDoesNotThrow(context::refresh,
                    "默认关闭下实验包不得要求任何 p04.* 属性或外部依赖");

            assertNoBean(context, DelegationVerifier.class);
            assertNoBean(context, PlatformAuthorizationClient.class);
            assertNoBean(context, P04SecurityConfig.class);
            assertNoBean(context, P04AiExceptionHandler.class);
            assertNoBean(context, RequestHasher.class);
            assertNoBean(context, RunAcceptanceService.class);
            assertNoBean(context, JdbcRunStore.class);
            assertNoBean(context, RunAcceptanceController.class);

            // 请求头过滤器由 P04SecurityConfig 注册；配置类不装配时它也必须缺席
            assertTrue(context.getBeanNamesForType(FilterRegistrationBean.class).length == 0,
                    "默认关闭下不得注册任何实验用 FilterRegistrationBean");
        }
    }

    @Test
    @DisplayName("默认关闭：实验端点映射类不存在（路由不可能被暴露）")
    void disabledByDefaultExposesNoRoutes() {
        try (AnnotationConfigApplicationContext context = scanWithoutAnyP04Properties()) {
            context.refresh();

            // 受理端点是唯一对外路由的载体；它不在上下文里，路由自然不存在
            assertNoBean(context, RunAcceptanceController.class);
            assertTrue(context.getBeanNamesForAnnotation(RestController.class).length == 0,
                    "默认关闭下不得存在任何 @RestController");
            assertTrue(context.getBeanNamesForAnnotation(RestControllerAdvice.class).length == 0,
                    "默认关闭下不得存在任何 @RestControllerAdvice");
        }
    }

    @Test
    @DisplayName("显式 p04.enabled=false：与缺席同样不装配（两条关闭路径都成立）")
    void explicitFalseAlsoAssemblesNothing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("p04-off", Map.of("p04.enabled", "false")));
            context.register(ExperimentPackageScan.class);
            assertDoesNotThrow(context::refresh, "显式关闭下不得要求任何 p04.* 依赖");

            assertNoBean(context, DelegationVerifier.class);
            assertNoBean(context, RequestHasher.class);
            assertNoBean(context, RunAcceptanceService.class);
            assertNoBean(context, JdbcRunStore.class);
            assertNoBean(context, RunAcceptanceController.class);
            assertTrue(context.getBeanNamesForType(FilterRegistrationBean.class).length == 0,
                    "显式关闭下不得注册实验用 FilterRegistrationBean");
        }
    }

    @Test
    @DisplayName("正向对照：显式开启时同一扫描必须装配出实验 bean（证明负向断言不是空转）")
    void enabledAssemblesFullChain() throws Exception {
        Path pem = writePublicKeyPem();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("p04-test", Map.of(
                    "p04.enabled", "true",
                    "p04.delegation.public-key-path", pem.toString(),
                    "p04.delegation.kid", "p04-platform-k1",
                    "p04.delegation.issuer", "platform",
                    "p04.delegation.audience", "ai",
                    "p04.delegation.clock-skew-seconds", "30",
                    "p04.platform.authorization-url", "http://127.0.0.1:1/internal/platform/v1/authorization/check",
                    "p04.platform.service-credential", "test-credential")));
            context.register(ExperimentPackageScan.class, PositiveControlSupport.class);
            context.refresh();

            // 没有这几条，上面的"默认关闭"断言就可能只是因为什么都没扫到而恒真
            assertHasBean(context, DelegationVerifier.class);
            assertHasBean(context, PlatformAuthorizationClient.class);
            assertHasBean(context, P04SecurityConfig.class);
            assertHasBean(context, RequestHasher.class);
            assertHasBean(context, RunAcceptanceService.class);
            assertHasBean(context, JdbcRunStore.class);
            assertHasBean(context, RunAcceptanceController.class);
            assertTrue(context.getBeanNamesForType(FilterRegistrationBean.class).length > 0,
                    "开启时 P04SecurityConfig 必须注册请求头过滤器");
        }
    }

    /** 正向对照所需的、主源集刻意不提供的最小协作 bean。 */
    @Configuration
    static class PositiveControlSupport {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            // JdbcTemplate 是 InitializingBean，必须带 DataSource 才能通过 afterPropertiesSet；
            // 这里只验证装配，不执行任何 SQL，故给一个不可用的占位 DataSource。
            JdbcTemplate template = new JdbcTemplate();
            template.setDataSource(new AbstractDataSource() {
                @Override
                public Connection getConnection() {
                    throw new UnsupportedOperationException("assembly-boundary probe: no database");
                }

                @Override
                public Connection getConnection(String username, String password) {
                    throw new UnsupportedOperationException("assembly-boundary probe: no database");
                }
            });
            return template;
        }

        @Bean
        AclProvider aclProvider() {
            return (tenantId, membershipId, action, resourceRef) -> false;
        }
    }

    private static void assertHasBean(AnnotationConfigApplicationContext context, Class<?> type) {
        String[] names = context.getBeanNamesForType(type, false, false);
        assertTrue(names.length > 0, () -> "开启时应当装配 " + type.getSimpleName() + "，但一个都没有");
    }

    private static Path writePublicKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPublic().getEncoded());
        Path dir = Files.createDirectories(Path.of("target", "p04-test"));
        Path pem = dir.resolve("assembly-boundary-public.pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n");
        return pem;
    }

    private static void assertNoBean(AnnotationConfigApplicationContext context, Class<?> type) {
        String[] names = context.getBeanNamesForType(type, false, false);
        assertTrue(names.length == 0,
                () -> "默认关闭下不应装配 " + type.getSimpleName() + "，实际 bean=" + String.join(",", names));
    }
}
