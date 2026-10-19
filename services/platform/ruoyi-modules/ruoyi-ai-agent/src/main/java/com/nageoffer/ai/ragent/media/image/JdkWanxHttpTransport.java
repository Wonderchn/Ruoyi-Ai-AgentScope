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

package com.nageoffer.ai.ragent.media.image;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 生产传输：JDK HttpClient（不跟随重定向、限时、限体长）。
 *
 * <p>不引入新的 HTTP 客户端依赖；不打印任何请求头或响应正文。
 */
public class JdkWanxHttpTransport implements WanxHttpTransport {

    private final HttpClient client;
    private final int requestTimeoutMs;

    public JdkWanxHttpTransport(int connectTimeoutMs, int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Reply send(String method, String url, Map<String, String> headers, byte[] body, long maxBytes)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1, requestTimeoutMs)));
        headers.forEach(builder::header);
        if ("GET".equalsIgnoreCase(method)) {
            builder.GET();
        } else {
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("media provider request interrupted", e);
        }
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = readBounded(in, maxBytes);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        return new Reply(response.statusCode(), bytes, contentType);
    }

    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        long limit = Math.max(1, maxBytes);
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int read;
        long total = 0;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw new IOException("media provider response exceeds the configured limit");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
