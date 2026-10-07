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

package com.nageoffer.ai.ragent.template;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 公共模板域只读仓库（P1.3a）：模板行在保留租户 {@code __public_template__} 下显式存在。
 *
 * <p>语义（05 §4.4 TEMPLATE_DOMAIN，V3/V4 迁移）：公共模板<b>不以 NULL 冒充共享</b>，
 * 每条查询都显式携带 {@code tenant_id = '__public_template__'} 列值——这是"按显式租户值
 * 查询"的正常租户查询，不属于"无主体访问"，因此<b>不需要</b>进
 * {@code TenantContextGuard} 的 tenantless 白名单（白名单语义保持不扩大）。
 *
 * <p>全部方法只读；租户覆盖行（真实 tenant_id）的读取属租户上下文路径，不在这里。
 */
@Repository
public class PublicTemplateRepository {

    /** 保留模板租户：与 V3 模板域转移、逐表账（p1/table-attribution.json）一致。 */
    public static final String TEMPLATE_TENANT = "__public_template__";

    private final JdbcTemplate jdbcTemplate;

    public PublicTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 智能体人设模板行。 */
    public record TemplateProfile(String id, String name, String description, String avatar,
                                  Integer builtin, Integer active) {
    }

    /** 提示词槽位模板行。 */
    public record TemplatePrompt(String id, String agentId, String slotKey, String content) {
    }

    /** 意图节点模板行。 */
    public record TemplateIntentNode(String id, String kbId, String intentCode, String name,
                                     Integer level, String parentCode, String collectionName,
                                     Integer topK, Integer enabled) {
    }

    /** 关键词归一化映射模板行。 */
    public record TemplateQueryTerm(String id, String domain, String sourceTerm, String targetTerm,
                                    Integer matchType, Integer priority, Integer enabled) {
    }

    /** 示例问题模板行。 */
    public record TemplateSampleQuestion(String id, String title, String description, String question) {
    }

    /** 技能模板行。 */
    public record TemplateSkill(String id, String skillCode, String name, String description,
                                String content, Integer sortOrder, Integer enabled) {
    }

    public List<TemplateProfile> findProfiles() {
        String sql = "SELECT id, name, description, avatar, builtin, active FROM ai_agent_profile"
                + " WHERE tenant_id = '" + TEMPLATE_TENANT + "' AND deleted = 0 ORDER BY id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TemplateProfile(
                rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("avatar"), rs.getInt("builtin"), rs.getInt("active")));
    }

    public List<TemplatePrompt> findPrompts(String agentId) {
        String sql = "SELECT id, agent_id, slot_key, content FROM ai_agent_prompt"
                + " WHERE tenant_id = '" + TEMPLATE_TENANT + "' AND deleted = 0 AND agent_id = ?"
                + " ORDER BY slot_key";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TemplatePrompt(
                rs.getString("id"), rs.getString("agent_id"), rs.getString("slot_key"),
                rs.getString("content")), agentId);
    }

    public List<TemplateIntentNode> findIntentNodes() {
        String sql = "SELECT id, kb_id, intent_code, name, level, parent_code, collection_name,"
                + " top_k, enabled FROM ai_intent_node"
                + " WHERE tenant_id = '" + TEMPLATE_TENANT + "' AND deleted = 0 ORDER BY sort_order, id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TemplateIntentNode(
                rs.getString("id"), rs.getString("kb_id"), rs.getString("intent_code"),
                rs.getString("name"), rs.getInt("level"), rs.getString("parent_code"),
                rs.getString("collection_name"), (Integer) rs.getObject("top_k"), rs.getInt("enabled")));
    }

    public List<TemplateQueryTerm> findQueryTermMappings() {
        String sql = "SELECT id, domain, source_term, target_term, match_type, priority, enabled"
                + " FROM ai_query_term_mapping"
                + " WHERE tenant_id = '" + TEMPLATE_TENANT + "' AND deleted = 0"
                + " ORDER BY priority DESC, id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TemplateQueryTerm(
                rs.getString("id"), rs.getString("domain"), rs.getString("source_term"),
                rs.getString("target_term"), rs.getInt("match_type"), rs.getInt("priority"),
                rs.getInt("enabled")));
    }

    public List<TemplateSampleQuestion> findSampleQuestions() {
        String sql = "SELECT id, title, description, question FROM ai_sample_question"
                + " WHERE tenant_id = '" + TEMPLATE_TENANT + "' AND deleted = 0 ORDER BY id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TemplateSampleQuestion(
                rs.getString("id"), rs.getString("title"), rs.getString("description"),
                rs.getString("question")));
    }

    public List<TemplateSkill> findSkills() {
        String sql = "SELECT id, skill_code, name, description, content, sort_order, enabled"
                + " FROM ai_agent_skill"
                + " WHERE tenant_id = '" + TEMPLATE_TENANT + "' AND deleted = 0"
                + " ORDER BY sort_order, id";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TemplateSkill(
                rs.getString("id"), rs.getString("skill_code"), rs.getString("name"),
                rs.getString("description"), rs.getString("content"), rs.getInt("sort_order"),
                rs.getInt("enabled")));
    }
}
