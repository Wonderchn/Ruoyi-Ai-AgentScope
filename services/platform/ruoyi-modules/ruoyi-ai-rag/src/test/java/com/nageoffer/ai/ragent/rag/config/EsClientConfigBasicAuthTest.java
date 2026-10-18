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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22:2 真机第二缺口：实验室 ES 开启 xpack.security 后，无认证请求返回 401。
 *
 * <p>{@link EsClientConfig} 在 {@code rag.keyword.es.username}/{@code password} 同时非空时
 * 必须启用 Basic Auth；为空时保持现状（不注册凭据提供者、不发 Authorization 头）。
 *
 * <p>测试用本机临时 HTTP 服务验证真实请求头，而不是断言内部字段：
 * 有凭据时服务端先回 401 挑战，客户端带 {@code Authorization: Basic ...} 重试成功；
 * 无凭据时任何请求都不得携带该头。凭据值在运行时随机生成，不写死任何真实凭据。
 */
@Tag("dev")
class EsClientConfigBasicAuthTest {

    @Test
    @DisplayName("配置 username/password：401 挑战后带 Basic Authorization 重试")
    void sendsBasicAuthorizationWhenCredentialsAreConfigured() throws Exception {
        String username = "f22-user-" + UUID.randomUUID();
        String password = "f22-pass-" + UUID.randomUUID();
        AtomicReference<String> authorization = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header != null) {
                authorization.compareAndSet(null, header);
            }
            if (header == null) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"f22-test\"");
                respond(exchange, 401,
                        "{\"error\":{\"type\":\"security_exception\",\"reason\":\"authentication required\"},"
                                + "\"status\":401}");
            } else {
                respond(exchange, 200, INFO_BODY);
            }
        });
        server.start();
        try {
            KeywordProperties properties = bind(Map.of(
                    "rag.keyword.es.uris", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "rag.keyword.es.username", username,
                    "rag.keyword.es.password", password));

            ElasticsearchClient client = new EsClientConfig().elasticsearchClient(properties);
            try {
                assertThat(client.info().clusterName()).isEqualTo("f22-mock-cluster");
            } finally {
                client.close();
            }

            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            assertThat(authorization)
                    .as("有凭据时必须带 Basic Authorization（401 挑战后重试）")
                    .hasValue(expected);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("未配置凭据：不发 Authorization 头（与现状逐字节等价）")
    void omitsAuthorizationWhenCredentialsAreAbsent() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header != null) {
                authorization.compareAndSet(null, header);
            }
            respond(exchange, 200, INFO_BODY);
        });
        server.start();
        try {
            KeywordProperties properties = bind(Map.of(
                    "rag.keyword.es.uris", "http://127.0.0.1:" + server.getAddress().getPort()));

            ElasticsearchClient client = new EsClientConfig().elasticsearchClient(properties);
            try {
                assertThat(client.info().clusterName()).isEqualTo("f22-mock-cluster");
            } finally {
                client.close();
            }

            assertThat(authorization)
                    .as("无凭据时不得附加 Authorization 头")
                    .hasValue(null);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("环境变量 RAG_KEYWORD_ES_USERNAME/PASSWORD 经 relaxed binding 绑定")
    void bindsEnvironmentStyleCredentials() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("f22-test-env",
                Map.of("RAG_KEYWORD_ES_USERNAME", "env-user",
                        "RAG_KEYWORD_ES_PASSWORD", "env-pass")));
        // 前缀下至少有一个普通属性，Binder 才会枚举子属性；
        // username/password 只存在于环境变量源里，正好验证 relaxed binding 的映射。
        environment.getPropertySources().addLast(new MapPropertySource("f22-test-uris",
                Map.of("rag.keyword.es.uris", "http://127.0.0.1:9200")));

        KeywordProperties properties = Binder.get(environment)
                .bind("rag.keyword", Bindable.of(KeywordProperties.class))
                .get();

        assertThat(properties.getEs().getUsername()).isEqualTo("env-user");
        assertThat(properties.getEs().getPassword()).isEqualTo("env-pass");
    }

    /**
     * 最小 Elasticsearch 响应：产品头是客户端 9.x 的硬性校验，
     * info 体提供 {@code cluster_name} 供调用方断言请求确实完成。
     */
    private static final String INFO_BODY = """
            {"name":"f22-mock","cluster_name":"f22-mock-cluster","cluster_uuid":"f22-mock-uuid",
             "version":{"number":"9.4.2","build_flavor":"default","build_type":"mock","build_hash":"f22mock",
                        "build_date":"2026-01-01T00:00:00.000Z","build_snapshot":false,
                        "lucene_version":"10.0.0","minimum_wire_compatibility_version":"8.0.0",
                        "minimum_index_compatibility_version":"8.0.0"},
             "tagline":"You Know, for Search"}
            """;

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static KeywordProperties bind(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("f22-test", values));
        return Binder.get(environment)
                .bind("rag.keyword", Bindable.of(KeywordProperties.class))
                .get();
    }
}
