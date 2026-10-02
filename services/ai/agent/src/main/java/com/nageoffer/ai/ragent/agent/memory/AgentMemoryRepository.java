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

package com.nageoffer.ai.ragent.agent.memory;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryControlDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryControlMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 长期记忆持久化层：claim、水位、双校验提交三件事都锁在这里，不指望调用方自觉
 *
 * <p>P1.3d：租户/成员在每次对外入口从可信执行主体解析（{@link PrincipalContext}），
 * 贯穿到全部 Mapper 调用与写入行；没有主体即拒绝。同一 userId 在两个租户是
 * 两份互不可见的记忆、两个互不排队的水位与版本。
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryRepository {

    /**
     * t_agent_message.role 存的是小写，与 AgentConversationServiceImpl 同一份口径
     */
    private static final String ROLE_USER = "user";

    /**
     * 在飞抽取超过这个时长即判定进程已死，结掉腾出 claim
     */
    private static final int STALE_PROCESSING_MINUTES = 10;

    /**
     * 同一区间判到第几次仍失败就丢弃，坏抽取不许永久堵住水位
     */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 一次抽取最多取这么多条待处理消息，防止久置会话堆出超大一批
     */
    private static final int MAX_PENDING_PER_EXTRACTION = 40;

    /**
     * 与 t_agent_memory.content 列宽同值；AGENT_MEMORY_EXTRACTION 与 AGENT_MEMORY_CONSOLIDATION
     * 两段提示词里手抄了这个数，改任何一处要几处一起改
     * 普通批超长在这里丢条，避免 INSERT 抛出导致整批回滚；清空批反过来整批拒收，见 commitClear
     */
    private static final int MAX_CONTENT_CHARS = 500;

    /**
     * 合并组最小成员数，与 AgentMemoryConsolidator.MIN_GROUP_SIZE 同值，两处要一起改
     */
    private static final int MIN_MERGE_GROUP_SIZE = 2;

    private final AgentMemoryMapper memoryMapper;
    private final AgentMemoryExtractionMapper extractionMapper;
    private final AgentMemoryControlMapper controlMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentMemoryProperties memoryProperties;

    /**
     * 读一次记忆视图；长期记忆开关关闭与读库异常一律回空快照，注入侧据此透传
     */
    public AgentMemorySnapshot loadSnapshot(String userId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return AgentMemorySnapshot.empty();
        }
        try {
            // 控制行正常由聊天入口在首条消息落库前预建，这里只是兜底补建，下界口径见 ensureControl
            Scope scope = scope();
            ensureControl(scope, userId);
            return new AgentMemorySnapshot(listActiveItems(scope, userId));
        } catch (Exception e) {
            log.warn("长期记忆读取失败, 本次不带记忆块, userId: {}", userId, e);
            return AgentMemorySnapshot.empty();
        }
    }

    /**
     * 懒创建控制面行，并发靠 ON CONFLICT 收敛；建行时刻兼抽取下界，必须先于本轮消息落库
     */
    public AgentMemoryControlDO ensureControl(String userId) {
        return ensureControl(scope(), userId);
    }

    private AgentMemoryControlDO ensureControl(Scope scope, String userId) {
        AgentMemoryControlDO control = controlMapper.selectByUserId(scope.tenantId(), scope.memberId());
        if (control != null) {
            return control;
        }
        controlMapper.ensureExists(scope.tenantId(), scope.memberId(), userId, new Date());
        return controlMapper.selectByUserId(scope.tenantId(), scope.memberId());
    }

    /**
     * 生效条目的唯一出口，持久化行不出这个类：仲裁、合并、注入拿到的都是同一份口径
     */
    public List<AgentMemoryItem> listActiveItems(String userId) {
        return listActiveItems(scope(), userId);
    }

    private List<AgentMemoryItem> listActiveItems(Scope scope, String userId) {
        return listActive(scope, userId).stream()
                .map(row -> new AgentMemoryItem(row.getId(), row.getContent()))
                .toList();
    }

    private List<AgentMemoryDO> listActive(Scope scope, String userId) {
        return memoryMapper.selectList(Wrappers.lambdaQuery(AgentMemoryDO.class)
                .eq(AgentMemoryDO::getTenantId, scope.tenantId())
                .eq(AgentMemoryDO::getMemberId, scope.memberId())
                .eq(AgentMemoryDO::getUserId, userId)
                .isNull(AgentMemoryDO::getInvalidAt)
                .orderByAsc(AgentMemoryDO::getCreateTime)
                .orderByAsc(AgentMemoryDO::getId));
    }

    /**
     * 当前水位，用户从未结过批返回 null
     */
    public String currentWatermark(String userId) {
        Scope scope = scope();
        return extractionMapper.selectWatermark(scope.tenantId(), scope.memberId(), userId);
    }

    /**
     * 覆盖这条用户消息的已结束抽取的状态，还没轮到它返回 null
     */
    public AgentMemoryExtractionStatus settledStatusCovering(String userId, String messageId) {
        Scope scope = scope();
        String status = extractionMapper.selectSettledStatusCovering(scope.tenantId(), scope.memberId(), userId, messageId);
        return status == null ? null : AgentMemoryExtractionStatus.valueOf(status);
    }

    /**
     * 水位之后、下界之后的用户消息，跨会话按 id 升序；下界防老账号接入前的历史倒灌
     * 不按会话切：各会话各推各的水位，先说的话会被后处理，旧值盖掉新纠正、已删的事实被写回
     */
    public List<AgentMessageDO> loadPending(String userId, String watermark, Date since) {
        Scope scope = scope();
        return messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getTenantId, scope.tenantId())
                .eq(AgentMessageDO::getMemberId, scope.memberId())
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getRole, ROLE_USER)
                .gt(watermark != null, AgentMessageDO::getId, watermark)
                .ge(since != null, AgentMessageDO::getCreateTime, since)
                .orderByAsc(AgentMessageDO::getId)
                .last("LIMIT " + MAX_PENDING_PER_EXTRACTION));
    }

    /**
     * 抢占该用户的处理权，抢不到返回 null；先回收僵尸行，靠部分唯一索引仲裁
     * conversationId 只记是哪个会话触发的，批内消息可以来自别的会话
     */
    public AgentMemoryExtractionDO claim(String userId, String conversationId,
                                         String fromMessageId, String toMessageId,
                                         AgentMemoryTriggerType trigger) {
        Scope scope = scope();
        int recycled = extractionMapper.recycleStale(scope.tenantId(), scope.memberId(), userId, STALE_PROCESSING_MINUTES);
        if (recycled > 0) {
            log.warn("长期记忆回收僵尸抽取, userId: {}, 条数: {}", userId, recycled);
        }
        int spent = extractionMapper.selectSpentAttempts(scope.tenantId(), scope.memberId(), userId, toMessageId);
        AgentMemoryExtractionDO extraction = AgentMemoryExtractionDO.builder()
                .tenantId(scope.tenantId())
                .memberId(scope.memberId())
                .userId(userId)
                .conversationId(conversationId)
                .fromMessageId(fromMessageId)
                .toMessageId(toMessageId)
                .status(AgentMemoryExtractionStatus.PROCESSING.name())
                .triggerType(trigger.name())
                .decisionCount(0)
                .attemptCount(spent + 1)
                .build();
        try {
            extractionMapper.insert(extraction);
            return extraction;
        } catch (DuplicateKeyException dke) {
            log.info("长期记忆跳过本次抽取, 同用户已有在飞抽取, userId: {}, conversationId: {}", userId, conversationId);
            return null;
        }
    }

    /**
     * Judge 或解析失败的结算：没到上限记 CONFLICT 等下次机会，到上限记 DROPPED 推水位
     */
    public AgentMemoryExtractionStatus settleFailure(AgentMemoryExtractionDO extraction) {
        AgentMemoryExtractionStatus status = extraction.getAttemptCount() >= MAX_ATTEMPTS
                ? AgentMemoryExtractionStatus.DROPPED
                : AgentMemoryExtractionStatus.CONFLICT;
        extractionMapper.settle(extraction.getId(), status.name(), 0, extraction.getAttemptCount());
        if (status == AgentMemoryExtractionStatus.DROPPED) {
            log.warn("长期记忆抽取重试耗尽, 丢弃并推进水位, extractionId: {}, 尝试次数: {}",
                    extraction.getId(), extraction.getAttemptCount());
        }
        return status;
    }

    /**
     * 短事务提交：先拿控制面行锁，revision 与水位双校验通过才应用决策
     * 缺一不可——NOOP 不推版本号，重复写入只有水位拦得住
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentMemoryCommitResult commit(AgentMemoryCommit commit) {
        String userId = commit.userId();
        Scope scope = scope();
        AgentMemoryControlDO control = controlMapper.selectForUpdate(scope.tenantId(), scope.memberId());
        String watermark = extractionMapper.selectWatermark(scope.tenantId(), scope.memberId(), userId);
        if (control == null || !Objects.equals(control.getRevision(), commit.expectedRevision())
                || !Objects.equals(watermark, commit.expectedWatermark())) {
            return rejectAsConflict(commit, control, watermark);
        }
        if (AgentMemoryDecision.containsClear(commit.decisions())) {
            return commitClear(scope, commit);
        }

        // 合并先落，腾出来的位置本批就能用上；事务内重读一次，后续预演看到的即合并后的记忆集
        List<AgentMemoryItem> active = listActiveItems(scope, userId);
        boolean consolidated = consolidate(scope, userId, active, commit) > 0;
        if (consolidated) {
            active = listActiveItems(scope, userId);
        }
        Map<String, String> survivors = new LinkedHashMap<>();
        for (AgentMemoryItem item : active) {
            survivors.put(item.id(), item.content());
        }

        List<AgentMemoryDecision> effective = new ArrayList<>();
        int discarded = 0;
        for (AgentMemoryDecision decision : commit.decisions()) {
            // 幻觉ID、已失效ID、他人ID 在这里一并落地，与条件 UPDATE 是同一个谓词
            if (decision.targetId() != null && !survivors.containsKey(decision.targetId())) {
                log.info("长期记忆丢弃指不着的决策, userId: {}, 动作: {}, 目标: {}",
                        userId, decision.action(), decision.targetId());
                discarded++;
                continue;
            }
            if (decision.introducesContent() && !storable(decision.content())) {
                log.info("长期记忆丢弃存不下的决策, userId: {}, 动作: {}, 正文字符: {}",
                        userId, decision.action(), decision.content() == null ? 0 : decision.content().length());
                discarded++;
                continue;
            }
            effective.add(decision);
        }
        if (effective.isEmpty()) {
            return settleEmpty(commit, consolidated);
        }

        boolean evicted = false;
        int projectedChars = AgentMemoryBlock.projectedChars(active, effective);
        if (projectedChars > memoryProperties.resolveMemoryMaxChars()) {
            List<AgentMemoryItem> remaining = evict(scope, userId, active, effective, commit);
            evicted = remaining.size() < active.size();
            active = remaining;
            projectedChars = AgentMemoryBlock.projectedChars(active, effective);
            if (projectedChars > memoryProperties.resolveMemoryMaxChars()) {
                throw new AgentMemoryCapacityException(commit.extractionId(), projectedChars,
                        memoryProperties.resolveMemoryMaxChars());
            }
        }

        int applied = apply(scope, userId, commit.sourceType(), effective);
        if (applied == 0) {
            return settleEmpty(commit, consolidated || evicted);
        }
        controlMapper.bumpRevision(scope.tenantId(), scope.memberId());
        settleOrThrow(commit, AgentMemoryExtractionStatus.WRITTEN, applied);
        log.info("长期记忆提交完成, userId: {}, extractionId: {}, 落库: {}, 丢弃: {}, 块字符: {}",
                userId, commit.extractionId(), applied, discarded + effective.size() - applied, projectedChars);
        return new AgentMemoryCommitResult(AgentMemoryExtractionStatus.WRITTEN, applied, true);
    }

    /**
     * 清空批：同批新增先验单条与总量，过了才动旧条目，不过整批抛出回滚——不能清空成功了、用户随后要记的却悄悄丢掉
     * 合并与淘汰都不走，旧条目整片失效，没有东西可合可淘；清空请求本身就在水位推进的这一批里，不必另记清空边界
     */
    private AgentMemoryCommitResult commitClear(Scope scope, AgentMemoryCommit commit) {
        String userId = commit.userId();
        List<AgentMemoryDecision> additions = commit.decisions().stream()
                .filter(AgentMemoryDecision::introducesContent)
                .toList();
        for (AgentMemoryDecision addition : additions) {
            if (!storable(addition.content())) {
                throw new AgentMemoryCapacityException(commit.extractionId(),
                        addition.content() == null ? 0 : addition.content().length(), MAX_CONTENT_CHARS);
            }
        }
        int maxChars = memoryProperties.resolveMemoryMaxChars();
        int projectedChars = AgentMemoryBlock.projectedChars(List.of(), additions);
        if (projectedChars > maxChars) {
            throw new AgentMemoryCapacityException(commit.extractionId(), projectedChars, maxChars);
        }

        int cleared = memoryMapper.retractAll(scope.tenantId(), scope.memberId(), userId);
        int added = apply(scope, userId, commit.sourceType(), additions);
        boolean mutated = cleared + added > 0;
        if (!mutated) {
            // 原本就空、清空后也没要记的：水位照推，免得这句清空请求日后被当成待处理再判一次
            settleOrThrow(commit, AgentMemoryExtractionStatus.NOOP, 0);
            return new AgentMemoryCommitResult(AgentMemoryExtractionStatus.NOOP, 0, false, true, 0);
        }
        controlMapper.bumpRevision(scope.tenantId(), scope.memberId());
        settleOrThrow(commit, AgentMemoryExtractionStatus.WRITTEN, added + (cleared > 0 ? 1 : 0));
        log.info("长期记忆清空完成, userId: {}, extractionId: {}, 清空: {}, 清空后新增: {}",
                userId, commit.extractionId(), cleared, added);
        return new AgentMemoryCommitResult(AgentMemoryExtractionStatus.WRITTEN, added, true, true, cleared);
    }

    /**
     * 决策落库；SUPERSEDE 先验旧行再插新行，倒过来会在失败时留下重复条目
     */
    private int apply(Scope scope, String userId, AgentMemorySourceType sourceType, List<AgentMemoryDecision> decisions) {
        int applied = 0;
        for (AgentMemoryDecision decision : decisions) {
            switch (decision.action()) {
                case ADD -> {
                    insert(scope, userId, decision.content(), sourceType, null);
                    applied++;
                }
                case SUPERSEDE -> {
                    String newId = IdWorker.getIdStr();
                    if (memoryMapper.supersede(scope.tenantId(), scope.memberId(), userId, decision.targetId(), newId) != 1) {
                        log.info("长期记忆取代落空, 丢弃该条, userId: {}, 目标: {}", userId, decision.targetId());
                        continue;
                    }
                    insert(scope, userId, decision.content(), sourceType, newId);
                    applied++;
                }
                case RETRACT -> {
                    if (memoryMapper.retract(scope.tenantId(), scope.memberId(), userId, decision.targetId()) != 1) {
                        log.info("长期记忆撤回落空, 丢弃该条, userId: {}, 目标: {}", userId, decision.targetId());
                        continue;
                    }
                    applied++;
                }
                case CLEAR -> throw new IllegalStateException("清空整批走 commitClear, 不逐条落库");
            }
        }
        return applied;
    }

    private boolean storable(String content) {
        return content != null && !content.isBlank() && content.length() <= MAX_CONTENT_CHARS;
    }

    private void insert(Scope scope, String userId, String content, AgentMemorySourceType sourceType, String presetId) {
        memoryMapper.insert(AgentMemoryDO.builder()
                .id(presetId)
                .tenantId(scope.tenantId())
                .memberId(scope.memberId())
                .userId(userId)
                .content(content)
                .sourceType(sourceType.name())
                .build());
    }

    /**
     * 受限合并落库，返回成功的组数；校验不信计划方，逐组复核成员有效性与合并后长度
     */
    private int consolidate(Scope scope, String userId, List<AgentMemoryItem> active, AgentMemoryCommit commit) {
        List<AgentMemoryMerge> merges = commit.merges();
        if (merges == null || merges.isEmpty()) {
            return 0;
        }
        Map<String, String> pool = new LinkedHashMap<>();
        active.forEach(item -> pool.put(item.id(), item.content()));
        Set<String> targeted = new HashSet<>();
        commit.decisions().stream()
                .map(AgentMemoryDecision::targetId)
                .filter(Objects::nonNull)
                .forEach(targeted::add);

        Set<String> claimed = new HashSet<>();
        int groups = 0;
        for (AgentMemoryMerge merge : merges) {
            if (!mergeable(userId, merge, pool, targeted, claimed)) {
                continue;
            }
            claimed.addAll(merge.ids());
            applyMerge(scope, userId, merge);
            groups++;
        }
        if (groups > 0) {
            // 与决策各推一格：合并本身已经改变了记忆集，在飞快照该在这一刻作废
            controlMapper.bumpRevision(scope.tenantId(), scope.memberId());
            log.info("长期记忆受限合并落库, userId: {}, extractionId: {}, 合并组: {}, 并掉条目: {}",
                    userId, commit.extractionId(), groups, claimed.size());
        }
        return groups;
    }

    /**
     * 整组条件 UPDATE 指向同一条新行，成员在锁内已核过一遍，落空即库被旁路改动，整个事务回滚
     */
    private void applyMerge(Scope scope, String userId, AgentMemoryMerge merge) {
        String newId = IdWorker.getIdStr();
        for (String oldId : merge.ids()) {
            if (memoryMapper.supersede(scope.tenantId(), scope.memberId(), userId, oldId, newId) != 1) {
                throw new IllegalStateException("长期记忆合并成员已被旁人改动, 条目: " + oldId);
            }
        }
        insert(scope, userId, merge.content(), AgentMemorySourceType.CONSOLIDATION, newId);
    }

    private boolean mergeable(String userId, AgentMemoryMerge merge, Map<String, String> pool,
                              Set<String> targeted, Set<String> claimed) {
        List<String> ids = merge.ids();
        if (ids.size() < MIN_MERGE_GROUP_SIZE || new HashSet<>(ids).size() != ids.size()) {
            log.info("长期记忆丢弃合并组, 成员不足两条或自身重复, userId: {}, 成员: {}", userId, ids);
            return false;
        }
        if (!storable(merge.content())) {
            log.info("长期记忆丢弃合并组, 产物存不下, userId: {}, 正文字符: {}",
                    userId, merge.content() == null ? 0 : merge.content().length());
            return false;
        }
        int before = 0;
        for (String id : ids) {
            String content = pool.get(id);
            // 指不着的、已被上一组并走的、本批决策正要动的，一律不许再并
            if (content == null || claimed.contains(id) || targeted.contains(id)) {
                log.info("长期记忆丢弃合并组, 成员不可用, userId: {}, 条目: {}", userId, id);
                return false;
            }
            before += content.length();
        }
        if (merge.content().length() >= before) {
            log.info("长期记忆丢弃合并组, 并完没变短, userId: {}, {} -> {}",
                    userId, before, merge.content().length());
            return false;
        }
        return true;
    }

    /**
     * 合并之后仍装不下才走这里：FLUSH 批来的殿后、其余最旧先淘，够装下就停
     * 停手水位是硬下限，越过它的那一条退回不淘，单批能顶掉的存量因此有界
     */
    private List<AgentMemoryItem> evict(Scope scope, String userId, List<AgentMemoryItem> active,
                                        List<AgentMemoryDecision> effective, AgentMemoryCommit commit) {
        Set<String> targeted = new HashSet<>();
        effective.stream()
                .map(AgentMemoryDecision::targetId)
                .filter(Objects::nonNull)
                .forEach(targeted::add);
        int maxChars = memoryProperties.resolveMemoryMaxChars();
        int stopChars = memoryProperties.resolveConsolidationStopChars();
        Set<String> evicted = new LinkedHashSet<>();
        for (AgentMemoryDO row : evictionOrder(scope, userId)) {
            List<AgentMemoryItem> remaining = survivorsOf(active, evicted);
            if (AgentMemoryBlock.projectedChars(remaining, effective) <= maxChars) {
                break;
            }
            // 本批决策指着的不许淘：淘了条件 UPDATE 就落空，那条决策会被静默丢掉
            if (targeted.contains(row.getId())) {
                continue;
            }
            List<AgentMemoryItem> shrunk = remaining.stream()
                    .filter(item -> !Objects.equals(item.id(), row.getId()))
                    .toList();
            // 量的是纯存量不含本批：一次提交最多顶掉上限与停手水位之间那一段
            if (AgentMemoryBlock.projectedChars(shrunk, List.of()) < stopChars) {
                break;
            }
            if (memoryMapper.retract(scope.tenantId(), scope.memberId(), userId, row.getId()) != 1) {
                throw new IllegalStateException("长期记忆淘汰落空, 条目已被旁人改动: " + row.getId());
            }
            evicted.add(row.getId());
        }
        if (!evicted.isEmpty()) {
            // 与合并各推一格：记忆集已经变了，在飞快照该在这一刻作废
            controlMapper.bumpRevision(scope.tenantId(), scope.memberId());
            log.warn("长期记忆容量淘汰, userId: {}, extractionId: {}, 淘汰: {}, 下限: {}",
                    userId, commit.extractionId(), evicted, stopChars);
        }
        return survivorsOf(active, evicted);
    }

    /**
     * 淘汰序；listActive 已按 create_time、id 升序，这里只再稳定分一档来源
     */
    private List<AgentMemoryDO> evictionOrder(Scope scope, String userId) {
        return listActive(scope, userId).stream()
                .sorted(Comparator.comparing(
                        (AgentMemoryDO row) -> AgentMemorySourceType.FLUSH.name().equals(row.getSourceType())))
                .toList();
    }

    private static List<AgentMemoryItem> survivorsOf(List<AgentMemoryItem> active, Set<String> evicted) {
        return active.stream().filter(item -> !evicted.contains(item.id())).toList();
    }

    private AgentMemoryCommitResult rejectAsConflict(AgentMemoryCommit commit,
                                                     AgentMemoryControlDO control, String watermark) {
        // 尝试次数退回上一档：快照过期不是这批内容的错，不该消耗它的重试额度
        extractionMapper.settle(commit.extractionId(), AgentMemoryExtractionStatus.CONFLICT.name(), 0,
                Math.max(commit.attemptCount() - 1, 0));
        log.info("长期记忆提交被拒, 快照已过期, userId: {}, extractionId: {}, 版本号: {} -> {}, 水位: {} -> {}",
                commit.userId(), commit.extractionId(), commit.expectedRevision(),
                control == null ? null : control.getRevision(), commit.expectedWatermark(), watermark);
        return new AgentMemoryCommitResult(AgentMemoryExtractionStatus.CONFLICT, 0, false);
    }

    /**
     * 判完没落东西照样推水位，判据是「Judge 跑完了」不是「产出了东西」
     */
    private AgentMemoryCommitResult settleEmpty(AgentMemoryCommit commit, boolean mutated) {
        settleOrThrow(commit, AgentMemoryExtractionStatus.NOOP, 0);
        return new AgentMemoryCommitResult(AgentMemoryExtractionStatus.NOOP, 0, mutated);
    }

    /**
     * 结算落空说明这次抽取已被旁人结掉，整个事务必须回滚，决不能只写一半
     */
    private void settleOrThrow(AgentMemoryCommit commit, AgentMemoryExtractionStatus status, int decisionCount) {
        if (extractionMapper.settle(commit.extractionId(), status.name(), decisionCount, commit.attemptCount()) != 1) {
            throw new IllegalStateException("长期记忆抽取已被结掉, extractionId: " + commit.extractionId());
        }
    }

    /**
     * 访问 DAO 前解析租户作用域：无执行主体直接拒绝，绝不落库。
     */
    private Scope scope() {
        ExecutionPrincipal principal = PrincipalContext.require();
        return new Scope(principal.tenantId(), principal.membershipId());
    }

    /**
     * @param tenantId 租户（键/谓词的一部分）
     * @param memberId canonical membershipId（键/谓词的一部分，权威主体引用）
     */
    private record Scope(String tenantId, String memberId) {
    }
}
