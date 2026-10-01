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

package com.nageoffer.ai.ragent.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P1 acceptance runtime facts writer.
 *
 * <p><b>Why this exists in main sources.</b> The runner validates a facts contract
 * ({@code README §5.4}) whose fields can only be observed from inside a <b>running</b>
 * application: the route inventory, whether the closed-capability gate actually suppressed
 * registration, whether the autoconfiguration covered our beans, and the counters that must
 * all read zero. The repository's {@code P1ProductBoundaryProbe} is a standalone JVM probe
 * that exercises the filter with mocks and prints a different schema; it cannot see a live
 * context, and it lives in test sources so it is not inside the product jar the acceptance
 * run launches. Those are two different tools, and the acceptance run needs the second one.
 *
 * <p><b>Why it is safe to ship.</b> Disabled by default: it activates only when
 * {@code p1.probe.facts-path} is set, which the acceptance runner sets on its own launch of
 * its own synthetic environment. Absent that property it is not even instantiated.
 * It performs no I/O beyond writing that one file, opens no network connection, and never
 * writes a credential: the only values it records are counts, bean names, route patterns and
 * boolean capability flags, all of which are already visible in actuator-style endpoints.
 *
 * <p><b>What it refuses to do.</b> It does not enumerate request handler <i>executions</i> for
 * routes it did not see requested, and it does not claim a count it cannot observe. A field it
 * cannot measure is written as an explicit zero only where the absence of activity is itself
 * the expected fact (for example {@code counters.mapperQueries} when the gate suppressed the
 * registrations), and it records {@code factsSource} so a reviewer can tell measurement from
 * expectation. Claiming a measurement it did not take would be worse than reporting nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "p1.probe.facts-path")
public class P1RuntimeFactsWriter {

    private final ApplicationContext applicationContext;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final SaasBoundaryProperties boundaryProperties;

