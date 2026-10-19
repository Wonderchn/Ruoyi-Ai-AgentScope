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

import cn.hutool.core.util.IdUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * F21 短剧仓库：显式 SQL、显式租户/属主谓词。
 *
 * <p><b>边界纪律。</b>每条读写语句都带 {@code tenant_id = ?}，凡是"用户自己的项目"
 * 还需带 {@code user_id = ?}；调用方只能传 {@code DramaActor} 解析出的值，
 * 不接受请求体里的 tenant/user。越权与不存在统一返回 {@code Optional.empty()} /
 * {@code 0} 行，由服务层映射成同一个 404，避免泄露资源存在性。
 *
 * <p><b>schema。</b>V9 把表建在 {@code platform} schema；内嵌连接串的
 * {@code currentSchema=platform,extensions} 虽已包含它，本类仍写全限定名，
 * 与 {@code AuthorizedExportService}/{@code TenantConversationReadRepository}
 * 的既有原生 SQL 同形，避免连接参数变化时静默换 schema。
 */
@Repository
public class ShortDramaRepository {

    private static final String PROJECT_COLUMNS = """
            id, user_id, project_name, description, status, art_style, compose_aspect_ratio,
            create_time, update_time, tenant_id
            """;

    private static final String SCRIPT_COLUMNS = """
            id, project_id, script_name, script_text, outline_text, tone, source_type,
            create_time, update_time, tenant_id
            """;

    private static final String CHARACTER_COLUMNS = """
            id, project_id, name, aliases, introduction, role_level, gender, age_range,
            personality_tags, visual_description, tenant_id
            """;

    private static final String LOCATION_COLUMNS = """
            id, project_id, name, summary, has_crowd, crowd_description, descriptions, tenant_id
            """;

    private static final String STORYBOARD_COLUMNS = """
            id, project_id, script_id, scene_no, scene_title, scene_text, scene_type, shot_type,
            camera_move, characters_json, location_name, photography_rules, acting_notes,
            duration_seconds, tenant_id
            """;

    private static final RowMapper<DramaRows.ProjectRow> PROJECT_MAPPER = (rs, rowNum) ->
            new DramaRows.ProjectRow(
                    rs.getLong("id"),
                    rs.getLong("user_id"),
                    rs.getString("project_name"),
                    rs.getString("description"),
                    rs.getString("status"),
                    rs.getString("art_style"),
                    rs.getString("compose_aspect_ratio"),
                    rs.getTimestamp("create_time"),
                    rs.getTimestamp("update_time"),
                    rs.getString("tenant_id"));

    private static final RowMapper<DramaRows.ScriptRow> SCRIPT_MAPPER = (rs, rowNum) ->
            new DramaRows.ScriptRow(
                    rs.getLong("id"),
                    rs.getLong("project_id"),
                    rs.getString("script_name"),
                    rs.getString("script_text"),
                    rs.getString("outline_text"),
                    rs.getString("tone"),
                    rs.getString("source_type"),
                    rs.getTimestamp("create_time"),
                    rs.getTimestamp("update_time"),
                    rs.getString("tenant_id"));

    private static final RowMapper<DramaRows.CharacterRow> CHARACTER_MAPPER = (rs, rowNum) ->
            new DramaRows.CharacterRow(
                    rs.getLong("id"),
                    rs.getLong("project_id"),
                    rs.getString("name"),
                    rs.getString("aliases"),
                    rs.getString("introduction"),
                    rs.getString("role_level"),
                    rs.getString("gender"),
                    rs.getString("age_range"),
                    rs.getString("personality_tags"),
                    rs.getString("visual_description"),
                    rs.getString("tenant_id"));

    private static final RowMapper<DramaRows.LocationRow> LOCATION_MAPPER = (rs, rowNum) ->
            new DramaRows.LocationRow(
                    rs.getLong("id"),
                    rs.getLong("project_id"),
                    rs.getString("name"),
                    rs.getString("summary"),
                    rs.getBoolean("has_crowd"),
                    rs.getString("crowd_description"),
                    rs.getString("descriptions"),
                    rs.getString("tenant_id"));

    private static final RowMapper<DramaRows.StoryboardRow> STORYBOARD_MAPPER = (rs, rowNum) ->
            new DramaRows.StoryboardRow(
                    rs.getLong("id"),
                    rs.getLong("project_id"),
                    rs.getLong("script_id"),
                    rs.getInt("scene_no"),
                    rs.getString("scene_title"),
                    rs.getString("scene_text"),
                    rs.getString("scene_type"),
                    rs.getString("shot_type"),
                    rs.getString("camera_move"),
                    rs.getString("characters_json"),
                    rs.getString("location_name"),
                    rs.getString("photography_rules"),
                    rs.getString("acting_notes"),
                    rs.getInt("duration_seconds"),
                    rs.getString("tenant_id"));

    private final JdbcTemplate jdbc;

    public ShortDramaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ project

    public List<DramaRows.ProjectRow> listProjects(long userId, String tenantId) {
        return jdbc.query("SELECT " + PROJECT_COLUMNS
                        + " FROM platform.ai_short_drama_project"
                        + " WHERE user_id = ? AND tenant_id = ? ORDER BY id DESC",
                PROJECT_MAPPER, userId, tenantId);
    }

