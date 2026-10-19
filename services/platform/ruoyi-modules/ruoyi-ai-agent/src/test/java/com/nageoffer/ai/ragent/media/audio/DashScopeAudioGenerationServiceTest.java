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

package com.nageoffer.ai.ragent.media.audio;

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
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F19:0 语音适配器协议/失败语义（DashScope qwen-tts HTTP 同步协议）。
 *
 * <p>全部使用注入的假传输：不访问网络、不读取真实凭据。测试 key 在运行时随机生成。
 * qwen-tts 的 HTTP 协议没有 format 参数（官方文档仅 input.text/voice/instructions），
 * 因此本测试同时钉住"不向 provider 发送未定义参数"。
 */
@Tag("dev")
class DashScopeAudioGenerationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingTransport transport = new RecordingTransport();
    private MediaProperties properties;
    private DashScopeAudioGenerationService service;
    private String syntheticKey;

    @BeforeEach
    void setUp() {
        syntheticKey = "synthetic-" + UUID.randomUUID();
        properties = new MediaProperties();
        properties.getAudio().setApiKey(syntheticKey);
        properties.getAudio().setModelName("qwen-test");
        properties.getAudio().setApiHost("https://dashscope.test");
        properties.getAudio().setAllowedResultHosts("aliyuncs.com");
        properties.getAudio().setDefaultVoice("Cherry");
        properties.getAudio().setDefaultFormat("wav");
        service = new DashScopeAudioGenerationService(properties, transport, mapper);
    }

    @Test
    void submitUsesQwenTtsHttpProtocolWithoutSendingUndefinedFormat() throws Exception {
        transport.replies.add(reply(200, "{\"request_id\":\"r-1\",\"output\":{\"audio\":{"
                + "\"url\":\"https://dashscope-result.oss-cn-beijing.aliyuncs.com/x.wav\","
                + "\"id\":\"a-1\",\"expires_at\":1760000000}},"
                + "\"usage\":{\"characters\":5,\"input_tokens\":0,\"output_tokens\":0}}", "application/json"));

        MediaAudioReceipt receipt = service.submit(new MediaAudioRequest(
                "qwen-test", "hello", "Cherry", "wav", 1.0, "calm"));

        assertThat(receipt.providerRequestId()).isEqualTo("r-1");
        assertThat(receipt.audioUrl()).contains("aliyuncs.com");
        assertThat(receipt.audioBase64()).isNull();
        assertThat(receipt.usage()).containsEntry("characters", 5L);

        Recorded recorded = transport.records.get(0);
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.url())
                .isEqualTo("https://dashscope.test/api/v1/services/aigc/multimodal-generation/generation");
        assertThat(recorded.headers().get("Authorization")).isEqualTo("Bearer " + syntheticKey);
        JsonNode body = mapper.readTree(recorded.body());
        assertThat(body.path("model").asText()).isEqualTo("qwen-test");
        assertThat(body.path("input").path("text").asText()).isEqualTo("hello");
        assertThat(body.path("input").path("voice").asText()).isEqualTo("Cherry");
        assertThat(body.path("input").path("instructions").asText()).isEqualTo("calm");
        // qwen-tts HTTP 协议没有 format/speed 参数：不得伪造 provider 未定义的字段。
        assertThat(body.path("parameters").isMissingNode()).isTrue();
        assertThat(recorded.body()).doesNotContain("\"format\"", "\"speed\"");
    }

    @Test
    void submitReturnsBase64AudioWhenProviderOmitsUrl() {
        byte[] wav = wav();
        String base64 = Base64.getEncoder().encodeToString(wav);
        transport.replies.add(reply(200, "{\"request_id\":\"r-2\",\"output\":{\"audio\":{\"data\":\""
                + base64 + "\"}},\"usage\":{\"characters\":12}}", "application/json"));

        MediaAudioReceipt receipt = service.submit(new MediaAudioRequest(
                "qwen-test", "hello there", null, null, null, null));

        assertThat(receipt.audioUrl()).isNull();
        assertThat(receipt.audioBase64()).isEqualTo(base64);
        assertThat(receipt.usage()).containsEntry("characters", 12L);
    }

    @Test
    void submitWithoutAudioResultIsIndeterminate() {
        transport.replies.add(reply(200, "{\"request_id\":\"r-3\",\"output\":{}}", "application/json"));

        assertThatThrownBy(() -> service.submit(new MediaAudioRequest(
                "qwen-test", "hello", null, null, null, null)))
                .isInstanceOf(MediaProviderUnavailableException.class);
    }

    @Test
    void authFailureIsDefinitiveAndNeverEchoesTheKey() {
        transport.replies.add(reply(401, "{\"message\":\"Invalid API-key provided.\"}", "application/json"));

        assertThatThrownBy(() -> service.submit(new MediaAudioRequest(
                "qwen-test", "hello", null, null, null, null)))
                .isInstanceOfSatisfying(MediaProviderRejectedException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("PROVIDER_AUTH_FAILED");
                    assertThat(ex.getMessage()).doesNotContain(syntheticKey);
                });
    }

    @Test
    void transportFailureIsIndeterminateNotRejected() {
        transport.failure = new IOException("timeout");

        assertThatThrownBy(() -> service.submit(new MediaAudioRequest(
                "qwen-test", "hello", null, null, null, null)))
                .isInstanceOf(MediaProviderUnavailableException.class);
    }

    @Test
    void downloadOnlyAcceptsHttpsAllowlistedHosts() {
        transport.replies.add(reply(200, wav(), "audio/wav"));

        assertThat(service.download("https://dashscope-result.oss-cn-beijing.aliyuncs.com/x.wav", 4096, "wav"))
                .isEqualTo(wav());

        assertThatThrownBy(() -> service.download(
                "http://dashscope-result.oss-cn-beijing.aliyuncs.com/x.wav", 4096, "wav"))
                .isInstanceOf(MediaProviderRejectedException.class);
        assertThatThrownBy(() -> service.download("https://evil.example/x.wav", 4096, "wav"))
                .isInstanceOf(MediaProviderRejectedException.class);
        assertThatThrownBy(() -> service.download(
                "https://user:pass@dashscope-result.aliyuncs.com/x.wav", 4096, "wav"))
                .isInstanceOf(MediaProviderRejectedException.class);
    }

    @Test
    void downloadRejectsNonAudioAndOversizedBodies() {
        transport.replies.add(reply(200, "<html>nope</html>".getBytes(StandardCharsets.UTF_8), "text/html"));
        assertThatThrownBy(() -> service.download("https://bucket.aliyuncs.com/x", 4096, "wav"))
                .isInstanceOf(MediaProviderRejectedException.class);

        transport.replies.add(reply(200, wav(), "audio/wav"));
        assertThatThrownBy(() -> service.download("https://bucket.aliyuncs.com/x", 2, "wav"))
                .isInstanceOf(MediaProviderRejectedException.class);
    }

    @Test
    void sniffAudioMimeRecognisesWavMp3OggFlacAndPcmHint() {
        assertThat(service.sniffMime(wav(), "wav")).isEqualTo("audio/wav");
        assertThat(service.sniffMime(mp3(), "mp3")).isEqualTo("audio/mpeg");
        assertThat(service.sniffMime(new byte[]{'O', 'g', 'g', 'S', 0, 0}, "opus")).isEqualTo("audio/ogg");
        assertThat(service.sniffMime(new byte[]{'f', 'L', 'a', 'C', 0, 0}, "flac")).isEqualTo("audio/flac");
        // PCM 没有文件头，只能按请求格式判定。
        assertThat(service.sniffMime(new byte[]{(byte) 0x90, 0x01, 0x02}, "pcm")).isEqualTo("audio/pcm");
        assertThatThrownBy(() -> service.sniffMime("<html>".getBytes(StandardCharsets.UTF_8), "wav"))
                .isInstanceOf(MediaProviderRejectedException.class);
    }

    private static byte[] wav() {
        return new byte[]{'R', 'I', 'F', 'F', 36, 0, 0, 0, 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ', 16, 0, 0, 0};
    }

    private static byte[] mp3() {
        return new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
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
