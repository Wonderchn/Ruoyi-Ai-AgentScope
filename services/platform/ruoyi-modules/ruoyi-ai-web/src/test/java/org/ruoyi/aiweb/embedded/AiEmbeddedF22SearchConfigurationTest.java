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
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.DeepSeekWebSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.WebSearchChannel;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22:5：DeepSeek 联网搜索通道的内嵌条件装配。
 *
 * <p>开态（embedded local × web-search.enabled × provider=deepseek × key 可解析）注册
 * {@link DeepSeekWebSearchChannel}；关态（provider 未设/youcom、enabled=false、key 缺失、
 * 集成总开关关闭、transport=http）零该 bean。默认 provider=youcom 时既有
 * {@link WebSearchChannel} 的行为不变（本测试用 fixture 代表引擎闭包已注册的实例，
 * 断言其在仅有 DeepSeek key 时保持 disabled）。
 */
@Tag("dev")
class AiEmbeddedF22SearchConfigurationTest {

    private static final String[] EMBEDDED_LOCAL = {
            "ai.integration.enabled=true", "ai.integration.transport=local"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedF22SearchConfiguration.class, Fixture.class);

    @Test
    @DisplayName("默认值：provider=youcom、DeepSeek apiUrl 为官方端点（未显式启用时装配结果不变）")
    void defaultsRemainYouCom() {
        SearchChannelProperties properties = new SearchChannelProperties();
        SearchChannelProperties.WebSearch webSearch = properties.getChannels().getWebSearch();
        assertThat(webSearch.getProvider()).isEqualTo("youcom");
        assertThat(webSearch.getDeepseek().getApiKey()).isEmpty();
        assertThat(webSearch.getDeepseek().getApiUrl()).isEqualTo("https://api.deepseek.com/v1/tools/search");
        assertThat(webSearch.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("开态：enabled + provider=deepseek + 配置 key -> 注册 DeepSeek 通道，既有 You.com 通道仍在且 disabled")
    void registersChannelWhenDeepSeekIsSelectedAndKeyIsPresent() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.search.channels.web-search.enabled=true",
                        "rag.search.channels.web-search.provider=deepseek",
                        "rag.search.channels.web-search.deepseek.api-key=test-key",
                        "YDC_API_KEY=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeepSeekWebSearchChannel.class);
                    assertThat(context).hasSingleBean(SearchChannelProperties.class);

                    DeepSeekWebSearchChannel channel = context.getBean(DeepSeekWebSearchChannel.class);
                    assertThat(channel.getName()).isEqualTo("DeepSeekWebSearch");
                    assertThat(channel.getType()).isEqualTo(SearchChannelType.WEB_SEARCH);

                    // 引擎闭包注册的 You.com 通道仍在场；provider=deepseek 且无 YDC key 时保持 disabled。
                    assertThat(context).hasSingleBean(WebSearchChannel.class);
                    assertThat(context.getBean(WebSearchChannel.class).isEnabled(context("q"))).isFalse();
                });
    }

    @Test
    @DisplayName("开态：配置 key 为空时回退 DEEPSEEK_API_KEY（环境键）")
    void registersChannelWithEnvironmentKeyFallback() {
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.search.channels.web-search.enabled=true",
                        "rag.search.channels.web-search.provider=deepseek",
                        "DEEPSEEK_API_KEY=env-deepseek-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeepSeekWebSearchChannel.class);
                });
    }

    @Test
    @DisplayName("关态：provider 未设（youcom）、enabled=false、key 缺失、集成门关闭 -> 零 DeepSeek bean")
    void noDeepSeekBeanWhenAnyGateIsClosed() {
        // provider 未设：默认 youcom
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.search.channels.web-search.enabled=true",
                        "rag.search.channels.web-search.deepseek.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeepSeekWebSearchChannel.class);
                });

        // enabled=false
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.search.channels.web-search.enabled=false",
                        "rag.search.channels.web-search.provider=deepseek",
                        "rag.search.channels.web-search.deepseek.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeepSeekWebSearchChannel.class);
                });

        // key 缺失（显式清空环境键，避免宿主环境污染）
        runner.withPropertyValues(EMBEDDED_LOCAL)
                .withPropertyValues("rag.search.channels.web-search.enabled=true",
                        "rag.search.channels.web-search.provider=deepseek",
                        "DEEPSEEK_API_KEY=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeepSeekWebSearchChannel.class);
                });

        // 集成总开关关闭 / 非 local 传输
        for (String[] gates : new String[][] {
                {"ai.integration.enabled=false", "ai.integration.transport=local"},
                {"ai.integration.enabled=true", "ai.integration.transport=http"}}) {
            runner.withPropertyValues(gates)
                    .withPropertyValues("rag.search.channels.web-search.enabled=true",
                            "rag.search.channels.web-search.provider=deepseek",
                            "rag.search.channels.web-search.deepseek.api-key=test-key")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(DeepSeekWebSearchChannel.class);
                    });
        }
    }

    private static SearchContext context(String question) {
        return SearchContext.builder()
                .originalQuestion(question)
                .budget(RetrievalBudget.uniform(10))
                .build();
    }

    /** 基础设施替身；WebSearchChannel 代表引擎闭包在真实内嵌上下文中已注册的既有通道。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SearchChannelProperties.class)
    static class Fixture {

        @Bean(name = "syncHttpClient")
        OkHttpClient syncHttpClient() {
            return new OkHttpClient();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        WebSearchChannel webSearchChannel(@Qualifier("syncHttpClient") OkHttpClient syncHttpClient,
                                          ObjectMapper objectMapper,
                                          SearchChannelProperties properties) {
            return new WebSearchChannel(syncHttpClient, objectMapper, properties) {
                @Override
                protected String readEnv(String name) {
                    return null;
                }
            };
        }
    }
}