    public Optional<DramaRows.ProjectRow> findProject(long projectId, long userId, String tenantId) {
        List<DramaRows.ProjectRow> rows = jdbc.query("SELECT " + PROJECT_COLUMNS
                        + " FROM platform.ai_short_drama_project"
                        + " WHERE id = ? AND user_id = ? AND tenant_id = ?",
                PROJECT_MAPPER, projectId, userId, tenantId);
        return rows.stream().findFirst();
    }

    /** 插入项目；主键由应用生成（V9 的 {@code id bigint NOT NULL} 无 identity 默认）。 */
    public long insertProject(long userId,
                              String tenantId,
                              String projectName,
                              String description,
                              String status,
                              String artStyle,
                              String aspectRatio) {
        long id = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO platform.ai_short_drama_project"
                        + " (id, user_id, project_name, description, status, art_style,"
                        + " compose_aspect_ratio, tenant_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, projectName, description, status, artStyle, aspectRatio, tenantId);
        return id;
    }

    public int updateProject(long projectId,
                             long userId,
                             String tenantId,
                             String projectName,
                             String description,
                             String status,
                             String artStyle) {
        return jdbc.update("UPDATE platform.ai_short_drama_project"
                        + " SET project_name = ?, description = ?, status = ?, art_style = ?,"
                        + " update_time = clock_timestamp()"
                        + " WHERE id = ? AND user_id = ? AND tenant_id = ?",
                projectName, description, status, artStyle, projectId, userId, tenantId);
    }

    public int deleteProject(long projectId, long userId, String tenantId) {
        return jdbc.update("DELETE FROM platform.ai_short_drama_project"
                        + " WHERE id = ? AND user_id = ? AND tenant_id = ?",
                projectId, userId, tenantId);
    }

    // ------------------------------------------------------------------ script

    public List<DramaRows.ScriptRow> listScripts(long projectId, String tenantId) {
        return jdbc.query("SELECT " + SCRIPT_COLUMNS
                        + " FROM platform.ai_short_drama_script"
                        + " WHERE project_id = ? AND tenant_id = ? ORDER BY id DESC",
                SCRIPT_MAPPER, projectId, tenantId);
    }

    public Optional<DramaRows.ScriptRow> findScript(long scriptId, long projectId, String tenantId) {
        List<DramaRows.ScriptRow> rows = jdbc.query("SELECT " + SCRIPT_COLUMNS
                        + " FROM platform.ai_short_drama_script"
                        + " WHERE id = ? AND project_id = ? AND tenant_id = ?",
                SCRIPT_MAPPER, scriptId, projectId, tenantId);
        return rows.stream().findFirst();
    }

    public Optional<DramaRows.ScriptRow> findLatestScript(long projectId, String tenantId) {
        List<DramaRows.ScriptRow> rows = jdbc.query("SELECT " + SCRIPT_COLUMNS
                        + " FROM platform.ai_short_drama_script"
                        + " WHERE project_id = ? AND tenant_id = ? ORDER BY id DESC LIMIT 1",
                SCRIPT_MAPPER, projectId, tenantId);
        return rows.stream().findFirst();
    }

    public long insertScript(long projectId,
                             String tenantId,
                             String scriptName,
                             String scriptText,
                             String outlineText,
                             String tone,
                             String sourceType) {
        long id = IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO platform.ai_short_drama_script"
                        + " (id, project_id, script_name, script_text, outline_text, tone,"
                        + " source_type, tenant_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, projectId, scriptName, scriptText, outlineText, tone, sourceType, tenantId);
        return id;
    }

    /**
     * 手动保存：不做版本守卫（用户显式编辑，last-write-wins）。
     */
    public int updateScriptManual(long scriptId,
                                  long projectId,
                                  String tenantId,
                                  String scriptName,
                                  String scriptText,
                                  String outlineText,
                                  String tone) {
        return jdbc.update("UPDATE platform.ai_short_drama_script"
                        + " SET script_name = ?, script_text = ?, outline_text = ?, tone = ?,"
                        + " update_time = clock_timestamp()"
                        + " WHERE id = ? AND project_id = ? AND tenant_id = ?",
                scriptName, scriptText, outlineText, tone, scriptId, projectId, tenantId);
    }

    /**
     * LLM 润色：以读取时的 {@code update_time} 做乐观并发守卫。
     *
     * <p>更新语句用 {@code clock_timestamp()} 而不是事务级 {@code now()}：
     * 两个并发事务可能在同一时刻开始，{@code now()} 会给出相同的值，导致第二个
     * UPDATE 在行锁释放后仍满足"旧值"条件而覆盖第一个结果；{@code clock_timestamp()}
     * 在每条语句执行时取值，第二个事务重检条件时必然看到不同的新值而更新 0 行。
     */
    public int updateScriptPolished(long scriptId,
                                    long projectId,
                                    String tenantId,
                                    Timestamp expectedUpdateTime,
                                    String scriptName,
                                    String scriptText,
                                    String outlineText,
                                    String tone) {
        return jdbc.update("UPDATE platform.ai_short_drama_script"
                        + " SET script_name = ?, script_text = ?, outline_text = ?, tone = ?,"
                        + " update_time = clock_timestamp()"
                        + " WHERE id = ? AND project_id = ? AND tenant_id = ? AND update_time = ?",
                scriptName, scriptText, outlineText, tone, scriptId, projectId, tenantId,
                expectedUpdateTime);
    }

