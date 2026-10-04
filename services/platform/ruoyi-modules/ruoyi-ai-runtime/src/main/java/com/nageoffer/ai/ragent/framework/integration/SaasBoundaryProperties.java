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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SaaS 边界开关（只含安全布尔配置，缺省全部关闭）。
 *
 * <p>P1.2a 语义（Spec 01 §3.2）：本单元<b>没有</b>"重开旧能力"的开关。
 * 三个开关在产品配置里出现 {@code true} 都属于未批准能力，启动直接拒绝；
 * 因此它们目前只能是 {@code false}。真正按动作开放客户 API 由 P1.2c 之后
 * 的精确 canonical/action 能力表接续，不能靠这里放行。
 *
 * <ul>
 *   <li>{@code ai.integration.enabled}：生产委托接线总开关（P1.2c 才有正例）；</li>
 *   <li>{@code ai.integration.customer-api.enabled}：客户 API 面（P1.2c 起按动作开放）；</li>
 *   <li>{@code ai.integration.legacy-listeners-enabled}：旧 MQ listener / 事务回查 /
 *       定时扫描装配开关，<b>永不</b>用于客户上线。</li>
 * </ul>
 *
 * <p>{@code ai.integration.startup-validation-enabled} 只控制"非法 true 是否让启动失败"
 * 这条自检本身。生产配置不设置该属性（默认 {@code true}），因此产品启动必经校验；
 * 仅"扫描几个类"的单元装配上下文可以显式关掉它。
 */
@ConfigurationProperties(prefix = "ai.integration")
public record SaasBoundaryProperties(
        Boolean enabled,
        Boolean startupValidationEnabled,
        CustomerApi customerApi,
        Boolean legacyListenersEnabled) {

    /** 客户 API 面开关。 */
    public record CustomerApi(Boolean enabled) {
    }

    /** 总开关默认关闭：缺属性与显式 false 等价。 */
    public boolean isIntegrationEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 客户 API 默认关闭。{@code customer-api} 段整体缺失同样视为关闭，
     * 不因为"没写"而放开。
     */
    public boolean isCustomerApiEnabled() {
        return customerApi != null && Boolean.TRUE.equals(customerApi.enabled());
    }

    /** 旧自动触发默认关闭；true 表示试图启用未批准的旧能力。 */
    public boolean isLegacyListenersEnabled() {
        return Boolean.TRUE.equals(legacyListenersEnabled);
    }

    /** 启动自检默认开启。 */
    public boolean isStartupValidationEnabled() {
        return !Boolean.FALSE.equals(startupValidationEnabled);
    }
}
