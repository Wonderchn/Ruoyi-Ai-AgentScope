package org.ruoyi.domain.entity.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.tenant.core.TenantEntity;

/**
 * 智能体信息实体
 * <p>
 * 一个智能体聚合：一个聊天模型 + 一组 MCP 工具 + 一组磁盘技能 + 一组知识库 + 自定义提示词
 * 关联以 JSON 数组字符串列存储：mcp_tool_ids / skill_names / knowledge_ids
 *
 * @author ruoyi team
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_profile")
public class Agent extends TenantEntity {

    /**
     * 智能体ID
     *
     * <p>统一库里 {@code ai_agent_profile.id} 是 {@code VARCHAR(20)} 且<b>没有列默认值</b>
     * （V7 形状，不是 V9 转换出来的旧 MySQL 自增列），所以：
     * <ul>
     *   <li>Java 类型必须是 {@code String}——AI 侧 {@code AgentProfileDO} 也把同一列映射为
     *       {@code String id}，两侧入口要读到同一条记录，类型不能各说各话；</li>
     *   <li>{@code IdType.AUTO} 必须改成 {@code IdType.ASSIGN_ID}——AUTO 会让 MyBatis 不写 id、
     *       指望数据库补，而这一列没有 default/identity，插入会直接撞 NOT NULL。</li>
     * </ul>
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 智能体名称。
     *
     * <p><b>必须显式映射到 {@code name}（WP-039A）。</b>与 {@code ChatSession.sessionTitle} 同一类问题：
     * 统一表 {@code ai_agent_profile} 上有**两个**名称列——{@code name}（V7，
     * {@code VARCHAR(64) NOT NULL}，AI 侧 {@code AgentProfileDO} 与统一读路径用的那一列）
     * 与 {@code agent_name}（V9 追加，"平台侧独有列，来自旧平台智能体表"）；
     * V11 列登记把旧列 {@code agent_name} 作为 identity 别名指向 {@code name}。
     *
     * <p>字段名 {@code agentName} 按驼峰约定会落到 {@code agent_name}，后果与 WP-038A 完全一致：
     * 平台侧新增智能体从不写 {@code name}（NOT NULL 且无默认值 → 插入失败），
     * 改名也只改 {@code agent_name}（AI 侧读 {@code name} → 看不到）。
     * 所以用注解把映射钉死在统一列上。
     */
    @TableField("name")
    private String agentName;

    /**
     * 智能体描述（下拉展示用）
     */
    private String agentDescribe;

    /**
     * 展示图标/头像URL
     */
    private String agentShow;

    /**
     * 绑定的聊天模型ID（ai_model.id, category=chat）
     */
    private Long modelId;

    /**
     * 是否启用深度思考(ReAct多子Agent)：0 否 1 是
     */
    private String enableThinking;

    /**
     * 自定义系统提示词
     */
    private String systemPrompt;

    /**
     * 关联MCP工具ID列表（JSON数组，[Long]）
     */
    private String mcpToolIds;

    /**
     * 关联磁盘技能名列表（JSON数组，[String]）
     */
    private String skillNames;

    /**
     * 关联知识库ID列表（JSON数组，[Long]）
     */
    private String knowledgeIds;

    /**
     * 状态：0 正常 1 停用
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

}