    // ------------------------------------------------------------------ assets (read only)

    public List<DramaRows.CharacterRow> listCharacters(long projectId, String tenantId) {
        return jdbc.query("SELECT " + CHARACTER_COLUMNS
                        + " FROM platform.ai_short_drama_character"
                        + " WHERE project_id = ? AND tenant_id = ? ORDER BY id",
                CHARACTER_MAPPER, projectId, tenantId);
    }

    public List<DramaRows.LocationRow> listLocations(long projectId, String tenantId) {
        return jdbc.query("SELECT " + LOCATION_COLUMNS
                        + " FROM platform.ai_short_drama_location"
                        + " WHERE project_id = ? AND tenant_id = ? ORDER BY id",
                LOCATION_MAPPER, projectId, tenantId);
    }

    public List<DramaRows.StoryboardRow> listStoryboards(long projectId,
                                                         long scriptId,
                                                         String tenantId) {
        return jdbc.query("SELECT " + STORYBOARD_COLUMNS
                        + " FROM platform.ai_short_drama_storyboard"
                        + " WHERE project_id = ? AND script_id = ? AND tenant_id = ?"
                        + " ORDER BY scene_no",
                STORYBOARD_MAPPER, projectId, scriptId, tenantId);
    }

    public int updateStoryboardPhotography(long storyboardId,
                                           long projectId,
                                           long scriptId,
                                           String tenantId,
                                           String photographyRulesJson) {
        return jdbc.update("UPDATE platform.ai_short_drama_storyboard"
                        + " SET photography_rules = ?, update_time = clock_timestamp()"
                        + " WHERE id = ? AND project_id = ? AND script_id = ? AND tenant_id = ?",
                photographyRulesJson, storyboardId, projectId, scriptId, tenantId);
    }

    public int updateStoryboardActing(long storyboardId,
                                      long projectId,
                                      long scriptId,
                                      String tenantId,
                                      String actingNotesJson) {
        return jdbc.update("UPDATE platform.ai_short_drama_storyboard"
                        + " SET acting_notes = ?, update_time = clock_timestamp()"
                        + " WHERE id = ? AND project_id = ? AND script_id = ? AND tenant_id = ?",
                actingNotesJson, storyboardId, projectId, scriptId, tenantId);
    }

    // ------------------------------------------------------------------ counts / cascade

    public long countScripts(long projectId, String tenantId) {
        return count("platform.ai_short_drama_script", projectId, tenantId);
    }

    public long countCharacters(long projectId, String tenantId) {
        return count("platform.ai_short_drama_character", projectId, tenantId);
    }

    public long countLocations(long projectId, String tenantId) {
        return count("platform.ai_short_drama_location", projectId, tenantId);
    }

    public long countStoryboards(long projectId, String tenantId) {
        return count("platform.ai_short_drama_storyboard", projectId, tenantId);
    }

    private long count(String table, long projectId, String tenantId) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table
                        + " WHERE project_id = ? AND tenant_id = ?",
                Long.class, projectId, tenantId);
        return value == null ? 0L : value;
    }

    /**
     * 删除项目的全部关联产物（F21:0 删除约束）。
     *
     * <p>按子表 → 父表顺序删除，全部语句显式带项目与租户谓词；调用方负责事务，
     * 任一语句失败即整体回滚。角色形象先于角色删除，避免留下孤儿行。
     */
    public void deleteProjectChildren(long projectId, String tenantId) {
        jdbc.update("DELETE FROM platform.ai_short_drama_character_appearance"
                        + " WHERE tenant_id = ? AND character_id IN ("
                        + " SELECT id FROM platform.ai_short_drama_character"
                        + " WHERE project_id = ? AND tenant_id = ?)",
                tenantId, projectId, tenantId);
        jdbc.update("DELETE FROM platform.ai_short_drama_storyboard"
                        + " WHERE project_id = ? AND tenant_id = ?",
                projectId, tenantId);
        jdbc.update("DELETE FROM platform.ai_short_drama_character"
                        + " WHERE project_id = ? AND tenant_id = ?",
                projectId, tenantId);
        jdbc.update("DELETE FROM platform.ai_short_drama_location"
                        + " WHERE project_id = ? AND tenant_id = ?",
                projectId, tenantId);
        jdbc.update("DELETE FROM platform.ai_short_drama_audio"
                        + " WHERE project_id = ? AND tenant_id = ?",
                projectId, tenantId);
        jdbc.update("DELETE FROM platform.ai_short_drama_script"
                        + " WHERE project_id = ? AND tenant_id = ?",
                projectId, tenantId);
    }
}
