package org.ruoyi.common.chat.entity.chat;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.identity.PlatformMergedRow;
import org.ruoyi.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 聊天消息对象（统一库 {@code platform.ai_message}，由旧 MySQL 消息表迁移合并而来）。
 *
 * <p><b>类型对齐</b>：统一表的 {@code id} / {@code conversation_id} / {@code user_id} 都是
 * {@code VARCHAR}（V11 用 {@code cast_text} 从旧 bigint 转换），用 {@code Long} 会让插入失败。
 * {@code session_id} 是 V9 追加列且<b>保持 bigint</b>（旧表语义是"会话主键"），所以它仍是
 * {@code Long}——这两个 id 的语义不同，不能一起改成字符串。
 *
 * <p><b>身份列</b>：{@code tenant_id}（继承自 {@code TenantEntity}）与 {@code member_id}
 * 都是 {@code NOT NULL}，由登录主体填充；V7 的 {@code fk_message_conversation} 要求
 * {@code (tenant_id, conversation_id, member_id)} 命中会话行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_message")
public class ChatMessage extends TenantEntity implements PlatformMergedRow {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键（varchar(20)）
     */
    @TableId(value = "id")
    private String id;

    /**
     * 会话主键（bigint：V9 追加列，指向 ai_conversation 主键，与公开 conversationId 不同）
     */
    private Long sessionId;

    /**
     * 用户id（varchar(20)）
     */
    private String userId;

    /**
     * canonical 成员列（varchar(160)，NOT NULL）：{@code platform:<tenantId>:<userId>}
     */
    private String memberId;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 对话角色
     */
    private String role;


    /**
     * 累计 Tokens
     */
    private Long totalTokens;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 备注
     */
    private String remark;


}
