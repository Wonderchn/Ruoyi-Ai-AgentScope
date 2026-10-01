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

package org.ruoyi.aiintegration.p04;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 无受管注解，仅由测试启动器记录装配事实；不输出配置和凭证。 */
public final class P04PlatformEvidence {

    private P04PlatformEvidence() {
    }

    public static void write(ConfigurableApplicationContext context, String destination) throws Exception {
        if (destination == null || destination.isBlank()) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("contextStarted", context.isActive());
        evidence.put("p04Enabled", context.getEnvironment().getProperty("p04.enabled"));
        var beans = new ArrayList<String>();
        var legacyControllers = new ArrayList<String>();
        var mqBeans = new ArrayList<String>();
        var storageBeans = new ArrayList<String>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null && type.getName().startsWith("org.ruoyi.")) {
                String className = type.getName();
                if (className.endsWith("Controller") && !className.startsWith("org.ruoyi.aiintegration.")) {
                    legacyControllers.add(name + ":" + className);
                }
                if (className.contains(".mq.") || className.contains("RocketMQ")) {
                    mqBeans.add(name + ":" + className);
                }
                if (className.contains("Oss") || className.contains("OSS") || className.contains("Storage")) {
                    storageBeans.add(name + ":" + className);
                }
            }
            if (type != null && type.getName().startsWith("org.ruoyi.aiintegration.")
                    && !type.getName().startsWith("org.ruoyi.aiintegration.p04.")
                    || List.of("p04Clock", "p04RequestIdFilter", "p04DelegationSigningKeys").contains(name)) {
                beans.add(name);
            }
        }
        evidence.put("p04Beans", beans);
        evidence.put("legacyControllers", legacyControllers);
        evidence.put("mqBeans", mqBeans);
        evidence.put("storageBeans", storageBeans);
        var routes = new TreeSet<String>();
        for (var mappings : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            mappings.getHandlerMethods().keySet().forEach(mapping -> routes.addAll(mapping.getPatternValues()));
        }
        evidence.put("routes", routes);
        Files.writeString(Path.of(destination), new ObjectMapper().writeValueAsString(evidence));
    }
}
