package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 知识库对象（统一库 {@code platform.ai_knowledge_base}，由旧 MySQL 知识库表迁移合并而来）。
 *
 * <p><b>写路径尚未适配（E5/WP-027 依赖）</b>：统一表主键是 {@code VARCHAR(20)}，而本实体仍是
 * {@code Long}；{@code owner_member_id} 是 {@code NOT NULL}；{@code collection_name} 需要部署
 * 向量后端前缀。这些都要等 WP-027 的输入与映射契约确定后一并处理，现在改一半会让知识域处于
 * "看起来适配了"的状态。本类只做了表名归位（读路径按统一表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_knowledge_base")
public class KnowledgeInfo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键（统一表是 varchar(20)；写路径适配见类注释，属 WP-027）
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 是否公开知识库（0 否 1是）
     */
    private Long share;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 知识分隔符
     */
    @TableField(value = "`separator`")
    private String separator;

    /**
     * 重叠字符数
     */
    private Long overlapChar;

    /**
     * 知识库中检索的条数
     */
    private Long retrieveLimit;

    /**
     * 相似度阈值
     */
    private Double similarityThreshold;

    /**
     * 文本块大小
     */
    private Long textBlockSize;

    /**
     * 向量库
     */
    private String vectorModel;

    /**
     * 向量模型
     */
    private String embeddingModel;

    /**
     * 是否启用重排序（0 否 1是）
     */
    private Integer enableRerank;

    /**
     * 重排序模型名称
     */
    private String rerankModel;

    /**
     * 重排序后返回的文档数量
     */
    private Integer rerankTopN;

    /**
     * 重排序相关性分数阈值
     */
    private Double rerankScoreThreshold;

    /**
     * 是否启用混合检索（0 否 1是）
     */
    private Integer enableHybrid;

    /**
     * 混合检索权重 (0.0-1.0)
     */
    private Double hybridAlpha;

    /**
     * 备注
     */
    private String remark;


}
