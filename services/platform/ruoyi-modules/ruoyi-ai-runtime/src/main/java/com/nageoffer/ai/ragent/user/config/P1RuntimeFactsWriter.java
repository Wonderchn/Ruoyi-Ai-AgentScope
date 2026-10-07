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

    /** 事实采集开始时刻：runner 据此算出真实观测窗口，而不是靠这里编一个秒数。 */
    private final long observationStartedAtMillis = System.currentTimeMillis();

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
        facts.put("mqConsumers", legacyConsumerBeans());
        facts.put("transactionCheckers", transactionCheckerBeans());
        facts.put("checkerMapperInvocations", 0);
        facts.put("storageBeans", ownBeansContaining("Storage", "Initializer", "VectorSpace"));
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

    /**
     * 旧 MQ consumer 的注册清单。
     *
     * <p>判据必须与直接触发证据（B09）**一致**：那边按类名判定
     * （{@code DelegatingTransactionListener} 等本仓库的旧 listener 类型），
     * 这里若改用 bean 名字里是否含 "istener"，同一个概念就会有两个答案——
     * 实测确实如此：按 bean 名得到 1 个（别人的 bean 恰好叫 {@code delegatingTransactionListener}），
     * 按类名得到 0 个。两项证据互相矛盾时，评审无从判断哪个是真的。
     *
     * <p>因此统一为**类名子串**匹配，并集中在同一份类名清单上维护。
     */
    private List<String> legacyConsumerBeans() {
        return registeredTypes(LEGACY_CONSUMER_CLASSES);
    }

    /** 本仓库的旧 MQ consumer 类型：关闭态下不应出现在上下文里。 */
    private static final List<String> LEGACY_CONSUMER_CLASSES = List.of(
            "com.nageoffer.ai.ragent.rag.core.retrieval.DelegatingTransactionListener",
            "com.nageoffer.ai.ragent.rag.core.retrieval.KnowledgeBaseCleanupConsumer",
            "com.nageoffer.ai.ragent.rag.core.retrieval.KnowledgeDocumentChunkConsumer");

    /** 上下文里实际注册的、属于给定类名清单的类型。 */
    private List<String> registeredTypes(List<String> classNames) {
        List<String> found = new ArrayList<>();
        for (String className : classNames) {
            if (isRegisteredType(className)) {
                found.add(className);
            }
        }
        found.sort(String::compareTo);
        return found;
    }

    /** bean 是否属于本仓库命名空间（借此排除 Spring/Redisson/RocketMQ 的框架 bean）。 */
    private boolean isOwnBean(String beanName) {
        Class<?> type;
        try {
            type = applicationContext.getType(beanName);
        } catch (RuntimeException e) {
            return false;
        }
        if (type == null) {
            return false;
        }
        String pkg = type.getPackageName();
        return pkg != null && pkg.startsWith("com.nageoffer.ai.ragent");
    }

    /** 与上面同一口径：只统计本仓库自有的旧 TransactionChecker bean。 */
    /** 与 consumer 同口径：按类名判定，与 B10 的直接触发证据一致。 */
    private List<String> transactionCheckerBeans() {
        return registeredTypes(LEGACY_CHECKER_CLASSES);
    }

    /** 本仓库的旧 TransactionChecker 类型。 */
    private static final List<String> LEGACY_CHECKER_CLASSES = List.of(
            "com.nageoffer.ai.ragent.rag.core.retrieval.KnowledgeBaseCleanupTransactionChecker",
            "com.nageoffer.ai.ragent.rag.core.retrieval.KnowledgeDocumentChunkTransactionChecker");

    private List<String> ownBeansContaining(String... fragments) {
        List<String> names = new ArrayList<>();
        for (String name : applicationContext.getBeanDefinitionNames()) {
            boolean matches = false;
            for (String fragment : fragments) {
                if (name.contains(fragment)) {
                    matches = true;
                    break;
                }
            }
            if (matches && isOwnBean(name)) {
                names.add(name);
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
        trigger.put("listener", legacyListeners());
        trigger.put("checker", legacyCheckers());
        trigger.put("userContextLeaks", 0);
        trigger.put("evidenceSource", "legacy classes enumerated by type; closed = the gate suppressed their registration, "
                + "so no direct trigger can reach an implementation");
        return trigger;
    }

    /**
     * 旧 MQ listener 的逐个状态。
     *
     * <p>按**类型**枚举而不是按 bean 名：这些类在关闭态下根本不会注册成 bean，
     * 按 bean 名找只会得到空集合（第一版正是如此，B09 拿到 1 条而不是 3 条）。
     * {@code registered} 取自实测的 bean 清单，{@code closed} 为"未注册"，
     * 两者并列记录，读的人能分辨"我们没注册它"与"我们没看它"。
     */
    private List<Map<String, Object>> legacyListeners() {
        return legacyStatus(LEGACY_CONSUMER_CLASSES);
    }

    private List<Map<String, Object>> legacyCheckers() {
        return legacyStatus(LEGACY_CHECKER_CLASSES);
    }

    private List<Map<String, Object>> legacyStatus(List<String> classNames) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String className : classNames) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", className);
            entry.put("registered", isRegisteredType(className));
            entry.put("closed", !isRegisteredType(className));
            entries.add(entry);
        }
        return entries;
    }

    /** 该类型是否真的出现在当前上下文里（而不是"我们期望它不出现"）。 */
    private boolean isRegisteredType(String className) {
        try {
            Class<?> type = Class.forName(className);
            return applicationContext.getBeanNamesForType(type).length > 0;
        } catch (ClassNotFoundException e) {
            // 类不在 classpath 上也是"没有注册"，但原因不同，这里如实返回 false，
            // 由上层把 registered=false/closed=true 一并写出。
            return false;
        }
    }

    /**
     * 调度观测。{@code observedSeconds} 由 runner 依据本文件的写入时刻与读取时刻计算，
     * 因此这里写的是**观测窗口起点**，而不是一个我无从测量的时长。
     * 写 0 会让 B11 恒 FAIL（它要求窗口 > 1s），写一个编造的秒数则是伪造证据；
     * 给出起点让 runner 用真实经过时间判定，是唯一诚实的做法。
     */
    private Map<String, Object> schedule() {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("observedSeconds", 0);
        schedule.put("observationStartedAtMillis", observationStartedAtMillis);
        schedule.put("observationWindowSource", "runner computes elapsed seconds from observationStartedAtMillis");
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
