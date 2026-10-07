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

package com.nageoffer.ai.ragent.framework.integration;

import com.nageoffer.ai.ragent.framework.exception.AbstractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

/**
 * P1.2a 旧能力关闭契约：缺属性、显式 {@code false}、甚至 properties 对象整体缺席，
 * 都必须让每个 {@link SaasCapabilityBoundary.LegacyCapability} 保持关闭。
 *
 * <p><b>为什么必须有正向对照</b>：一个 {@code requireOpen} 恒抛、{@code isLegacyOpen} 恒假的桩实现，
 * 能让本类所有"关闭"断言通过。因此这里额外用 {@code legacy-listeners-enabled=true} 构造同一边界，
 * 证明拒绝来自开关状态，而不是来自"这个类只会拒绝"。
 */
@Tag("dev")
class SaasCapabilityBoundaryTest {

    /** 缺属性：产品配置里 {@code ai.integration} 段整体不存在。 */
    private static SaasBoundaryProperties missingProperties() {
        return new SaasBoundaryProperties(null, null, null, null);
    }

    /** 正向对照用：唯一能让旧能力"技术上打开"的真值来源。 */
    private static SaasBoundaryProperties legacyOpenProperties() {
        return new SaasBoundaryProperties(null, null, null, true);
    }

    @Test
    @DisplayName("缺属性：每个旧能力都关闭，且异常带出被请求的那个能力")
    void missingPropertiesCloseEveryLegacyCapability() {
        SaasCapabilityBoundary boundary = new SaasCapabilityBoundary(missingProperties());

        assertFalse(boundary.legacyEnabled(), "缺属性不得等价于开启旧能力");
        assertFalse(boundary.isLegacyOpen(), "缺属性下旧能力必须关闭");

        for (SaasCapabilityBoundary.LegacyCapability capability : SaasCapabilityBoundary.LegacyCapability.values()) {
            SaasCapabilityBoundary.ClosedCapabilityException thrown = assertThrows(
                    SaasCapabilityBoundary.ClosedCapabilityException.class,
                    () -> boundary.requireOpen(capability),
                    () -> "关闭的旧能力必须抛受控异常: " + capability);
            // 带出具体能力是现场排障与审计的唯一线索；抛错却指错能力会把定位带偏
            assertSame(capability, thrown.capability(), "异常必须带出被请求的那个能力");
            assertTrue(thrown.getMessage().contains(capability.name()), "异常消息必须含能力名，便于日志检索");
        }
    }

    @Test
    @DisplayName("显式 false：即使总开关与客户 API 开关为 true，旧能力仍关闭（第二条关闭路径）")
    void explicitFalseLegacyListenersIsClosedEvenWhenOtherSwitchesAreOn() {
        SaasBoundaryProperties properties = new SaasBoundaryProperties(
                true, null, new SaasBoundaryProperties.CustomerApi(true), false);
        SaasCapabilityBoundary boundary = new SaasCapabilityBoundary(properties);

        // 别的开关打开不能"顺带"打开旧能力：真值只有一个来源
        assertTrue(properties.isIntegrationEnabled(), "本用例前提：总开关为 true");
        assertTrue(properties.isCustomerApiEnabled(), "本用例前提：客户 API 开关为 true");
        assertFalse(boundary.legacyEnabled());
        assertFalse(boundary.isLegacyOpen(), "legacy-listeners-enabled=false 时必须关闭");

        for (SaasCapabilityBoundary.LegacyCapability capability : SaasCapabilityBoundary.LegacyCapability.values()) {
            SaasCapabilityBoundary.ClosedCapabilityException thrown = assertThrows(
                    SaasCapabilityBoundary.ClosedCapabilityException.class,
                    () -> boundary.requireOpen(capability),
                    () -> "显式关闭同样必须拒绝: " + capability);
            assertSame(capability, thrown.capability());
        }
    }

    @Test
    @DisplayName("properties 对象整体缺席：按关闭处理，不得 NPE、更不得放行")
    void absentPropertiesObjectIsClosed() {
        SaasCapabilityBoundary boundary = new SaasCapabilityBoundary(null);

        // 可选注入取不到边界 Bean 时调用点可能拿到 null properties；这条路径不能变成 NPE 之外的"放行"
        assertFalse(boundary.legacyEnabled());
        assertFalse(boundary.isLegacyOpen());
        for (SaasCapabilityBoundary.LegacyCapability capability : SaasCapabilityBoundary.LegacyCapability.values()) {
            assertThrows(SaasCapabilityBoundary.ClosedCapabilityException.class,
                    () -> boundary.requireOpen(capability));
        }
    }

    @Test
    @DisplayName("启动自检默认开启，只有显式 false 才关闭")
    void startupValidationDefaultsOnAndOnlyExplicitFalseDisablesIt() {
        // 产品配置不写该属性，所以"默认 true"是启动必经自检的前提；默认值一旦被改成 false，非法 true 会静默通过
        assertTrue(missingProperties().isStartupValidationEnabled(), "缺属性时启动自检必须默认开启");
        assertTrue(new SaasBoundaryProperties(null, true, null, null).isStartupValidationEnabled());
        assertFalse(new SaasBoundaryProperties(null, false, null, null).isStartupValidationEnabled(),
                "只有显式 false 才允许跳过启动自检");
    }

