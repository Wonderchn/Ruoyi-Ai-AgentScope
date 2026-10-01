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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P0.4 最小 AI 测试应用（Spec §8.4）。
 *
 * <p>只扫描两个包：{@code ...framework.security} 与 {@code ...rag.runtime}。因此
 * {@code framework.web.GlobalExceptionHandler}、{@code system} 模块的 sa-token 登录链路
 * （{@code SaTokenConfig}/{@code AuthController}）以及任何旧业务入口都<b>不会</b>被装配，
 * 即使它们的类因 {@code rag -> system -> framework} 依赖而位于 classpath 上。
 *
 * <p>这不是产品启动类（产品启动类是 {@code bootstrap} 模块的 {@code RagentApplication}）；
 * 它以 {@code --spring.config.name=p04ai} 启动，从而完全不读产品配置文件。
 */
@SpringBootApplication(scanBasePackages = {
        "com.nageoffer.ai.ragent.framework.security",
        "com.nageoffer.ai.ragent.rag.runtime"
})
public class P04AiTestApplication {

    public static void main(String[] args) throws Exception {
        var context = SpringApplication.run(P04AiTestApplication.class, args);
        try {
            P04ApplicationEvidence.write(context, System.getenv("CONTRACT_ASSEMBLY_EVIDENCE"));
        } catch (Exception e) {
            context.close();
            throw e;
        }
    }
}
