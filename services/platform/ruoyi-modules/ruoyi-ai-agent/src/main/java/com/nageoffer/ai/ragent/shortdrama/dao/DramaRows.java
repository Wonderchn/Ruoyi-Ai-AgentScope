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

package com.nageoffer.ai.ragent.shortdrama.dao;

import java.sql.Timestamp;

/**
 * F21 短剧持久化行（V9 {@code ai_short_drama_*} 冻结表形状的只读/窄写视图）。
 *
 * <p><b>为什么用行记录而不是 MyBatis 实体。</b>本轮切片只触碰
 * {@code ai_short_drama_project/script/character/location/storyboard} 五张冻结表，
 * 且写面只有"项目/脚本插入、脚本条件更新、分镜摄影/表演列更新"。
 * 用 {@code JdbcTemplate} 显式 SQL 可以保证：
 * <ul>
 *   <li>每条语句的 {@code tenant_id} 与 {@code user_id} 谓词都写在 SQL 里，
 *       <b>不依赖</b>平台 MyBatis 租户行拦截器的线程上下文（异步 LLM 回调线程没有
 *       SaToken 登录上下文，靠拦截器会在语句下发前被 fail-closed 拒绝）；</li>
 *   <li>不新增 Mapper 包，也就不需要把新包追加进
 *       {@code AiEmbeddedMapperConfiguration} 的固定 {@code @MapperScan} 清单；</li>
 *   <li>行形状逐列对照 V9（{@code V9__legacy_ai_domain_ddl.sql:702-1029}），
 *       不复制实体字段映射的第二份真相。</li>
 * </ul>
 */
public final class DramaRows {

    private DramaRows() {
    }

    /** {@code platform.ai_short_drama_project}。 */
    public record ProjectRow(long id,
                             long userId,
                             String projectName,
                             String description,
                             String status,
                             String artStyle,
                             String aspectRatio,
                             Timestamp createTime,
                             Timestamp updateTime,
                             String tenantId) {
    }

    /** {@code platform.ai_short_drama_script}。 */
    public record ScriptRow(long id,
                            long projectId,
                            String scriptName,
                            String scriptText,
                            String outlineText,
                            String tone,
                            String sourceType,
                            Timestamp createTime,
                            Timestamp updateTime,
                            String tenantId) {
    }

    /** {@code platform.ai_short_drama_character}（F21:5 只读输入）。 */
    public record CharacterRow(long id,
                               long projectId,
                               String name,
                               String aliases,
                               String introduction,
                               String roleLevel,
                               String gender,
                               String ageRange,
                               String personalityTags,
                               String visualDescription,
                               String tenantId) {
    }

    /** {@code platform.ai_short_drama_location}（F21:5 只读输入）。 */
    public record LocationRow(long id,
                              long projectId,
                              String name,
                              String summary,
                              boolean hasCrowd,
                              String crowdDescription,
                              String descriptions,
                              String tenantId) {
    }

    /** {@code platform.ai_short_drama_storyboard}（F21:5 摄影规则/表演指导的窄写目标）。 */
    public record StoryboardRow(long id,
                                long projectId,
                                long scriptId,
                                int sceneNo,
                                String sceneTitle,
                                String sceneText,
                                String sceneType,
                                String shotType,
                                String cameraMove,
                                String charactersJson,
                                String locationName,
                                String photographyRules,
                                String actingNotes,
                                int durationSeconds,
                                String tenantId) {
    }
}
