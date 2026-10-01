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

package com.nageoffer.ai.ragent.knowledge.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识库文档定时刷新任务
 *
 * <p>P1.2a：这两个业务扫描属于<b>未批准旧能力</b>，且旧实现没有可信主体
 * （既无 tenant 也无 member，恢复逻辑会直接写文档状态）。两个 {@code @Scheduled} 方法
 * 在<b>任何 SQL / claim lease / 线程 submit 之前</b>短路关闭，因此即使在测试里直接调用，
 * 也不会查询、不会更新文档状态、不会抢 Redis 锁、不会提交任务。
 *
 * <p>保留 Bean 与调度入口（不删除），可靠恢复算法仍按 P2 处理，本单元不改它。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleJob {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final Executor knowledgeChunkExecutor;
    private final KnowledgeScheduleProperties scheduleProperties;
    private final ScheduleLockManager lockManager;
    private final ScheduleRefreshProcessor scheduleRefreshProcessor;
    private final DocumentStatusHelper documentStatusHelper;
    /**
     * 旧能力关闭判定；缺席按关闭处理（不默认放行）。
     */
    private final ObjectProvider<SaasCapabilityBoundary> capabilityBoundary;

    /**
     * 恢复长时间卡在 RUNNING 状态的文档（进程崩溃等异常场景）
     * 超过配置阈值未完成的 RUNNING 文档重置为 FAILED，允许用户手动重试
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverStuckRunningDocuments() {
        SaasCapabilityBoundary.requireOpenOrClosed(capabilityBoundary,
                SaasCapabilityBoundary.LegacyCapability.DOCUMENT_SCHEDULE_RECOVER);

        long timeoutMinutes = scheduleProperties.getRunningTimeoutMinutes();
        DocumentStatusHelper.StuckRecoveryResult result = documentStatusHelper.recoverStuckRunning(timeoutMinutes);
        if (result.actualRecovered() > 0) {
            long effectiveTimeout = Math.max(timeoutMinutes, 10);
            log.warn("重置了 {} 个卡在 RUNNING 状态超过 {} 分钟的文档为 FAILED，候选 docIds={}",
                    result.actualRecovered(), effectiveTimeout, result.stuckDocIds());
        }
    }

    @Scheduled(fixedDelayString = "${rag.knowledge.schedule.scan-delay-ms:10000}")
    public void scan() {
        SaasCapabilityBoundary.requireOpenOrClosed(capabilityBoundary,
                SaasCapabilityBoundary.LegacyCapability.DOCUMENT_SCHEDULE_SCAN);

        Date now = new Date();
        List<KnowledgeDocumentScheduleDO> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getEnabled, 1)
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getNextRunTime)
                                .or()
                                .le(KnowledgeDocumentScheduleDO::getNextRunTime, now))
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
                        .orderByAsc(KnowledgeDocumentScheduleDO::getNextRunTime)
                        .last("LIMIT " + Math.max(scheduleProperties.getBatchSize(), 1))
        );

        if (schedules == null || schedules.isEmpty()) {
            return;
        }

        for (KnowledgeDocumentScheduleDO schedule : schedules) {
            if (schedule == null || schedule.getId() == null) {
                continue;
            }
            ScheduleLockLease lease = lockManager.tryAcquire(schedule.getId(), now);
            if (lease == null) {
                continue;
            }
            try {
                knowledgeChunkExecutor.execute(() -> scheduleRefreshProcessor.process(lease));
            } catch (RejectedExecutionException e) {
                log.error("定时任务提交失败: scheduleId={}, docId={}, kbId={}",
                        schedule.getId(), schedule.getDocId(), schedule.getKbId(), e);
                lockManager.release(lease);
            }
        }
    }
}
