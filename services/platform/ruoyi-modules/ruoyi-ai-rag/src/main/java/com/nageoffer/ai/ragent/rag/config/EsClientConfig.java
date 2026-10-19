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

package com.nageoffer.ai.ragent.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Arrays;

/**
 * Elasticsearch 客户端配置
 */
@Slf4j
@Configuration
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnProperty(name = "rag.keyword.type", havingValue = "es")
public class EsClientConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(KeywordProperties keywordProperties) {
        String uris = keywordProperties.getEs().getUris();
        URI[] hosts = Arrays.stream(StringUtils.commaDelimitedListToStringArray(uris))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(URI::create)
                .toArray(URI[]::new);

        Rest5ClientBuilder builder = Rest5Client.builder(hosts);

        // F22:2 真机第二缺口：开启 xpack.security 的 ES 对未认证请求返回 401。
        // 仅当用户名与密码同时非空才注册凭据（用户名/密码是部署注入的环境变量，
        // 这里不记录任何凭据值）；两者为空时构建路径与既有实现完全一致。
        String username = keywordProperties.getEs().getUsername();
        String password = keywordProperties.getEs().getPassword();
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            // HC5 没有 AuthScope.ANY 常量：null host + -1 port 即"任意目标"通配。
            credentialsProvider.setCredentials(new AuthScope(null, -1),
                    new UsernamePasswordCredentials(username, password.toCharArray()));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        Rest5Client restClient = builder.build();
        Rest5ClientTransport transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        log.info("Elasticsearch 关键词检索客户端已初始化, uris={}", uris);
        return new ElasticsearchClient(transport);
    }
}
