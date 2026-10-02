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

package com.nageoffer.ai.ragent.agent.tool;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * P1.2a 启动期 MCP 连接的关闭护栏（单元级契约）。
 *
 * <p>被验证的语义：{@link AgentMcpClients} 在"旧能力关闭"时的正确行为是
 * <b>一个远端 MCP 请求都不发</b>——既不是"先连上再回滚"，也不是"因为关闭所以启动失败"。
 * 关闭不是错误状态，它正是默认状态：{@code init()} 是 {@code @PostConstruct}，
 * 抛异常会让整个应用起不来，因此它记日志后正常返回（旧 MQ 消费者/事务回查器会抛
 * {@code ClosedCapabilityException}，但那类方法不是 {@code @PostConstruct}）。
 *
 * <p>证据分两层，缺一不可：
 * <ol>
 *   <li>{@link AgentMcpClients#connectAttempts()} 的进程内累计计数增量——"零网络调用"的
 *       <b>可观测</b>证据：{@code connect()} 第一行就自增，因此增量恒为 0 等价于
 *       "连接根本没被尝试过"，而不是"连了但失败了"；</li>
 *   <li>工具表与连接表为空——"零发现"的结构证据，仅靠 {@code get()} 返回 null 无法排除
 *       "表里还有别的键"。</li>
 * </ol>
 *
 * <p>本类只覆盖单元级契约（{@code init(SaasCapabilityBoundary)} / {@code init()} /
 * {@code close()}）。装配条件（{@code ragent.engine.type}）与上下文级零连接护栏在
 * {@link AgentMcpClientsModeTest} 中，两者刻意不重复。
 */
class P1McpStartupBoundaryTest {

    /**
     * 确定不可达端点：连过去会被立刻拒绝，因此"是否发生过连接尝试"完全由
     * {@link AgentMcpClients#connectAttempts()} 决定，不依赖任何外部服务。
     */
    private static final String DEAD_ENDPOINT = "http://127.0.0.1:1";

    /** 关闭态：只把"旧能力总开关"写成 false，与产品默认配置同义。 */
    private static final SaasBoundaryProperties CLOSED_PROPERTIES =
            new SaasBoundaryProperties(false, true, new SaasBoundaryProperties.CustomerApi(false), false);

    /** 打开态：仅用于正向对照，证明"零连接"断言不是因为连接路径本来就不可达。 */
    private static final SaasBoundaryProperties LEGACY_OPEN_PROPERTIES =
            new SaasBoundaryProperties(false, true, new SaasBoundaryProperties.CustomerApi(false), true);

    private static final SaasCapabilityBoundary CLOSED_BOUNDARY = new SaasCapabilityBoundary(CLOSED_PROPERTIES);

    private static final SaasCapabilityBoundary OPEN_BOUNDARY = new SaasCapabilityBoundary(LEGACY_OPEN_PROPERTIES);

    @Test
    @DisplayName("默认关闭：init 正常返回，零连接尝试、零工具、连接表为空")
    void closedBoundarySkipsStartupWithoutAnyConnectAttempt() {
        AgentMcpProperties properties = propertiesFor("default");
        AgentMcpClients clients = new AgentMcpClients(properties, providerOf(CLOSED_BOUNDARY));

        assertThat(CLOSED_BOUNDARY.isLegacyOpen()).as("关闭态构造前提").isFalse();
        assertThat(properties.getServers())
                .as("必须真的有可连的服务，否则下面的零连接断言是空转")
                .hasSize(1);

        int before = AgentMcpClients.connectAttempts();
        assertThatCode(() -> clients.init(CLOSED_BOUNDARY))
                .as("关闭态不是错误状态：@PostConstruct 路径不得让应用启动失败")
                .doesNotThrowAnyException();

        assertThat(AgentMcpClients.connectAttempts())
                .as("关闭态下一次远端 MCP 连接尝试都不允许")
                .isEqualTo(before);
        assertThat(clients.get("default")).as("服务名不是工具 id，更不能命中").isNull();
        assertThat(clients.get("any-remote-tool")).isNull();
        assertThat(internalCollection(clients, "clients")).as("连接表必须为空").isEmpty();
        assertThat(internalMap(clients, "tools")).as("工具表必须为空").isEmpty();
    }

    @Test
    @DisplayName("边界 Bean 缺席按关闭处理，而不是「没有守卫就照常连」")
    void missingBoundaryIsTreatedAsClosedNotAsPermissionToConnect() {
        // 辅助设施自检：providerOf(null) 必须真的模拟"上下文里没有该 Bean"，
        // 否则下面的"缺席"用例其实什么都没测到。
        assertThat(providerOf((SaasCapabilityBoundary) null).getIfAvailable()).isNull();
        assertThat(providerOf(CLOSED_BOUNDARY).getIfAvailable()).isSameAs(CLOSED_BOUNDARY);

        AgentMcpClients explicitNull = new AgentMcpClients(propertiesFor("default"),
                providerOf((SaasCapabilityBoundary) null));
        AgentMcpClients missingBean = new AgentMcpClients(propertiesFor("default"),
                providerOf((SaasCapabilityBoundary) null));
        int before = AgentMcpClients.connectAttempts();

        // 1) 显式 null：装配层给不出边界时的等价路径
        assertThatCode(() -> explicitNull.init(null))
                .as("边界缺席不得被解释成「没有守卫，于是照常连接」")
                .doesNotThrowAnyException();

        // 2) 真实 @PostConstruct 入口：ObjectProvider 取不到 Bean
        assertThatCode(missingBean::init)
                .as("边界 Bean 缺席不能让启动失败，但也不能变成放行")
                .doesNotThrowAnyException();

        assertThat(AgentMcpClients.connectAttempts())
                .as("缺席即关闭：零连接尝试")
                .isEqualTo(before);
        assertThat(missingBean.get("default")).isNull();
        assertThat(internalCollection(missingBean, "clients")).isEmpty();
        assertThat(internalMap(missingBean, "tools")).isEmpty();

        // 缺席属于装配错误：必须留下 ERROR 证据（而不是悄悄按关闭处理、事后无人知晓）
        assertThat(errorMessages(logsWhile(missingBean::init)))
                .as("边界 Bean 缺席时必须记 ERROR，便于发现装配错误")
                .anySatisfy(message -> assertThat(message).contains("boundary bean is missing"));
    }

    @Test
    @DisplayName("关闭态下不可达服务连尝试都不会发生：零 connectAttempts 增量，重复 init 也保持")
    void unreachableServerIsNotEvenAttemptedWhileClosed() {
        AgentMcpProperties properties = propertiesFor("dead-a", "dead-b");
        assertThat(properties.getServers()).extracting(AgentMcpProperties.ServerConfig::getUrl)
                .as("配置里确实挂着两个确定不可达的服务")
                .containsOnly(DEAD_ENDPOINT);
        AgentMcpClients clients = new AgentMcpClients(properties, providerOf(CLOSED_BOUNDARY));

        int before = AgentMcpClients.connectAttempts();
        clients.init(CLOSED_BOUNDARY);
        // 第二次调用：关闭判定不能因为"已经 init 过一次"而放行（无状态泄漏）
        clients.init(CLOSED_BOUNDARY);

        assertThat(AgentMcpClients.connectAttempts())
                .as("计数器在 connect() 第一行自增：增量 0 说明两个服务都没被尝试连接过")
                .isEqualTo(before);
        assertThat(clients.get("dead-a")).isNull();
        assertThat(clients.get("dead-b")).isNull();
        assertThat(internalCollection(clients, "clients")).isEmpty();
        assertThat(internalMap(clients, "tools")).isEmpty();
    }

    @Test
    @DisplayName("装配与销毁完整：零连接 close() 安全；缺边界 Bean 的上下文照常启动且零连接")
    void beanCreationAndDestructionAreIntactWithoutBoundaryBean() {
        // (a) 单元级销毁路径：一个连接都没有时 close() 必须是空操作，不能 NPE
        AgentMcpClients unconnected = new AgentMcpClients(propertiesFor("default"), providerOf(CLOSED_BOUNDARY));
        assertThatCode(unconnected::close).doesNotThrowAnyException();
        assertThat(unconnected.get("default")).isNull();

        // (b) 真实上下文：@PostConstruct 在 refresh 期间触发 init()，而上下文里没有
        //     SaasCapabilityBoundary Bean。修复后的行为是记 ERROR 后正常返回，因此
        //     上下文必须启动成功——早先"@PostConstruct 抛 ClosedCapabilityException"
        //     的版本会让这里 refresh 直接失败（这正是本用例守护的回归点）。
        int before = AgentMcpClients.connectAttempts();
        new ApplicationContextRunner()
                .withUserConfiguration(AgentMcpClients.class)
                .withPropertyValues(
                        "ragent.engine.type=agent",
                        "agent.mcp.servers[0].name=default",
                        "agent.mcp.servers[0].url=" + DEAD_ENDPOINT)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SaasCapabilityBoundary.class);
                    assertThat(context).hasSingleBean(AgentMcpClients.class);
                    assertThat(context.getStartupFailure())
                            .as("缺少守卫 Bean 不得让启动失败：不连 MCP 才是默认状态")
                            .isNull();
                    assertThat(context.getBean(AgentMcpProperties.class).getServers())
                            .as("上下文里必须真的有可连的服务，否则零连接断言是空转")
                            .hasSize(1);

                    AgentMcpClients clients = context.getBean(AgentMcpClients.class);
                    assertThat(AgentMcpClients.connectAttempts()).isEqualTo(before);

                    // refresh 之后再手动 init：边界 Bean 仍然缺席，仍不得连接
                    assertThatCode(clients::init).doesNotThrowAnyException();
                    assertThat(AgentMcpClients.connectAttempts()).isEqualTo(before);

                    assertThat(clients.get("default")).isNull();
                    assertThat(internalCollection(clients, "clients")).isEmpty();
                    assertThat(internalMap(clients, "tools")).isEmpty();
                    assertThatCode(clients::close).doesNotThrowAnyException();
                });
        // 上下文已关闭（@PreDestroy 走 close()），全程仍不得有任何连接尝试
        assertThat(AgentMcpClients.connectAttempts())
                .as("上下文启动 + 销毁全过程中零连接尝试")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("正向对照：显式打开后同一个不可达服务必须被真的尝试连接，证明零增量断言并非空转")
    void openBoundaryActuallyAttemptsConnectForTheSameUnreachableServer() {
        AgentMcpProperties properties = propertiesFor("dead-endpoint");
        AgentMcpClients clients = new AgentMcpClients(properties, providerOf(OPEN_BOUNDARY));

        assertThat(OPEN_BOUNDARY.isLegacyOpen()).as("打开态构造前提").isTrue();
        int before = AgentMcpClients.connectAttempts();

        assertThatCode(() -> clients.init(OPEN_BOUNDARY))
                .as("连接失败由 connect() 内部捕获并记日志，不得冒泡成异常")
                .doesNotThrowAnyException();

        assertThat(AgentMcpClients.connectAttempts())
                .as("同一份配置、同一个端点：打开边界后必须真的发起连接尝试")
                .isEqualTo(before + 1);
        // 尝试 != 成功：端点不可达，工具表仍然为空，连接表也不该留下失败连接
        assertThat(clients.get("dead-endpoint")).isNull();
        assertThat(internalCollection(clients, "clients")).isEmpty();
        assertThat(internalMap(clients, "tools")).isEmpty();
    }

    // ------------------------------------------------------------------ 辅助

    /** 构造一份产品形态的 MCP 配置：若干个服务名，全部指向确定不可达的端点。 */
    private static AgentMcpProperties propertiesFor(String... serverNames) {
        AgentMcpProperties properties = new AgentMcpProperties();
        for (String name : serverNames) {
            AgentMcpProperties.ServerConfig server = new AgentMcpProperties.ServerConfig();
            server.setName(name);
            server.setUrl(DEAD_ENDPOINT);
            properties.getServers().add(server);
        }
        return properties;
    }

    /** 最小 {@link ObjectProvider}：命中时返回固定实例，{@code null} 模拟"上下文里没有该 Bean"。 */
    private static <T> ObjectProvider<T> providerOf(T value) {
        return new FixedObjectProvider<>(value);
    }

    /**
     * 读取 {@link AgentMcpClients} 的私有集合字段。
     *
     * <p>用反射的原因：该类只暴露 {@link AgentMcpClients#get(String)}（按工具 id 查询），
     * 没有任何访问器能证明 {@code clients} / {@code tools} <b>整体为空</b>；而"零连接、零工具"
     * 正是本测试要证明的关闭语义，只断言 {@code get()} 返回 null 无法排除"表里还有别的键"。
     * 字段找不到时直接失败，不允许静默跳过——否则护栏会在重构后悄悄失效。
     */
    private static Collection<?> internalCollection(AgentMcpClients clients, String fieldName) {
        Object value = internalField(clients, fieldName);
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalStateException("AgentMcpClients." + fieldName + " is not a Collection: " + value);
        }
        return collection;
    }

    /** 同上，用于 {@code Map} 形态的内部表。 */
    private static Map<?, ?> internalMap(AgentMcpClients clients, String fieldName) {
        Object value = internalField(clients, fieldName);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("AgentMcpClients." + fieldName + " is not a Map: " + value);
        }
        return map;
    }

    private static Object internalField(AgentMcpClients clients, String fieldName) {
        try {
            Field field = AgentMcpClients.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(clients);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read AgentMcpClients." + fieldName
                    + "; the closure guard layout probably changed", e);
        }
    }

    /** 捕获 {@link AgentMcpClients} 在一次动作中产生的日志事件。 */
    private static List<ILoggingEvent> logsWhile(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AgentMcpClients.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    private static List<String> errorMessages(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> Level.ERROR.equals(event.getLevel()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 固定值 provider；"取不到"时 {@code getObject()} 直接失败，绝不返回 null 让调用方继续。 */
    static final class FixedObjectProvider<T> implements ObjectProvider<T> {

        private final T value;

        FixedObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            return require();
        }

        @Override
        public T getObject(Object... args) {
            return require();
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        private T require() {
            if (value == null) {
                throw new IllegalStateException("no bean available for this provider");
            }
            return value;
        }
    }
}
