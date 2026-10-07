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

package com.nageoffer.ai.ragent.rag.runtime.p04;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;

/** 仅测试启动器调用：不注册bean，不序列化配置、令牌或凭证。 */
@Tag("dev")
public final class P04ApplicationEvidence {

    private P04ApplicationEvidence() {
    }

    public static void write(ConfigurableApplicationContext context, String destination) throws Exception {
        if (destination == null || destination.isBlank()) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("contextStarted", context.isActive());
        evidence.put("p04Enabled", context.getEnvironment().getProperty("p04.enabled"));
        List<String> beans = new ArrayList<>();
        List<String> legacyControllers = new ArrayList<>();
        List<String> mqBeans = new ArrayList<>();
        List<String> storageBeans = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null && type.getName().startsWith("com.nageoffer.ai.ragent.")) {
                String className = type.getName();
                if (className.endsWith("Controller") && !className.contains(".rag.runtime.")
                        && !className.contains(".framework.security.")) {
                    legacyControllers.add(name + ":" + className);
                }
                if (className.contains(".mq.") || className.contains("RocketMQ")) {
                    mqBeans.add(name + ":" + className);
                }
                if (className.contains("ObjectStorage") || className.contains("StorageClient")
                        || className.endsWith("StorageInitializer")) {
                    storageBeans.add(name + ":" + className);
                }
            }
            if (type != null && (type.getName().startsWith("com.nageoffer.ai.ragent.framework.security.")
                    || type.getName().startsWith("com.nageoffer.ai.ragent.rag.runtime.")
                    && !type.getName().contains(".runtime.p04."))
                    || List.of("p04Clock", "p04HttpClient", "p04AiRequestIdFilter").contains(name)) {
                beans.add(name);
            }
        }
        evidence.put("p04Beans", beans);
        evidence.put("legacyControllers", legacyControllers);
        evidence.put("mqBeans", mqBeans);
        evidence.put("storageBeans", storageBeans);
        TreeSet<String> routes = new TreeSet<>();
        for (RequestMappingHandlerMapping mappings : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            mappings.getHandlerMethods().keySet().forEach(mapping -> routes.addAll(mapping.getPatternValues()));
        }
        evidence.put("routes", routes);
        List<Map<String, Object>> resources = new ArrayList<>();
        for (String name : List.of("com.nageoffer.ai.ragent.framework.security.DelegationVerifier",
                "com.nageoffer.ai.ragent.framework.security.PlatformAuthorizationClient",
                "com.nageoffer.ai.ragent.framework.security.P04SecurityConfig",
                "com.nageoffer.ai.ragent.framework.security.P04AiExceptionHandler",
                "com.nageoffer.ai.ragent.rag.runtime.RequestHasher",
                "com.nageoffer.ai.ragent.rag.runtime.RunAcceptanceService",
                "com.nageoffer.ai.ragent.rag.runtime.JdbcRunStore",
                "com.nageoffer.ai.ragent.rag.runtime.RunAcceptanceController")) {
            Class<?> type = Class.forName(name);
            List<String> urls = Collections.list(type.getClassLoader().getResources(name.replace('.', '/') + ".class"))
                    .stream().map(Object::toString).toList();
            if (urls.size() != 1) {
                throw new IllegalStateException("duplicate/missing class resources: " + name + " count=" + urls.size());
            }
            resources.add(Map.of("class", name, "codeSource", type.getProtectionDomain().getCodeSource().getLocation().toString(),
                    "resources", urls));
        }
        evidence.put("classOrigins", resources);
        Files.writeString(Path.of(destination), new ObjectMapper().writeValueAsString(evidence));
    }
}