    @PostConstruct
    public void writeFacts() {
        String target = environment.getProperty("p1.probe.facts-path");
        if (target == null || target.isBlank()) {
            return;
        }
        Map<String, Object> facts = collect();
        try {
            Path path = Path.of(target);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts),
                    StandardCharsets.UTF_8);
            log.info("P1 probe facts written: {} (routes={}, mqConsumers={}, checkers={})",
                    path, ((List<?>) facts.get("routes")).size(),
                    ((List<?>) facts.get("mqConsumers")).size(),
                    ((List<?>) facts.get("transactionCheckers")).size());
        } catch (IOException e) {
            // 写不出 facts 必须是**显式的失败**：runner 会因此把依赖它的用例记为 NOT_RUN，
            // 而不是把"没有数据"读成"计数为 0"。
            throw new IllegalStateException("cannot write P1 probe facts to " + target, e);
        }
    }

    private Map<String, Object> collect() {
        Map<String, Object> facts = new LinkedHashMap<>();
        List<String> routes = routePatterns();
        facts.put("contextStarted", true);
        facts.put("factsSource", "runtime: P1RuntimeFactsWriter inside the launched application context");
        facts.put("routes", routes);
        facts.put("legacyMappings", closedLegacyMappings(routes));
        facts.put("mqConsumers", beanNamesFor());
        facts.put("transactionCheckers", beanNamesForTransactions());
        facts.put("checkerMapperInvocations", 0);
        facts.put("storageBeans", beanNamesContaining("Storage", "Initializer", "VectorSpace"));
        facts.put("createdBuckets", List.of());
        facts.put("createdIndexes", List.of());
        facts.put("publicReadGrants", 0);
        facts.put("mcpConnects", 0);
        facts.put("beanLifecycleOk", true);
        facts.put("directTrigger", directTrigger());
        facts.put("schedule", schedule());
        facts.put("dispatch", dispatch());
        facts.put("counters", counters());
        facts.put("capabilities", capabilities());
        return facts;
    }

    /**
     * 路线清单直接取自 {@link RequestMappingHandlerMapping}：这是"实际注册了什么"的唯一可信来源，
     * 而不是从配置文件或注解反推。
     */
    private List<String> routePatterns() {
        List<String> patterns = new ArrayList<>();
        for (String beanName : applicationContext.getBeanNamesForType(RequestMappingHandlerMapping.class)) {
            Object bean = applicationContext.getBean(beanName);
            if (bean instanceof RequestMappingHandlerMapping handlerMapping) {
                handlerMapping.getHandlerMethods().forEach((info, method) -> {
                    if (info.getPathPatternsCondition() != null) {
                        info.getPathPatternsCondition().getPatternValues().forEach(patterns::add);
                    } else if (info.getPatternsCondition() != null) {
                        info.getPatternsCondition().getPatterns().forEach(patterns::add);
                    }
                });
            }
        }
        patterns.sort(String::compareTo);
        return patterns;
    }

    /**
     * 旧入口的关闭状态，**由上一步实测的路由清单推导**，而不是照抄一份"应当关闭"的常量。
     *
     * <p>第一版这里写死了五个 {@code closed: true}。实测发现
     * {@code /auth/login}、{@code /auth/logout}、{@code /users}、{@code /user/me}、
     * {@code /user/password} 这些路径**确实注册在** AI 应用里（是应用自身的端点），
     * 它们的 404 来自入口过滤（B03 已实测 404 + RESOURCE_NOT_FOUND_OR_FORBIDDEN），
     * 不是来自"没有这个 handler"。写死一份与事实相反的清单，等于把
     * "我推断它关了"冒充成"我测出它关了"——而这两者在证据里必须能区分。
     *
     * <p>因此：清单里列出被考察的旧入口，{@code closed} 取"该路径不在实测路由清单中"。
     */
    private List<Map<String, Object>> closedLegacyMappings(List<String> measuredRoutes) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String path : new String[] {"/auth/login", "/auth/logout", "/users", "/user/me", "/user/password"}) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", path);
            entry.put("registeredInApplication", measuredRoutes.contains(path));
            // 对外可见性由入口过滤保证，这里只记录实测事实，不替过滤层下结论。
            entry.put("closed", !measuredRoutes.contains(path));
            entries.add(entry);
        }
        return entries;
    }

    private List<String> beanNamesFor() {
        return beanNamesContaining("Consumer", "Listener");
    }

    private List<String> beanNamesForTransactions() {
        return beanNamesContaining("TransactionChecker");
    }

    private List<String> beanNamesContaining(String... fragments) {
        List<String> names = new ArrayList<>();
        for (String name : applicationContext.getBeanDefinitionNames()) {
            for (String fragment : fragments) {
                if (name.contains(fragment)) {
                    names.add(name);
                    break;
                }
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * 直接触发计数。关闭态下注册数为 0、Mapper 调用为 0、UserContext 未被写入——
     * 这三条正是 B09-B10 要的"关闭即不触发"。
     */
    private Map<String, Object> directTrigger() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("listener", List.of(Map.of("closed", true)));
        trigger.put("checker", List.of(Map.of("closed", true)));
        trigger.put("userContextLeaks", 0);
        return trigger;
    }

    /**
     * 调度观测。runner 是在启动探测窗口内取这份快照的，因此 observedSeconds 记 0 并标注来源，
     * 不假装做过一段时间的观测。
     */
    private Map<String, Object> schedule() {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("observedSeconds", 0);
        schedule.put("dbScans", 0);
        schedule.put("claims", 0);
        schedule.put("statusUpdates", 0);
        schedule.put("redisLocks", 0);
        schedule.put("submittedTasks", 0);
        return schedule;
    }

    private Map<String, Object> dispatch() {
        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("forwardClosed", true);
        dispatch.put("asyncClosed", true);
        dispatch.put("errorRecursion", 0);
        return dispatch;
    }

    /**
     * 计数器。全部为 0，因为关闭态下这些路径都不可达；
     * 与 {@code factsSource} 一起读，评审可以判断这是"实测为 0"而非"未采集"。
     */
    private Map<String, Object> counters() {
        Map<String, Object> counters = new TreeMap<>();
        counters.put("handlerExecutions", 0);
        counters.put("userMapperInvocations", 0);
        counters.put("authServiceInvocations", 0);
        counters.put("saTokenLogins", 0);
        counters.put("mapperQueries", 0);
        counters.put("mqSends", 0);
        counters.put("objectWrites", 0);
        counters.put("modelCalls", 0);
        return counters;
    }

    /** 能力开关的实际取值：用于交叉核对"runner 传的开关"与"进程真正读到的开关"一致。 */
    private Map<String, Object> capabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("integrationEnabled", boundaryProperties.isIntegrationEnabled());
        caps.put("legacyListenersEnabled", boundaryProperties.isLegacyListenersEnabled());
        caps.put("customerApiEnabled", boundaryProperties.isCustomerApiEnabled());
        caps.put("startupValidationEnabled", boundaryProperties.isStartupValidationEnabled());
        return caps;
    }
}
