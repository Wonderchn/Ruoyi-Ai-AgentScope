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

package com.nageoffer.ai.ragent.shortdrama.controller;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * F21 短剧公开面的输入/输出载体。
 *
 * <p><b>请求体刻意没有 tenantId / userId / projectOwner 字段。</b>租户与属主只从
 * 委托主体（{@code PrincipalContext}）取；请求体里的同名值既不会被读取，也不会被
 * 静默忽略后写入——而是根本没有这个入口，避免"客户端自带身份"成为可复制路径。
 *
 * <p><b>输出不回传 tenantId。</b>租户是服务端资源边界，不作为响应字段外泄；
 * 属主 id 同样不回传，客户端只能看到自己的资源。
 */
public final class ShortDramaDtos {

    private ShortDramaDtos() {
    }

    // ------------------------------------------------------------------ requests

    public record CreateProjectRequest(String projectName,
                                       String description,
                                       String artStyle,
                                       String aspectRatio,
                                       String status) {
    }

    public record UpdateProjectRequest(String projectName,
                                       String description,
                                       String artStyle,
                                       String status) {
    }

    public record CreateFromIdeaRequest(String idea,
                                        String projectName,
                                        String artStyle,
                                        String aspectRatio,
                                        String idempotencyKey) {
    }

    public record SaveScriptRequest(Long scriptId,
                                    String scriptName,
                                    String scriptText,
                                    String outlineText,
                                    String tone) {
    }

    public record GenerateScriptRequest(String idea,
                                        String projectName,
                                        String tone) {
    }

    public record PolishScriptRequest(Long scriptId) {
    }

    public record ScriptActionRequest(Long scriptId) {
    }

    // ------------------------------------------------------------------ views

    public record ProjectView(Long id,
                              String projectName,
                              String description,
                              String status,
                              String artStyle,
                              String aspectRatio,
                              String createTime,
                              String updateTime) {
    }

    public record ScriptView(Long id,
                             Long projectId,
                             String scriptName,
                             String scriptText,
                             String outlineText,
                             String tone,
                             String sourceType,
                             String createTime,
                             String updateTime) {
    }

    public record CharacterView(Long id,
                                String name,
                                String aliases,
                                String introduction,
                                String roleLevel,
                                String gender,
                                String ageRange,
                                String personalityTags,
                                String visualDescription) {
    }

    public record LocationView(Long id,
                               String name,
                               String summary,
                               Boolean hasCrowd,
                               String crowdDescription,
                               String descriptions) {
    }

    public record ProjectDetailView(ProjectView project,
                                    ScriptView script,
                                    List<ScriptView> scripts,
                                    List<CharacterView> characters,
                                    List<LocationView> locations,
                                    long characterCount,
                                    long locationCount,
                                    long storyboardCount) {
    }

    public record CharacterAnalysisView(Long id,
                                        String name,
                                        String analysis,
                                        String visualNotes) {
    }

    public record LocationAnalysisView(Long id,
                                       String name,
                                       String analysis,
                                       String stagingNotes) {
    }

    public record AssetAnalysisView(Long projectId,
                                    Long scriptId,
                                    String scriptUpdateTime,
                                    String inputHash,
                                    List<CharacterAnalysisView> characters,
                                    List<LocationAnalysisView> locations,
                                    String continuityNotes,
                                    String styleNotes) {
    }

    public record SceneRuleView(Integer sceneNo, JsonNode rules) {
    }

    public record RulesResultView(Long projectId,
                                  Long scriptId,
                                  String scriptUpdateTime,
                                  String inputHash,
                                  String kind,
                                  List<SceneRuleView> rules) {
    }
}
