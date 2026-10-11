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

package com.nageoffer.ai.ragent.mcp.security;

import com.nageoffer.ai.ragent.framework.security.DelegatedPrincipalFilter;
import com.nageoffer.ai.ragent.mcp.config.McpServerConfig;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * 受保护模式装配判据（SPEC-9099 §7/J4）：开关关=现行为原样；开关开=受保护端点唯一、
 * 过滤器面（前缀/顺序/分派类型）就位；公钥/平台事实端口配置缺省即启动失败（fail-fast）。
 */
@Tag("dev")
class McpSecurityConfigurationTest {

    private static String publicKeyPath;

    @BeforeAll
    static void setUpClass() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pair = generator.generateKeyPair();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPublic().getEncoded());
        Path dir = Path.of("target", "mcp9099-ctx-test");
        Files.createDirectories(dir);
        Path pem = dir.resolve("platform-public.pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n",
                StandardCharsets.UTF_8);
        publicKeyPath = pem.toAbsolutePath().toString();
    }

    private ApplicationContextRunner securedRunner(Class<?> support) {
        return new ApplicationContextRunner()
                .withUserConfiguration(McpServerConfig.class, McpSecurityConfiguration.class, support)
                .withPropertyValues(
                        "ai.integration.security.enabled=true",
                        "ai.integration.security.public-key-path=" + publicKeyPath,
                        "ai.integration.security.service-credential=ctx-test-service-credential",
                        "ai.integration.platform-base-url=http://127.0.0.1:9",
                        "ai.integration.platform-service-credential=ctx-test-platform-credential");
    }

    private ApplicationContextRunner securedRunner() {
        return securedRunner(SecuredTestSupport.class);
    }

    @Test
    @DisplayName("开关关（默认）：/mcp 原样、无受保护过滤器、无安全端口 bean")
    void disabledKeepsLegacySurface() {
        new ApplicationContextRunner()
                .withUserConfiguration(McpServerConfig.class, McpSecurityConfiguration.class, SecuredTestSupport.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DelegatedPrincipalFilter.class);
                    assertThat(context).doesNotHaveBean("mcpDelegationFilterRegistration");
                    assertThat(context).doesNotHaveBean("securedMcpServlet");
                    ServletRegistrationBean<?> servlet =
                            context.getBean("mcpServlet", ServletRegistrationBean.class);
                    assertThat(servlet.getUrlMappings()).containsExactly("/mcp");
                });
    }

    @Test
    @DisplayName("开关开：受保护端点唯一（/mcp 不存在＝无旁路），过滤器前缀/顺序/分派类型就位")
    void securedLocksDownSurface() {
        securedRunner().run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context).doesNotHaveBean("mcpServlet");
            ServletRegistrationBean<?> servlet =
                    context.getBean("securedMcpServlet", ServletRegistrationBean.class);
            assertThat(servlet.getUrlMappings()).containsExactly("/internal/ai/v1/mcp");

            FilterRegistrationBean<?> delegation =
                    context.getBean("mcpDelegationFilterRegistration", FilterRegistrationBean.class);
            assertThat(delegation.getUrlPatterns()).containsExactly("/internal/ai/v1/*");
            assertThat(delegation.determineDispatcherTypes()).containsExactly(DispatcherType.REQUEST);
            assertThat(delegation.getOrder()).isEqualTo(Integer.MIN_VALUE + 200);
            assertThat(delegation.getFilter()).isInstanceOf(DelegatedPrincipalFilter.class);

            FilterRegistrationBean<?> authz =
                    context.getBean("mcpToolAuthzFilterRegistration", FilterRegistrationBean.class);
            assertThat(authz.getUrlPatterns()).containsExactly("/internal/ai/v1/*");
            assertThat(authz.determineDispatcherTypes()).containsExactly(DispatcherType.REQUEST);
            assertThat(authz.getOrder()).isEqualTo(Integer.MIN_VALUE + 300);
            assertThat(authz.getFilter()).isInstanceOf(McpToolAuthzFilter.class);

            assertThat(context).hasSingleBean(DelegatedPrincipalFilter.class);
            assertThat(context).hasSingleBean(JdbcTemplate.class);
        });
    }

    @Test
    @DisplayName("公钥缺失：启动失败（fail-fast，不允许无钥降级运行）")
    void missingPublicKeyFailsStartup() {
        securedRunner()
                .withPropertyValues("ai.integration.security.public-key-path=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public-key-path");
                });
    }

    @Test
    @DisplayName("平台事实端口配置缺省：启动失败（fail-fast，没有'没配好也先起着'）")
    void missingPlatformFactsConfigFailsStartup() {
        securedRunner()
                .withPropertyValues("ai.integration.platform-base-url=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("platform-base-url");
                });
    }

    @Test
    @DisplayName("白名单成员缺失：启动失败点名缺失工具（不静默缩小面）")
    void missingWhitelistMemberFailsStartup() {
        securedRunner(FiveToolSupport.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("白名单工具缺失")
                            .hasStackTraceContaining("query_after_sale");
                });
    }

    /** 受保护模式的装配测试支撑：数据源替身（深桩）+ 白名单全量工具面。 */
    @Configuration
    static class SecuredTestSupport {

        @Bean
        DataSource bitDataSource() {
            return mock(DataSource.class, RETURNS_DEEP_STUBS);
        }

        @Bean
        McpServerFeatures.SyncToolSpecification currentDateSpec() {
            return spec("current_date");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification searchProductSpec() {
            return spec("search_product");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryOrderSpec() {
            return spec("query_order");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryLogisticsSpec() {
            return spec("query_logistics");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryCouponsSpec() {
            return spec("query_coupons");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryAfterSaleSpec() {
            return spec("query_after_sale");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification payOrderSpec() {
            return spec("pay_order");
        }
    }

    /** 白名单缺一（query_after_sale）：用于 fail-fast 判据。 */
    @Configuration
    static class FiveToolSupport {

        @Bean
        DataSource bitDataSource() {
            return mock(DataSource.class, RETURNS_DEEP_STUBS);
        }

        @Bean
        McpServerFeatures.SyncToolSpecification currentDateSpec() {
            return spec("current_date");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification searchProductSpec() {
            return spec("search_product");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryOrderSpec() {
            return spec("query_order");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryLogisticsSpec() {
            return spec("query_logistics");
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryCouponsSpec() {
            return spec("query_coupons");
        }
    }

    private static McpServerFeatures.SyncToolSpecification spec(String name) {
        Tool tool = Tool.builder()
                .name(name)
                .description("测试工具")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .annotations(new ToolAnnotations(null, true, false, true, false, null))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> null)
                .build();
    }
}
