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

package com.nageoffer.ai.ragent.runtime.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.nageoffer.ai.ragent.authorization.TenantBarrierReconciler;

/**
 * D02 运行权威端口的装配（本模块自持，不再依赖宿主侧逐条列举）。
 *
 * <p><b>为什么需要它。</b>{@link RunConfigBindingPort}（`JdbcRunConfigBindingPort`）与
 * {@link ProviderConnectionPort} 在此之前**都没有 bean 定义** —— 只有类与判据测试。
 * 也就是说"C1.6 的端口已定义"是真的，"它在运行期能被注入"却不成立：端口没有装配点，
 * 调用方（`RagChatExecutor` / `AgentModelAdapter`）拿不到取事实的方式。
 * 本配置同时给**受理侧**提供发布权威（见 {@link #engineModelAuthority}）。
 *
 * <p><b>为什么注册在本模块的资源里而不是宿主。</b>AI 类全部位于 {@code com.nageoffer.ai.ragent.**}，
 * 不在 platform 的组件扫描路径内；宿主只能靠 `AutoConfiguration.imports` 显式列举。
 * 本模块自持一份 `AutoConfiguration.imports` 后，Boot 会自动聚合所有 jar 的 imports，
 * 因此**不必**改宿主的 imports 文件，也不产生跨租约改动。
 *
 * <p><b>门控与 fail-closed。</b>仅在 {@code ai.integration.enabled=true} 时注册（与
 * `AiResourceWriteService` / `RagChatExecutor` / 引擎装配同一开关：该开关关闭时 AI 侧 bean 本就不存在，
 * 因此不会出现"消费者在、权威端口不在"的半装配形态）。消费方用 **required 注入**，
 * 端口缺失即启动期失败（响亮），不会退化成"运行期静默拿不到配置"。
 *
 * <p><b>两个 {@code @ConditionalOnMissingBean} 都写明具体类型</b>：不带参数的写法按方法返回类型推断，
 * 而这里返回的就是接口，显式写明类型才能让"将来有人提供别的实现"被正确跳过而不是叠加第二个 bean。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
@EnableConfigurationProperties(ProviderConnections.class)
public class RuntimeAuthorityConfiguration {

    /**
     * run 级配置绑定端口（C1.2/C1.3）。
     *
     * <p>刻意**不加缓存**：C1.3 要求撤权立即生效，而进程内缓存正是让"撤权后旧版本仍在用"发生的东西
     * （`JdbcRunConfigBindingPort` 的类注释已把这条写死）。
     */
    @Bean
    @ConditionalOnMissingBean(RunConfigBindingPort.class)
    public RunConfigBindingPort runConfigBindingPort(JdbcTemplate jdbc) {
        return new JdbcRunConfigBindingPort(jdbc);
    }

    /**
     * **受理侧**用的发布权威（C1.2 第 1 行）：受理时取该租户当前 PUBLISHED 版本并固定到 run 上。
     *
     * <p><b>为什么这里还要一个注册点（读的人会问"是不是重复注册"）。</b>
     * `EngineModelAuthority` 的另一处注册点是
     * `org.ruoyi.aiweb.embedded.AiEmbeddedAgentEngineConfiguration`（`ruoyi-ai-web`），
     * 它嵌套在 `@ConditionalOnProperty("ragent.engine.type"="agent")` 之内 —— 而 C13.4 已核实
     * **全仓 shipped yml 里 `ragent` 命中 = 0**，即本形态下**它不是 bean**。可 run 组
     * （`rag.chat` / `document.ingest`）与 agent 组**都要**有发布权威：受理不给 run 绑版本，
     * 执行期的 `RunConfigBindingPort` 就会对每个 run 都拒绝。
     *
     * <p><b>两个注册点靠"显式补集"互斥，不依赖 auto-config 排序</b>：本 bean 只在
     * `ragent.engine.type != agent` 时激活，而那一个只在 `== agent` 时激活 ⇒ 任何形态下
     * **恰好一个**实现。`@ConditionalOnMissingBean` 只作第二道保险（若将来有人加了第三个注册点，
     * 这里不会叠加第二个 bean）。判据：`EngineModelAuthorityExclusivityTest` 在两种属性形态下
     * 分别断言本配置产出 1 / 0 个实现。
     */
    @Bean
    @ConditionalOnMissingBean(EngineModelAuthority.class)
    @ConditionalOnExpression("!'agent'.equals('${ragent.engine.type:}')")
    public EngineModelAuthority engineModelAuthority(JdbcTemplate jdbc) {
        return new PublishedModelAuthority(jdbc);
    }

    /**
     * 租户屏障的**唯一**对账口径（G-55c）。
     *
     * <p>放在本配置里注册（而不是让 {@code TenantBarrierReconciler} 自带 {@code @Component}）：
     * AI 类位于 {@code com.nageoffer.ai.ragent.**}，不在 platform 组件扫描路径内，
     * 只有经 {@code AutoConfiguration.imports} 显式列举的配置里的 {@code @Bean} 才会成为 bean。
     * 门控与 {@code AiResourceWriteService} 同一个（{@code ai.integration.enabled=true}）——
     * 两者必须同生共死：写路径的 finally 依赖它对账。
     */
    @Bean
    @ConditionalOnMissingBean(TenantBarrierReconciler.class)
    public TenantBarrierReconciler tenantBarrierReconciler(NamedParameterJdbcTemplate jdbc,
                                                           PlatformTransactionManager transactionManager) {
        return new TenantBarrierReconciler(jdbc, transactionManager);
    }

    /**
     * 连接引导端口（C1.1 用途②）由 {@code @EnableConfigurationProperties(ProviderConnections.class)}
     * 注册的 {@link ProviderConnections} 实例**本身**承担（它 implements {@link ProviderConnectionPort}），
     * **刻意不再补一个同类型的 {@code @Bean} 别名**：
     * 那会让容器里出现**两个** {@code ProviderConnectionPort} 候选（本仓实测踩过同一形态），
     * 注入点直接变成 "expected single matching bean but found 2"。
     * 判据：{@code EngineModelAuthorityExclusivityTest} 断言该类型**恰好 1 个** bean。
     */
}
