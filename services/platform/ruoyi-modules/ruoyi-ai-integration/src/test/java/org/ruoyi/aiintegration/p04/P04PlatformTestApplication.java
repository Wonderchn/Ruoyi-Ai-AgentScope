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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P0.4 最小 platform 测试应用（Spec §8.4）。
 *
 * <p>只扫描 {@code org.ruoyi.aiintegration}：不装配 sa-token、数据源、Redis、MyBatis 或任何
 * 旧业务入口。它是独立 JVM 的入口，只服务契约实验，<b>不</b>是产品启动类；也刻意不被
 * {@code ruoyi-admin} 依赖，因此内部端点不会因本实验进入真实 dev 应用（Spec §8.3）。
 *
 * <p>启动时用 {@code --spring.config.name=p04platform} 完全取代旧配置文件，
 * 避免读到产品配置。
 */
@SpringBootApplication(scanBasePackages = "org.ruoyi.aiintegration")
public class P04PlatformTestApplication {

    public static void main(String[] args) throws Exception {
        var context = SpringApplication.run(P04PlatformTestApplication.class, args);
        try {
            P04PlatformEvidence.write(context, System.getenv("CONTRACT_ASSEMBLY_EVIDENCE"));
        } catch (Exception e) {
            context.close();
            throw e;
        }
    }
}
