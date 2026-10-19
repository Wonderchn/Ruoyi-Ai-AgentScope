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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.DeepSeekWebSearchChannel;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * F22:5：内嵌形态的 DeepSeek 联网搜索通道装配。
 *
 * <p><b>门控。</b>{@link ConditionalOnEmbeddedLocal}（{@code ai.integration.enabled=true} ×
 * {@code transport=local}）× {@code rag.search.channels.web-search.enabled=true} ×
 * {@code provider=deepseek} × key 可解析（配置 {@code deepseek.api-key} 或环境变量
 * {@code DEEPSEEK_API_KEY}）。任一不满足即<b>零 bean、零外呼</b>；默认 provider=youcom
 * 时本类不激活，既有 {@code WebSearchChannel}（引擎闭包注册）行为逐字节不变。
 *
 * <p><b>为什么 key 也作为装配条件。</b>只开开关但没有任何 key 时注册一个永远
 * {@code isEnabled=false} 的通道 bean 没有运行期收益，反而让"配置看起来接上了"。
 * 这里与运行期门保持一致：不可用的通道连 bean 都不存在。
 *
 * <p><b>HTTP 客户端。</b>沿用内嵌模型组提供的 {@code syncHttpClient}（显式 {@code @Qualifier}，
 * 避免按类型拿到无读超时的流式客户端），与 LightRAG/图谱管理面同款。
 *
 * <p>本类需由 T0 追加到
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}：
 * {@code org.ruoyi.aiweb.embedded.AiEmbeddedF22SearchConfiguration}
 */
@AutoConfiguration
@ConditionalOnEmbeddedLocal
@ConditionalOnProperty(prefix = "rag.search.channels.web-search", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.search.channels.web-search", name = "provider", havingValue = "deepseek")
@Conditional(DeepSeekSearchKeyPresentCondition.class)
@EnableConfigurationProperties(SearchChannelProperties.class)
public class AiEmbeddedF22SearchConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeepSeekWebSearchChannel deepSeekWebSearchChannel(
            @Qualifier("syncHttpClient") OkHttpClient syncHttpClient,
            ObjectMapper objectMapper,
            SearchChannelProperties properties) {
        return new DeepSeekWebSearchChannel(syncHttpClient, objectMapper, properties);
    }
}

/**
 * DeepSeek key 可解析性条件：配置 {@code deepseek.api-key} 非空，或环境变量
 * {@code DEEPSEEK_API_KEY} 非空（与通道运行期的 key 解析同源）。
 *
 * <p>直读 {@link Environment} 而不先绑定 {@link SearchChannelProperties}：条件求值早于
 * 配置属性 bean 的创建，绑定结果在此阶段不可依赖。
 */
class DeepSeekSearchKeyPresentCondition extends SpringBootCondition {

    static final String CONFIGURED_KEY = "rag.search.channels.web-search.deepseek.api-key";
    static final String ENV_KEY = "DEEPSEEK_API_KEY";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        String configured = environment.getProperty(CONFIGURED_KEY);
        String fromEnvironment = environment.getProperty(ENV_KEY);
        if (StringUtils.hasText(configured) || StringUtils.hasText(fromEnvironment)) {
            return ConditionOutcome.match("DeepSeek search key is resolvable");
        }
        return ConditionOutcome.noMatch(
                "DeepSeek search key missing (set " + CONFIGURED_KEY + " or " + ENV_KEY + ")");
    }
}
