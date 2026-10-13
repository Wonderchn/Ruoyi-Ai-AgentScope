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

package org.ruoyi.aiidentity;

import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.tenant.handle.TenantKeyPrefixHandler;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10 op2（stop 频道不可达）回归判据：跨节点取消频道名必须租户无关。
 *
 * <p>缺陷形态：取消频道若不含 {@code global:}，订阅发生在容器启动线程（无租户，频道名原样），
 * 发布发生在 stop 请求线程（租户 000000，频道名被 {@link TenantKeyPrefixHandler}
 * 加上 {@code 000000:} 前缀），两端频道名不等，stop 广播永远到不了执行节点。
 *
 * <p>判据钉的是生产常量本身（反射读取），不复制字面量；负控证明本判据不是恒真，
 * 正控钉住既有跨节点频道（global:sse / global:websocket / 限流 notify）的同一约定。
 *
 * <p>不连 Redis、不起 Spring Boot：{@code tenant.enable} 用最小静态环境回答
 * （手法同 {@code org.ruoyi.common.tenant.handle.R2TenantTestContext}），
 * 租户夹具用非登录态下只写线程内的动态租户。
 */
@Tag("dev")
class F10StreamCancelTopicNamespaceTest {

    /** 平台默认租户号（与 ruoyi-admin/application.yml 同源）。 */
    private static final String TENANT = "000000";

    private static final String PROPERTY_SOURCE = "f10-cancel-topic-tenant-enable";

    private static StaticApplicationContext tenantEnvironment;
    private static ApplicationContext previousApplicationContext;

    private final TenantKeyPrefixHandler handler = new TenantKeyPrefixHandler("");

    @BeforeAll
    static void installTenantEnvironment() {
        // TenantHelper.isEnable() 静态读 Spring 属性：装最小环境回答 tenant.enable，
        // 与 R2TenantTestContext 同款手法（不起 Spring Boot、不连 Redis/DB）
        previousApplicationContext = SpringUtils.getApplicationContext();
        tenantEnvironment = new StaticApplicationContext();
        tenantEnvironment.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of("tenant.enable", "true")));
        new SpringUtils().setApplicationContext(tenantEnvironment);
    }

    @AfterAll
    static void restoreSpringContext() {
        // 还原静态环境，不给同 JVM 后续测试类留状态（hutool setter 会无条件覆盖静态字段）
        new SpringUtils().setApplicationContext(previousApplicationContext);
    }

    @AfterEach
    void clearTenantContext() {
        // 非登录态下 clearDynamic 只清线程内动态租户，不碰 Redis/SaHolder
        TenantHelper.clearDynamic();
    }

    @Test
    @DisplayName("F10 op2：取消频道在无租户（订阅线程）与租户 000000（发布线程）下映射同名")
    void cancelTopicMapsToSameChannelWithAndWithoutTenant() throws ReflectiveOperationException {
        String cancelTopic = cancelTopicConstant();

        // 订阅：@PostConstruct 容器启动线程，无租户
        String subscribeChannel = handler.map(cancelTopic);
        // 发布：/agent/v1/stop 请求线程，动态租户 000000
        TenantHelper.setDynamic(TENANT);
        // 夹具自检：租户必须真的可见，否则两端都无前缀会假绿
        assertThat(TenantHelper.getTenantId()).as("夹具自检：租户 000000 必须可见").isEqualTo(TENANT);
        String publishChannel = handler.map(cancelTopic);

        assertThat(publishChannel)
                .as("F10-op2-P0 判据：CANCEL_TOPIC=%s 两态映射必须同名（基线红应恰好出自这条）", cancelTopic)
                .isEqualTo(subscribeChannel);
    }

    @Test
    @DisplayName("负控：不带 global: 的频道在两态必然不等（本判据不是恒真）")
    void plainChannelDivergesAcrossTenantStates() {
        String plainChannel = "ragent:other";

        String subscribeChannel = handler.map(plainChannel);
        TenantHelper.setDynamic(TENANT);
        // 夹具自检：没有生效的租户上下文，"两态不等"就成了空话
        assertThat(TenantHelper.getTenantId()).as("夹具自检：租户 000000 必须可见").isEqualTo(TENANT);
        String publishChannel = handler.map(plainChannel);

        assertThat(subscribeChannel).as("负控：无租户时非 global: 频道原样").isEqualTo(plainChannel);
        assertThat(publishChannel).as("负控：有租户时非 global: 频道加租户前缀").isEqualTo(TENANT + ":" + plainChannel);
        assertThat(publishChannel).as("负控：非 global: 频道两态必须不等（证明主判据不是恒真）").isNotEqualTo(subscribeChannel);
    }

    @Test
    @DisplayName("正控：既有跨节点频道（global:sse/global:websocket/限流 notify）两态同名，约定不许破")
    void existingGlobalChannelsAreTenantInvariant() {
        for (String channel : new String[]{"global:sse", "global:websocket", "rag:global:chat:queue:notify"}) {
            String subscribeChannel = handler.map(channel);
            TenantHelper.setDynamic(TENANT);
            String publishChannel = handler.map(channel);
            TenantHelper.clearDynamic();

            assertThat(subscribeChannel).as("无租户映射 %s", channel).isEqualTo(channel);
            assertThat(publishChannel).as("租户 000000 下映射 %s", channel).isEqualTo(channel);
        }
    }

    /** 反射读生产常量：判据不能落在测试自己复制的字面量上。 */
    private static String cancelTopicConstant() throws ReflectiveOperationException {
        Field field = StreamTaskManager.class.getDeclaredField("CANCEL_TOPIC");
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
