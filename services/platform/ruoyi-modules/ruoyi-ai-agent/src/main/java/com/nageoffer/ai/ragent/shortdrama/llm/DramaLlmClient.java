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

package com.nageoffer.ai.ragent.shortdrama.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F21 短剧 LLM 调用门面。
 *
 * <p>只依赖 {@link LLMService}（现有 DeepSeek/通义路由链），不自行读取任何 API key、
 * 不拼接 provider 端点、不绕过 EgressPolicy。模型档位固定 {@link Tier#STANDARD}：
 * 创作类任务需要中等以上质量，深度档不在本切片预算内。
 *
 * <p>所有同步调用把提供方异常收敛为统一的 500 信封（不缺省成平台 503），
 * 但日志只记异常类型与耗时，不记剧本正文。
 */
public class DramaLlmClient {

    private static final Logger log = LoggerFactory.getLogger(DramaLlmClient.class);

    private static final int MAX_TOKENS = 4096;

    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final LlmOutputParser parser;

    public DramaLlmClient(LLMService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.parser = new LlmOutputParser(objectMapper);
    }

    public LlmOutputParser.ScriptEnvelope generateScript(String projectName, String idea, String tone) {
        String raw = chat(DramaPrompts.scriptSystem(),
                DramaPrompts.scriptUser(projectName, idea));
        return parser.parseScriptEnvelope(raw);
    }

    public LlmOutputParser.ScriptEnvelope polishScript(String projectName, String scriptText, String tone) {
        String raw = chat(DramaPrompts.polishSystem(),
                DramaPrompts.polishUser(projectName, scriptText));
        return parser.parseScriptEnvelope(raw);
    }

    /** 流式结束后由流式服务对完整缓冲做一次与同步路径相同的解析/校验。 */
    public LlmOutputParser.ScriptEnvelope parseScript(String raw) {
        return parser.parseScriptEnvelope(raw);
    }

    public com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle streamScript(
            String projectName, String idea, StreamCallback callback) {
        ChatRequest request = request(DramaPrompts.scriptSystem(),
                DramaPrompts.scriptUser(projectName, idea));
        try {
            return llmService.streamChat(request, callback);
        } catch (P04AiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("短剧流式剧本生成启动失败: {}", e.getClass().getSimpleName());
            throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR, "模型调用失败，请稍后重试");
        }
    }

    public LlmOutputParser.Analysis analyzeAssets(String projectName,
                                                  String scriptText,
                                                  String outlineText,
                                                  List<DramaRows.CharacterRow> characters,
                                                  List<DramaRows.LocationRow> locations) {
        Set<Long> characterIds = new LinkedHashSet<>();
        for (DramaRows.CharacterRow row : characters) {
            characterIds.add(row.id());
        }
        Set<Long> locationIds = new LinkedHashSet<>();
        for (DramaRows.LocationRow row : locations) {
            locationIds.add(row.id());
        }
        String raw = chat(DramaPrompts.analysisSystem(),
                DramaPrompts.analysisUser(projectName, scriptText, outlineText, characters, locations));
        return parser.parseAnalysis(raw, characterIds, locationIds);
    }

    public Map<Integer, JsonNode> photographyRules(String projectName,
                                                   String scriptText,
                                                   List<DramaRows.StoryboardRow> storyboards,
                                                   List<DramaRows.CharacterRow> characters,
                                                   List<DramaRows.LocationRow> locations) {
        String raw = chat(DramaPrompts.photographySystem(),
                DramaPrompts.sceneRulesUser(projectName, scriptText, storyboards,
                        characters, locations));
        return parser.parseSceneRules(raw, sceneNumbers(storyboards), "photographyRules");
    }

    public Map<Integer, JsonNode> actingDirections(String projectName,
                                                   String scriptText,
                                                   List<DramaRows.StoryboardRow> storyboards,
                                                   List<DramaRows.CharacterRow> characters,
                                                   List<DramaRows.LocationRow> locations) {
        String raw = chat(DramaPrompts.actingSystem(),
                DramaPrompts.sceneRulesUser(projectName, scriptText, storyboards,
                        characters, locations));
        return parser.parseSceneRules(raw, sceneNumbers(storyboards), "actingNotes");
    }

    private static Set<Integer> sceneNumbers(List<DramaRows.StoryboardRow> storyboards) {
        Set<Integer> scenes = new LinkedHashSet<>();
        for (DramaRows.StoryboardRow row : storyboards) {
            scenes.add(row.sceneNo());
        }
        return scenes;
    }

    private String chat(String system, String user) {
        ChatRequest request = request(system, user);
        long started = System.currentTimeMillis();
        try {
            String raw = llmService.chat(request, Tier.STANDARD);
            log.info("短剧模型调用完成: elapsed={}ms outputLength={}",
                    System.currentTimeMillis() - started, raw == null ? 0 : raw.length());
            return raw;
        } catch (P04AiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("短剧模型调用失败: elapsed={}ms type={}",
                    System.currentTimeMillis() - started, e.getClass().getSimpleName());
            throw new P04AiException(P04AiErrorCode.INTERNAL_ERROR, "模型调用失败，请稍后重试");
        }
    }

    static ChatRequest request(String system, String user) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.system(system), ChatMessage.user(user)))
                .temperature(0.8D)
                .topP(0.95D)
                .maxTokens(MAX_TOKENS)
                .thinking(false)
                .build();
    }
}
