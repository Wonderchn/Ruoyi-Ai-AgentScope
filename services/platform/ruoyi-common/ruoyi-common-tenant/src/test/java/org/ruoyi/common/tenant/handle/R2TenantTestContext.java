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

package org.ruoyi.common.tenant.handle;

import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.common.tenant.properties.TenantProperties;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

/**
 * R-2 定向判据的最小运行环境。
 *
 * <p>{@code TenantHelper.isEnable()} 是"静态取 Spring bean 的属性"
 * （{@code SpringUtils.getProperty} → hutool {@code SpringUtil.getBean(Environment.class)}），
 * 这里用 {@link StaticApplicationContext} 注入最小环境，让 fail-closed 判据能在
 * <b>不起 Spring Boot、不连 Redis、不连数据库</b>的前提下运行。
 *
 * <p>刻意把 {@code tenant.enable} 做成可切换：fail-closed 只在租户功能启用时有意义，
 * "未启用时不应改变行为"本身也是一条判据。
 */
final class R2TenantTestContext {

    /** 与 ruoyi-admin/application.yml 的 tenant.excludes 同源。 */
    static final List<String> EXCLUDES = List.of(
            "sys_menu", "sys_tenant", "sys_tenant_package", "sys_role_dept", "sys_role_menu",
            "sys_user_post", "sys_user_role", "sys_client", "sys_oss_config", "flow_spel",
            "ai_flow_trace_run", "ai_flow_trace_node",
            "ai_legacy_user", "ai_provider_envelope", "ai_provider_spend");

    private static final String PROP = "r2-tenant-enable";

    private static StaticApplicationContext context;

    private R2TenantTestContext() {
    }

    /** 幂等安装：注册一个能回答 {@code tenant.enable} 的环境。 */
    static synchronized void install(boolean tenantEnabled) {
        if (context == null) {
            context = new StaticApplicationContext();
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource(PROP, Map.of("tenant.enable", String.valueOf(tenantEnabled))));
            new SpringUtils().setApplicationContext(context);
        }
        setTenantEnabled(tenantEnabled);
    }

    /** 运行期切换 {@code tenant.enable}（同一 JVM 内多个判据共享一个静态上下文）。 */
    static void setTenantEnabled(boolean enabled) {
        StandardEnvironment environment = (StandardEnvironment) context.getEnvironment();
        environment.getPropertySources().remove(PROP);
        environment.getPropertySources()
                .addFirst(new MapPropertySource(PROP, Map.of("tenant.enable", String.valueOf(enabled))));
    }

    /** 与生产同一份配置形状：共享表清单来自 tenant.excludes。 */
    static TenantProperties properties() {
        TenantProperties properties = new TenantProperties();
        properties.setEnable(true);
        properties.setExcludes(EXCLUDES);
        return properties;
    }

    /** 用任意 excludes 构造属性（F2 判据要注入"被放宽的配置"）。 */
    static TenantProperties properties(List<String> excludes) {
        TenantProperties properties = new TenantProperties();
        properties.setEnable(true);
        properties.setExcludes(excludes);
        return properties;
    }

    static PlusTenantLineHandler handler() {
        return new PlusTenantLineHandler(properties());
    }

    /** 清空所有租户上下文（动态租户 + 线程内租户）。 */
    static void clearTenantContext() {
        TenantHelper.clearDynamic();
    }
}
