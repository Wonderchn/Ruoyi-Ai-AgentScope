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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.entity.image.ImageContext;
import org.ruoyi.common.chat.entity.media.MediaGenerationResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F19:1 图片适配器协议/失败语义（DashScope 文生图异步协议）。
 *
 * <p>全部使用注入的假传输：不访问网络、不读取真实凭据。测试 key 在运行时随机生成，
 * 不硬编码任何真实值。
 */
@Tag("dev")
class TongYiWanxImageGenerationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingTransport transport = new RecordingTransport();
    private MediaProperties properties;
    private TongYiWanxImageGenerationService service;
    private String syntheticKey;

    @BeforeEach
    void setUp() {
        syntheticKey = "synthetic-" + UUID.randomUUID();
        properties = new MediaProperties();
        properties.getImage().setApiKey(syntheticKey);
        properties.getImage().setModelName("wanx-test");
        properties.getImage().setApiHost("https://dashscope.test");
        properties.getImage().setAllowedResultHosts("aliyuncs.com");
        properties.getImage().setPollIntervalMs(1);
        properties.getImage().setPollTimeoutMs(2000);
        service = new TongYiWanxImageGenerationService(properties, transport, mapper);
    }

    @Test
    void submitUsesAsyncDashScopeProtocolWithoutLeakingKey() throws Exception {
        transport.replies.add(reply(200, "{\"request_id\":\"r-1\",\"output\":{\"task_id\":\"t-1\",\"task_status\":\"PENDING\"}}", "application/json"));

        String taskId = service.submit(new MediaImageRequest("wanx-test", "a red fox", "1024*1024", 7));

        assertThat(taskId).isEqualTo("t-1");
        Recorded recorded = transport.records.get(0);
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.url()).isEqualTo("https://dashscope.test/api/v1/services/aigc/text2image/image-synthesis");
        assertThat(recorded.headers().get("X-DashScope-Async")).isEqualTo("enable");
        assertThat(recorded.headers().get("Authorization")).isEqualTo("Bearer " + syntheticKey);
        JsonNode body = mapper.readTree(recorded.body());
        assertThat(body.path("model").asText()).isEqualTo("wanx-test");
        assertThat(body.path("input").path("prompt").asText()).isEqualTo("a red fox");
        assertThat(body.path("parameters").path("size").asText()).isEqualTo("1024*1024");
        assertThat(body.path("parameters").path("n").asInt()).isEqualTo(1);
        assertThat(body.path("parameters").path("seed").asInt()).isEqualTo(7);
        assertThat(service.getProviderName()).isEqualTo("Tongyiwanx");
    }

    @Test
    void pollMapsSucceededAndCarriesUsage() {
        transport.replies.add(reply(200, "{\"request_id\":\"r-2\",\"output\":{\"task_id\":\"t-1\","
                + "\"task_status\":\"SUCCEEDED\",\"results\":[{\"url\":\"https://dashscope-result.oss-cn-beijing.aliyuncs.com/x.png\"}]},"
                + "\"usage\":{\"image_count\":1}}", "application/json"));

        MediaImagePoll poll = service.poll("t-1");

        assertThat(poll.status()).isEqualTo("SUCCEEDED");
        assertThat(poll.imageUrl()).contains("aliyuncs.com");
        assertThat(poll.providerRequestId()).isEqualTo("r-2");
        assertThat(poll.usage()).containsEntry("image_count", 1L);
        assertThat(transport.records.get(0).method()).isEqualTo("GET");
        assertThat(transport.records.get(0).url()).isEqualTo("https://dashscope.test/api/v1/tasks/t-1");
    }

    @Test
    void pollMapsDefinitiveFailureAndCancellation() {
        transport.replies.add(reply(200, "{\"output\":{\"task_status\":\"FAILED\",\"fail_reason\":\"content policy\"}}", "application/json"));
        MediaImagePoll failed = service.poll("t-1");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("PROVIDER_FAILED");
        assertThat(failed.message()).isEqualTo("content policy");

        transport.replies.add(reply(200, "{\"output\":{\"task_status\":\"CANCELLED\"}}", "application/json"));
        assertThat(service.poll("t-2").status()).isEqualTo("CANCELED");
    }

    @Test
    void unknownProviderStatusIsNotTreatedAsSuccess() {
        transport.replies.add(reply(200, "{\"output\":{\"task_status\":\"MYSTERY\"}}", "application/json"));
        assertThat(service.poll("t-1").status()).isEqualTo("UNKNOWN");
    }

    @Test
    void submitAuthFailureIsDefinitiveAndNeverEchoesTheKey() {
        transport.replies.add(reply(401, "{\"message\":\"Invalid API-key provided.\"}", "application/json"));

        assertThatThrownBy(() -> service.submit(new MediaImageRequest("wanx-test", "p", "1024*1024", null)))
                .isInstanceOfSatisfying(MediaProviderRejectedException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("PROVIDER_AUTH_FAILED");
                    assertThat(ex.getMessage()).doesNotContain(syntheticKey);
                });
    }

    @Test
    void submitWithoutTaskIdIsIndeterminate() {
        transport.replies.add(reply(200, "{\"request_id\":\"r-3\",\"output\":{}}", "application/json"));

        assertThatThrownBy(() -> service.submit(new MediaImageRequest("wanx-test", "p", "1024*1024", null)))
                .isInstanceOf(MediaProviderUnavailableException.class);
    }

    @Test
    void transportFailureIsIndeterminateNotRejected() {
        transport.failure = new IOException("timeout");

        assertThatThrownBy(() -> service.submit(new MediaImageRequest("wanx-test", "p", "1024*1024", null)))
                .isInstanceOf(MediaProviderUnavailableException.class);
    }

    @Test
    void downloadOnlyAcceptsHttpsAllowlistedHosts() {
        byte[] png = png();
        transport.replies.add(reply(200, png, "image/png"));

        assertThat(service.download("https://dashscope-result.oss-cn-beijing.aliyuncs.com/x.png", 4096))
                .isEqualTo(png);

        assertThatThrownBy(() -> service.download("http://dashscope-result.oss-cn-beijing.aliyuncs.com/x.png", 4096))
                .isInstanceOf(MediaProviderRejectedException.class);
        assertThatThrownBy(() -> service.download("https://evil.example/x.png", 4096))
                .isInstanceOf(MediaProviderRejectedException.class);
        assertThatThrownBy(() -> service.download("https://user:pass@dashscope-result.aliyuncs.com/x.png", 4096))
                .isInstanceOf(MediaProviderRejectedException.class);
    }

    @Test
    void downloadRejectsNonImageAndOversizedBodies() {
        transport.replies.add(reply(200, "<html>nope</html>".getBytes(StandardCharsets.UTF_8), "text/html"));
        assertThatThrownBy(() -> service.download("https://bucket.aliyuncs.com/x", 4096))
                .isInstanceOf(MediaProviderRejectedException.class);

        transport.replies.add(reply(200, png(), "image/png"));
        assertThatThrownBy(() -> service.download("https://bucket.aliyuncs.com/x", 2))
                .isInstanceOf(MediaProviderRejectedException.class);
    }

    @Test
    void startImageGenerationReturnsPendingTaskAndSyncGeneratePollsToSuccess() {
        transport.replies.add(reply(200, "{\"output\":{\"task_id\":\"t-9\",\"task_status\":\"PENDING\"}}", "application/json"));
        ChatModelVo model = new ChatModelVo();
        model.setModelName("wanx-test");
        MediaGenerationResponse started = service.startImageGeneration(ImageContext.builder()
                .chatModelVo(model).prompt("p").size("1024*1024").build());
        assertThat(started.getId()).isEqualTo("t-9");
        assertThat(started.getStatus()).isEqualTo("pending");

        transport.replies.add(reply(200, "{\"output\":{\"task_id\":\"t-9\",\"task_status\":\"PENDING\"}}", "application/json"));
        transport.replies.add(reply(200, "{\"output\":{\"task_id\":\"t-9\",\"task_status\":\"SUCCEEDED\","
                + "\"results\":[{\"url\":\"https://bucket.aliyuncs.com/y.png\"}]}}", "application/json"));
        String url = service.generateImage(ImageContext.builder()
                .chatModelVo(model).prompt("p").size("1024*1024").build());
        assertThat(url).isEqualTo("https://bucket.aliyuncs.com/y.png");
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    }

    private static WanxHttpTransport.Reply reply(int status, String body, String contentType) {
        return new WanxHttpTransport.Reply(status, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static WanxHttpTransport.Reply reply(int status, byte[] body, String contentType) {
        return new WanxHttpTransport.Reply(status, body, contentType);
    }

    private record Recorded(String method, String url, Map<String, String> headers, String body) {
    }

    private static final class RecordingTransport implements WanxHttpTransport {

        private final List<Recorded> records = new ArrayList<>();
        private final Deque<Reply> replies = new ArrayDeque<>();
        private IOException failure;

        @Override
        public Reply send(String method, String url, Map<String, String> headers, byte[] body, long maxBytes)
                throws IOException {
            if (failure != null) {
                throw failure;
            }
            records.add(new Recorded(method, url, new LinkedHashMap<>(headers),
                    body == null ? null : new String(body, StandardCharsets.UTF_8)));
            if (replies.isEmpty()) {
                throw new IOException("no queued reply");
            }
            return replies.removeFirst();
        }
    }
}
