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

package com.nageoffer.ai.ragent.shortdrama;

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import com.nageoffer.ai.ragent.shortdrama.llm.DramaLlmClient;
import com.nageoffer.ai.ragent.shortdrama.llm.LlmOutputParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F21 红/绿判据：模型输出必须被当作不可信输入。
 *
 * <p>覆盖：剧本分隔符格式、正文空值、资产 ID 越界、摄影/表演 sceneNo 缺漏与越界。
 * 这些负例是"结构化输出校验"的实质，而不是只把 JSON 反序列化一遍。
 */
@Tag("dev")
class DramaLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private DramaLlmClient clientReturning(String raw) {
        LLMService llm = mock(LLMService.class);
        when(llm.chat(any(ChatRequest.class), eq(Tier.STANDARD))).thenReturn(raw);
        return new DramaLlmClient(llm, mapper);
    }

    @Test
    @DisplayName("剧本格式：JSON 元信息 + ===SCRIPT=== + 正文")
    void scriptEnvelopeIsParsed() {
        String raw = "{\"projectName\":\"逆风\",\"description\":\"简介\","
                + "\"scriptName\":\"第一稿\",\"tone\":\"热血\",\"outlineText\":\"大纲\"}\n"
                + "===SCRIPT===\n内景 训练馆 白天\n角色：「开始」";
        LlmOutputParser.ScriptEnvelope envelope = clientReturning(raw)
                .generateScript("逆风", "一个短剧创意", "热血");
        assertThat(envelope.projectName()).isEqualTo("逆风");
        assertThat(envelope.scriptName()).isEqualTo("第一稿");
        assertThat(envelope.scriptText()).contains("内景 训练馆");
    }

    @Test
    @DisplayName("剧本负例：没有可用正文 → 坏输出拒绝")
    void scriptEnvelopeWithoutScriptIsRejected() {
        assertThatThrownBy(() -> clientReturning("{\"projectName\":\"逆风\"}")
                .generateScript("逆风", "创意", null))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("资产分析：只接受当前项目已有角色/场景 ID")
    void analysisAcceptsKnownIds() {
        String raw = "{\"characters\":[{\"id\":1,\"analysis\":\"主角\",\"visualNotes\":\"短发\"}],"
                + "\"locations\":[{\"id\":2,\"analysis\":\"主场\",\"stagingNotes\":\"中央站位\"}],"
                + "\"continuityNotes\":\"服装连续\",\"styleNotes\":\"冷色\"}";
        LlmOutputParser.Analysis analysis = clientReturning(raw)
                .analyzeAssets("项目", "剧本", "大纲",
                        List.of(DramaTestSupport.characterRow(1, 10)),
                        List.of(DramaTestSupport.locationRow(2, 10)));
        assertThat(analysis.characters()).singleElement().extracting(LlmOutputParser.CharacterAnalysis::id)
                .isEqualTo(1L);
        assertThat(analysis.locations()).singleElement().extracting(LlmOutputParser.LocationAnalysis::id)
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("资产分析负例：模型臆造/跨项目 ID → 整份拒绝")
    void analysisRejectsUnknownId() {
        String raw = "{\"characters\":[{\"id\":999,\"analysis\":\"别人家的角色\"}],\"locations\":[]}";
        assertThatThrownBy(() -> clientReturning(raw)
                .analyzeAssets("项目", "剧本", "大纲",
                        List.of(DramaTestSupport.characterRow(1, 10)),
                        List.of()))
                .isInstanceOf(P04AiException.class)
                .extracting(e -> ((P04AiException) e).errorCode())
                .isEqualTo(P04AiErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("摄影规则：必须覆盖全部 sceneNo 且不得新增")
    void photographyRequiresEveryScene() {
        String raw = "{\"rules\":[{\"sceneNo\":1,\"photographyRules\":{\"shotType\":\"中景\"}}]}";
        assertThatThrownBy(() -> clientReturning(raw)
                .photographyRules("项目", "剧本",
                        List.of(DramaTestSupport.storyboardRow(1, 10, 20, 1),
                                DramaTestSupport.storyboardRow(2, 10, 20, 2)),
                        List.of(), List.of()))
                .isInstanceOf(P04AiException.class);
    }

    @Test
    @DisplayName("摄影规则负例：未知 sceneNo → 拒绝")
    void photographyRejectsUnknownScene() {
        String raw = "{\"rules\":[{\"sceneNo\":99,\"photographyRules\":{\"shotType\":\"中景\"}}]}";
        assertThatThrownBy(() -> clientReturning(raw)
                .photographyRules("项目", "剧本",
                        List.of(DramaTestSupport.storyboardRow(1, 10, 20, 1)),
                        List.of(), List.of()))
                .isInstanceOf(P04AiException.class);
    }

    @Test
    @DisplayName("表演指导：全覆盖时解析为 sceneNo → JSON")
    void actingDirectionsParseAllScenes() {
        String raw = "{\"rules\":["
                + "{\"sceneNo\":1,\"actingNotes\":{\"emotion\":\"克制\"}},"
                + "{\"sceneNo\":2,\"actingNotes\":{\"emotion\":\"爆发\"}}]}";
        Map<Integer, JsonNode> rules = clientReturning(raw)
                .actingDirections("项目", "剧本",
                        List.of(DramaTestSupport.storyboardRow(1, 10, 20, 1),
                                DramaTestSupport.storyboardRow(2, 10, 20, 2)),
                        List.of(), List.of());
        assertThat(rules).containsOnlyKeys(1, 2);
        assertThat(rules.get(2).get("emotion").asText()).isEqualTo("爆发");
    }

    @Test
    @DisplayName("流式：回调透传由 streamChat 负责，本判据只钉住接口存在")
    void streamChatIsDelegated() {
        LLMService llm = mock(LLMService.class);
        StreamCancellationHandle handle = mock(StreamCancellationHandle.class);
        when(llm.streamChat(any(ChatRequest.class), any(StreamCallback.class))).thenReturn(handle);
        new DramaLlmClient(llm, mapper).streamScript("项目", "创意", new StreamCallback() {
            @Override
            public void onContent(String content) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable error) {
            }
        });
        org.mockito.Mockito.verify(llm)
                .streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }
}
