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

package com.nageoffer.ai.ragent.media.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.media.config.MediaProperties;
import com.nageoffer.ai.ragent.media.image.MediaProviderRejectedException;
import com.nageoffer.ai.ragent.media.image.MediaProviderUnavailableException;
import com.nageoffer.ai.ragent.media.image.WanxHttpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
 * F19:2 视频适配器协议/失败语义（DashScope 文生视频异步协议）。
 *
 * <p>全部使用注入的假传输：不访问网络、不读取真实凭据。
 * 协议：POST video-synthesis + X-DashScope-Async → output.task_id；
 * GET /api/v1/tasks/{id} → output.task_status / output.video_url。
 */
@Tag("dev")
class DashScopeVideoGenerationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingTransport transport = new RecordingTransport();
    private MediaProperties properties;
    private DashScopeVideoGenerationService service;
    private String syntheticKey;

    @BeforeEach
    void setUp() {
        syntheticKey = "synthetic-" + UUID.randomUUID();
        properties = new MediaProperties();
        properties.getVideo().setApiKey(syntheticKey);
        properties.getVideo().setModelName("wanx-test");
        properties.getVideo().setApiHost("https://dashscope.test");
        properties.getVideo().setAllowedResultHosts("aliyuncs.com");
        properties.getVideo().setDefaultSize("1280*720");
        properties.getVideo().setDefaultDurationSeconds(5);
        service = new DashScopeVideoGenerationService(properties, transport, mapper);
    }

    @Test
    void submitUsesAsyncDashScopeVideoProtocolWithoutLeakingKey() throws Exception {
        transport.replies.add(reply(200, "{\"request_id\":\"r-1\",\"output\":{\"task_id\":\"t-1\","
                + "\"task_status\":\"PENDING\"}}", "application/json"));

        String taskId = service.submit(new MediaVideoRequest("wanx-test", "a cat running", "1280*720", 5, null));

        assertThat(taskId).isEqualTo("t-1");
        Recorded recorded = transport.records.get(0);
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.url())
                .isEqualTo("https://dashscope.test/api/v1/services/aigc/video-generation/video-synthesis");
        assertThat(recorded.headers().get("X-DashScope-Async")).isEqualTo("enable");
        assertThat(recorded.headers().get("Authorization")).isEqualTo("Bearer " + syntheticKey);
        JsonNode body = mapper.readTree(recorded.body());
        assertThat(body.path("model").asText()).isEqualTo("wanx-test");
        assertThat(body.path("input").path("prompt").asText()).isEqualTo("a cat running");
        assertThat(body.path("parameters").path("size").asText()).isEqualTo("1280*720");
        assertThat(body.path("parameters").path("duration").asInt()).isEqualTo(5);
        assertThat(recorded.body()).doesNotContain("quality");
    }

    @Test
    void submitWithoutTaskIdIsIndeterminateNeverResubmittedByAdapter() {
        transport.replies.add(reply(200, "{\"request_id\":\"r-2\",\"output\":{}}", "application/json"));

        assertThatThrownBy(() -> service.submit(
                new MediaVideoRequest("wanx-test", "p", "1280*720", 5, null)))
                .isInstanceOf(MediaProviderUnavailableException.class);
    }

    @Test
    void authFailureIsDefinitiveAndNeverEchoesTheKey() {
        transport.replies.add(reply(401, "{\"message\":\"Invalid API-key provided.\"}", "application/json"));

        assertThatThrownBy(() -> service.submit(
                new MediaVideoRequest("wanx-test", "p", "1280*720", 5, null)))
                .isInstanceOfSatisfying(MediaProviderRejectedException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("PROVIDER_AUTH_FAILED");
                    assertThat(ex.getMessage()).doesNotContain(syntheticKey);
                });
    }

    @Test
    void transportFailureIsIndeterminateNotRejected() {
        transport.failure = new IOException("timeout");

        assertThatThrownBy(() -> service.submit(
                new MediaVideoRequest("wanx-test", "p", "1280*720", 5, null)))
                .isInstanceOf(MediaProviderUnavailableException.class);
    }

    @Test
    void pollMapsSucceededWithVideoUrlAndUsage() {
        transport.replies.add(reply(200, "{\"request_id\":\"r-2\",\"output\":{\"task_id\":\"t-1\","
                + "\"task_status\":\"SUCCEEDED\","
                + "\"video_url\":\"https://dashscope-result.oss-cn-beijing.aliyuncs.com/x.mp4\"},"
                + "\"usage\":{\"video_count\":1,\"video_duration\":5}}", "application/json"));

        MediaVideoPoll poll = service.poll("t-1");

        assertThat(poll.status()).isEqualTo("SUCCEEDED");
        assertThat(poll.videoUrl()).contains("aliyuncs.com");
        assertThat(poll.providerRequestId()).isEqualTo("r-2");
        assertThat(poll.usage()).containsEntry("video_count", 1L).containsEntry("video_duration", 5L);
        assertThat(transport.records.get(0).method()).isEqualTo("GET");
        assertThat(transport.records.get(0).url()).isEqualTo("https://dashscope.test/api/v1/tasks/t-1");
    }

    @Test
    void pollMapsDefinitiveFailureCancellationAndUnknownStatus() {
        transport.replies.add(reply(200, "{\"output\":{\"task_status\":\"FAILED\","
                + "\"fail_reason\":\"content policy\"}}", "application/json"));
        MediaVideoPoll failed = service.poll("t-1");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("PROVIDER_FAILED");
        assertThat(failed.message()).isEqualTo("content policy");

        transport.replies.add(reply(200, "{\"output\":{\"task_status\":\"CANCELED\"}}", "application/json"));
        assertThat(service.poll("t-2").status()).isEqualTo("CANCELED");

        transport.replies.add(reply(200, "{\"output\":{\"task_status\":\"MYSTERY\"}}", "application/json"));
        assertThat(service.poll("t-3").status()).isEqualTo("UNKNOWN");
    }

    @Test
    void pollSucceededWithoutVideoUrlIsUnknown() {
        transport.replies.add(reply(200, "{\"output\":{\"task_status\":\"SUCCEEDED\"}}", "application/json"));

        MediaVideoPoll poll = service.poll("t-1");

        assertThat(poll.status()).isEqualTo("UNKNOWN");
        assertThat(poll.errorCode()).isEqualTo("PROVIDER_RESULT_MISSING");
    }

    @Test
    void downloadOnlyAcceptsHttpsAllowlistedHosts() {
        byte[] mp4 = mp4();
        transport.replies.add(reply(200, mp4, "video/mp4"));

        assertThat(service.download("https://dashscope-result.oss-cn-beijing.aliyuncs.com/x.mp4", 4096))
                .isEqualTo(mp4);

        assertThatThrownBy(() -> service.download(
                "http://dashscope-result.oss-cn-beijing.aliyuncs.com/x.mp4", 4096))
                .isInstanceOf(MediaProviderRejectedException.class);
        assertThatThrownBy(() -> service.download("https://evil.example/x.mp4", 4096))
                .isInstanceOf(MediaProviderRejectedException.class);
        assertThatThrownBy(() -> service.download(
                "https://user:pass@dashscope-result.aliyuncs.com/x.mp4", 4096))
                .isInstanceOf(MediaProviderRejectedException.class);
    }

    @Test
    void downloadRejectsNonVideoAndOversizedBodies() {
        transport.replies.add(reply(200, "<html>nope</html>".getBytes(StandardCharsets.UTF_8), "text/html"));
        assertThatThrownBy(() -> service.download("https://bucket.aliyuncs.com/x", 4096))
                .isInstanceOf(MediaProviderRejectedException.class);

        transport.replies.add(reply(200, mp4(), "video/mp4"));
        assertThatThrownBy(() -> service.download("https://bucket.aliyuncs.com/x", 2))
                .isInstanceOf(MediaProviderRejectedException.class);
    }

    private static byte[] mp4() {
        return new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0};
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
