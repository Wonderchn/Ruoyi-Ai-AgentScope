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

package org.ruoyi.aiweb.embedded;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * WP-031B：把 AI 侧 MyBatis Mapper 注册进 platform 应用。
 *
 * <p><b>为什么需要单独一组。</b>platform 的扫描入口是
 * {@code MybatisPlusConfig} 上的 {@code @MapperScan("${mybatis-plus.mapperPackage}")}，
 * 而该属性的值是 {@code org.ruoyi.**.mapper}——AI 侧 Mapper 全在
 * {@code com.nageoffer.ai.ragent.*.dao.mapper} 下，**一个都不在扫描范围内**。
 * 也就是说 WP-025/WP-026 适配好的实体与注解 SQL，在内嵌形态里此前根本不会被实例化：
 * 任何注入它们的服务都会以"缺 bean"启动失败，或者（更糟）绕过它们走别的路径。
 *
 * <p><b>为什么不直接往 {@code mapperPackage} 里加第二个通配。</b>该属性经
 * {@code ClassPathBeanDefinitionScanner.resolveBasePackage} 解析后会被当作<b>单个</b>包路径，
 * 逗号不会被拆成多包（多包只有在 {@code @MapperScan} 的数组属性里才有意义）。
 * 写 {@code "org.ruoyi.**.mapper,com.nageoffer...**"} 的结果是两段都扫不到——
 * 静默失去全部 Mapper。所以这里用显式数组，并且由
 * {@code AiEmbeddedMapperConfigurationTest} 用真实容器证明 bean 真的注册了。
 *
 * <p>包清单与 {@code ruoyi-ai-rag} 测试启动类 {@code TestRagentApplication} 的
 * {@code @MapperScan} 保持一致（那里少 {@code agent.dao.mapper}，因为该模块测试类路径不含
 * agent 模块）；这里含 agent，因为 platform 应用装配了全部 AI 模块。
 *
 * <p>与 {@code MybatisPlusConfig} 的既有边界不冲突：本类只注册 Mapper 扫描，
 * <b>不</b>注册第二个 {@code MybatisPlusInterceptor}/{@code MetaObjectHandler}/
 * {@code IdentifierGenerator}——那三样仍只有 platform 唯一一份。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
@MapperScan({
        "com.nageoffer.ai.ragent.rag.dao.mapper",
        "com.nageoffer.ai.ragent.ingestion.dao.mapper",
        "com.nageoffer.ai.ragent.knowledge.dao.mapper",
        "com.nageoffer.ai.ragent.user.dao.mapper",
        "com.nageoffer.ai.ragent.audit.dao.mapper",
        "com.nageoffer.ai.ragent.sample.dao.mapper",
        "com.nageoffer.ai.ragent.agent.dao.mapper",
        "com.nageoffer.ai.ragent.flow.dao.mapper"
})
public class AiEmbeddedMapperConfiguration {
}
