package org.ruoyi.domain.entity.chat;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import org.ruoyi.common.mybatis.core.identity.PlatformMergedRow;

import java.io.Serial;

/**
 * 会话管理对象（统一库 {@code platform.ai_conversation}，由旧 MySQL 会话表迁移合并而来）。
 *
 * <p><b>类型对齐</b>：统一表的 {@code id} / {@code conversation_id} / {@code user_id} 都是
 * {@code VARCHAR}（V7 按 AI 侧形状建表，V11 用 {@code cast_text} 把旧 bigint 转成文本），
 * 因此这里的 {@code id}/{@code userId} 必须是字符串——用 {@code Long} 会让 JDBC 把 bigint
 * 参数送进 varchar 列，插入直接失败。
 *
 * <p><b>身份列</b>：{@code tenant_id} / {@code member_id} 是 V7 的 {@code NOT NULL} 平台侧列，
 * 由 {@code InjectionMetaObjectHandler} 从登录主体填充（见 {@link PlatformMergedRow}）。
 *
 * <p>{@code conversationId} 是<b>公开会话标识</b>，不是主键：消息与摘要按
 * {@code (tenant_id, conversation_id, member_id)} 关联到本行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_conversation")
public class ChatSession extends BaseEntity implements PlatformMergedRow {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键（varchar(20)：统一表沿用 AI 侧的字符串主键）
     */
    @TableId(value = "id")
    private String id;

    /**
     * 用户id（varchar(20)）
     */
    private String userId;

    /**
     * 平台租户列（varchar(64)，NOT NULL）：由登录主体填充
     */
    private String tenantId;

    /**
     * canonical 成员列（varchar(160)，NOT NULL）：{@code platform:<tenantId>:<userId>}
     */
    private String memberId;

    /**
     * 会话标题。
     *
     * <p><b>必须显式映射到 {@code title}（WP-038A）。</b>统一表上有<b>两个</b>标题列：
     * {@code title}（V7，{@code VARCHAR(128) NOT NULL}，无默认值）是统一读路径
     * （{@code TenantConversationReadRepository}）与 AI 资源面用的那一列；
     * {@code session_title}（V9 追加）是"平台侧独有列，来自旧平台会话表（已并入 ai_conversation）"。
     * WP-024 已把旧 {@code session_title} 作为 identity 别名映射到 {@code title}，
     * 两个列只是"值相同"的历史产物。
     *
     * <p>字段名 {@code sessionTitle} 按 MyBatis-Plus 的驼峰约定会落到 {@code session_title}，
     * 实测后果有两条（真库判据见 {@code ConversationTitleColumnTest}）：
     * <ol>
     *   <li>新增会话从不写 {@code title}，而它 NOT NULL 且无默认值 → 插入直接失败；</li>
     *   <li>重命名只改 {@code session_title}，而工作台会话列表与 AI 资源面读 {@code title}
     *       → 改名"看起来没生效"。</li>
     * </ol>
     * 所以这里用注解把映射钉死在统一读路径那一列上。
     */
    @TableField("title")
    private String sessionTitle;

    /**
     * 会话内容
     */
    private String sessionContent;

    /**
     * 会话ID（公开标识，不是主键）
     */
    private String conversationId;

    /**
     * 备注
     */
    private String remark;

}