    @Test
    @DisplayName("总开关与客户 API 开关默认关闭，只有显式 true 才是 true")
    void integrationAndCustomerApiDefaultOffAndOnlyExplicitTrueOpens() {
        SaasBoundaryProperties missing = missingProperties();
        assertFalse(missing.isIntegrationEnabled());
        // customer-api 段整体缺失（null）必须与 enabled=false 等价："没写"不等于"放开"
        assertFalse(missing.isCustomerApiEnabled());
        assertFalse(new SaasBoundaryProperties(null, null, new SaasBoundaryProperties.CustomerApi(null), null)
                .isCustomerApiEnabled());
        assertFalse(new SaasBoundaryProperties(null, null, new SaasBoundaryProperties.CustomerApi(false), null)
                .isCustomerApiEnabled());

        assertTrue(new SaasBoundaryProperties(true, null, null, null).isIntegrationEnabled());
        assertTrue(new SaasBoundaryProperties(null, null, new SaasBoundaryProperties.CustomerApi(true), null)
                .isCustomerApiEnabled());
    }

    @Test
    @DisplayName("边界缺席按关闭处理：provider 为 null 与取不到实例都必须拒绝")
    void requireOpenOrClosedRejectsWhenProviderIsAbsentOrEmpty() {
        for (SaasCapabilityBoundary.LegacyCapability capability : SaasCapabilityBoundary.LegacyCapability.values()) {
            // 可选注入没配上（provider 为 null）是最容易被写成"没 Bean 就算放行"的分支
            assertThrows(SaasCapabilityBoundary.ClosedCapabilityException.class,
                    () -> SaasCapabilityBoundary.requireOpenOrClosed(null, capability),
                    () -> "provider 缺席绝不能等价于放行: " + capability);
            // 注入了 provider 但容器里没有该 Bean，同样必须拒绝
            assertThrows(SaasCapabilityBoundary.ClosedCapabilityException.class,
                    () -> SaasCapabilityBoundary.requireOpenOrClosed(new FixedBoundaryProvider(null), capability),
                    () -> "provider 取不到实例时必须拒绝: " + capability);
        }
    }

    @Test
    @DisplayName("正向对照：legacy=true 时同一边界不再拒绝（证明上面的抛来自开关）")
    void openSwitchMakesTheSameBoundaryStopRejecting() {
        SaasCapabilityBoundary boundary = new SaasCapabilityBoundary(legacyOpenProperties());

        assertTrue(boundary.legacyEnabled());
        assertTrue(boundary.isLegacyOpen(), "正向对照要求开关真的能打开，否则负向断言是空转");

        // 至少两个能力不抛即可证明实现不是恒抛的桩；这里对全部枚举逐一验证，覆盖面更大
        List<SaasCapabilityBoundary.LegacyCapability> sample = List.of(
                SaasCapabilityBoundary.LegacyCapability.KB_CLEANUP_CONSUMER,
                SaasCapabilityBoundary.LegacyCapability.STORAGE_INITIALIZER,
                SaasCapabilityBoundary.LegacyCapability.AGENT_MCP_STARTUP);
        for (SaasCapabilityBoundary.LegacyCapability capability : sample) {
            assertDoesNotThrow(() -> boundary.requireOpen(capability),
                    () -> "开关为 true 时不得再拒绝: " + capability);
        }
        for (SaasCapabilityBoundary.LegacyCapability capability : SaasCapabilityBoundary.LegacyCapability.values()) {
            assertDoesNotThrow(() -> boundary.requireOpen(capability));
        }
    }

    @Test
    @DisplayName("正向对照：provider 能取到开启态边界时 requireOpenOrClosed 不抛")
    void requireOpenOrClosedPassesWhenProviderYieldsOpenBoundary() {
        SaasCapabilityBoundary openBoundary = new SaasCapabilityBoundary(legacyOpenProperties());
        FixedBoundaryProvider provider = new FixedBoundaryProvider(openBoundary);

        // 先证明 provider 真的交出了非 null 的开启态边界，否则"不抛"可能只是因为它什么也没做
        assertSame(openBoundary, provider.getIfAvailable());
        for (SaasCapabilityBoundary.LegacyCapability capability : SaasCapabilityBoundary.LegacyCapability.values()) {
            assertDoesNotThrow(() -> SaasCapabilityBoundary.requireOpenOrClosed(provider, capability));
        }
    }

    @Test
    @DisplayName("关闭异常不得是会被上层 catch-and-continue 吞掉的业务异常子类")
    void closedCapabilityExceptionIsNotABusinessException() {
        // 旧消费者/回查/调度普遍 catch 业务异常后"记录并继续"；关闭信号一旦落进那些分支，
        // 就会被转成"返回成功/继续执行"，关闭随即失效。这条断言守住该契约。
        assertFalse(
                AbstractException.class.isAssignableFrom(SaasCapabilityBoundary.ClosedCapabilityException.class),
                "ClosedCapabilityException 不得继承业务异常基类 AbstractException");
        // 也不能是受检异常：否则调用点被迫写 try/catch，又会多出"吞掉"的分支
        assertTrue(RuntimeException.class.isAssignableFrom(SaasCapabilityBoundary.ClosedCapabilityException.class));
    }

    /** 最小 {@link ObjectProvider} 替身：只回答"取不取得到"，用于断言缺席路径。 */
    private static final class FixedBoundaryProvider implements ObjectProvider<SaasCapabilityBoundary> {

        private final SaasCapabilityBoundary boundary;

        private FixedBoundaryProvider(SaasCapabilityBoundary boundary) {
            this.boundary = boundary;
        }

        @Override
        public SaasCapabilityBoundary getObject() {
            if (boundary == null) {
                throw new IllegalStateException("no boundary bean");
            }
            return boundary;
        }

        @Override
        public SaasCapabilityBoundary getObject(Object... args) {
            return getObject();
        }

        @Override
        public SaasCapabilityBoundary getIfAvailable() {
            return boundary;
        }

        @Override
        public SaasCapabilityBoundary getIfUnique() {
            return boundary;
        }
    }
}
