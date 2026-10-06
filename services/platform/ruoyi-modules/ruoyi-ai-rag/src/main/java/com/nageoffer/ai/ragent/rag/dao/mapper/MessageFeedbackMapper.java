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

package com.nageoffer.ai.ragent.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.MessageFeedbackDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface MessageFeedbackMapper extends BaseMapper<MessageFeedbackDO> {

    // 两处与冻结 AI 侧形状的差异都在隔离 PG 实跑时验证过（WP-025）：
    // 1) 冲突目标必须是统一链上的键：V7 把 AI 侧的 (message_id, user_id) 换成了
    //    (tenant_id, message_id, user_id)（V7:2032/2033 先 DROP 再 ADD）。沿用旧键会让语句以
    //    "no unique or exclusion constraint matching the ON CONFLICT specification" 直接失败。
    // 2) 统一链的 create_time/update_time 是 NOT NULL 且没有列默认值（AI 侧有默认时间戳），
    //    注解 @Insert 不走 MyBatis-Plus 的字段填充，因此这里保留"未给值就用当前时间"的
    //    原语义；审计时间戳的取值来源就是"写入时刻"，与服务层显式传入的提交时间不冲突。

    @Insert("""
            INSERT INTO ai_message_feedback
                (id, tenant_id, member_id, message_id, conversation_id, user_id, vote, reason, comment, create_time, update_time, deleted)
            VALUES
                (#{feedback.id}, #{feedback.tenantId}, #{feedback.memberId},
                 #{feedback.messageId}, #{feedback.conversationId}, #{feedback.userId},
                 #{feedback.vote}, #{feedback.reason}, #{feedback.comment},
                 COALESCE(#{feedback.createTime}, CURRENT_TIMESTAMP),
                 COALESCE(#{feedback.updateTime}, CURRENT_TIMESTAMP), 0)
            ON CONFLICT (tenant_id, message_id, user_id) DO UPDATE SET
                conversation_id = EXCLUDED.conversation_id,
                vote = EXCLUDED.vote,
                reason = EXCLUDED.reason,
                comment = EXCLUDED.comment,
                update_time = EXCLUDED.update_time,
                deleted = 0
            WHERE ai_message_feedback.update_time < EXCLUDED.update_time
            """)
    int upsertActiveFeedback(@Param("feedback") MessageFeedbackDO feedback);

    /**
     * 写入取消反馈；记录不存在时创建逻辑删除占位，保证重复取消幂等。
     */
    @Insert("""
            INSERT INTO ai_message_feedback
                (id, tenant_id, member_id, message_id, conversation_id, user_id, vote, reason, comment, create_time, update_time, deleted)
            VALUES
                (#{feedback.id}, #{feedback.tenantId}, #{feedback.memberId},
                 #{feedback.messageId}, #{feedback.conversationId}, #{feedback.userId},
                 0, NULL, NULL,
                 COALESCE(#{feedback.createTime}, CURRENT_TIMESTAMP),
                 COALESCE(#{feedback.updateTime}, CURRENT_TIMESTAMP), 1)
            ON CONFLICT (tenant_id, message_id, user_id) DO UPDATE SET
                update_time = EXCLUDED.update_time,
                deleted = 1
            WHERE ai_message_feedback.update_time < EXCLUDED.update_time
            """)
    int upsertCancelledFeedback(@Param("feedback") MessageFeedbackDO feedback);
}
