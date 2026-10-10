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

package org.ruoyi.aiweb.embedded;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.integration.SaasBoundaryProperties;
import com.nageoffer.ai.ragent.framework.integration.SaasCapabilityBoundary;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.enums.ScheduleRunStatus;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.schedule.CronScheduleHelper;
import com.nageoffer.ai.ragent.knowledge.schedule.KnowledgeDocumentScheduleJob;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleLockLease;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleLockManager;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleRefreshProcessor;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleStateContext;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleStateManager;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentScheduleServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S2-F06-A2：计划任务**机制维度**的判据（六维：cron/时区、启停、唯一触发键、错过触发、
 * 并发语义、{@code ai_knowledge_document_schedule(_exec)} 记录落档）+ 能力门 {@code DOCUMENT_SCHEDULE_*}。
 *
 * <p><b>为什么全部经装配上下文断言。</b>本切片是**装配切片**：既有类行为一字未改，
 * "应有而未有"的只有接线。因此每个维度都从装配组产出的 bean 上取机制入口
 * （{@code ScheduleLockManager} / {@code Job} / {@code Processor} / {@code StateManager} /
 * {@code KnowledgeDocumentScheduleServiceImpl} / {@code KnowledgeScheduleProperties}），
 * 装配没接线时整类红——同一份测试承担红→绿两态。协作者（mapper / 文档服务 / 文件面）
 * 是 Mockito 替身；对 wrapper 的 SQL 形态断言用 {@code TableInfoHelper} 初始化实体元数据
 * （与 {@code RW23DashboardTenantScopeTest} 同一套先例）。
 *
 * <p><b>能力门语义（P1 成文，不弱化）。</b>运行期判定一字未动：
 * 边界 bean 缺席或 legacy 关闭 ⇒ {@code ClosedCapabilityException} 抛在任何 SQL 之前；
 * 本类只在测试内注入**开放**边界做正对照（与 {@code P1TriggerBoundaryTest} 对 ES 初始化的
 * 正对照同形），并在"缺席 = 关闭"用例里复证内嵌真实形态（无 boundary bean）。
 */
@Tag("dev")
class AiEmbeddedKnowledgeScheduleMechanismTest {

    private static final String CONFIG_SIMPLE_NAME = "AiEmbeddedKnowledgeScheduleConfiguration";

    private static final String[] ALL_GATES_ON = {
            "ai.integration.enabled=true",
            "ai.integration.transport=local",
            "ai.embedded.knowledge-admin.full=true",
            "ai.integration.legacy-listeners-enabled=true"
    };

    private static final String SCHEDULE_ID = "schedule-user-1";
    private static final String DOC_ID = "doc-1";

    private final KnowledgeDocumentScheduleMapper scheduleMapper = mock(KnowledgeDocumentScheduleMapper.class);
    private final KnowledgeDocumentScheduleExecMapper execMapper =
            mock(KnowledgeDocumentScheduleExecMapper.class);
    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentServiceImpl documentService = mock(KnowledgeDocumentServiceImpl.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final RemoteFileFetcher remoteFileFetcher = mock(RemoteFileFetcher.class);
    private final Executor chunkExecutor = mock(Executor.class);
    private final List<Runnable> submitted = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void initTableInfo() {
        // 单测无 Spring/MyBatis 启动：lambda wrapper 的列名解析需要实体 TableInfo。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentScheduleDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentScheduleExecDO.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocumentDO.class);
    }

    @BeforeEach
    void recordSubmissions() {
        doAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return null;
        }).when(chunkExecutor).execute(any(Runnable.class));
    }

    // ------------------------------------------------------------ 维度 1：cron / 时区

    @Test
    @DisplayName("cron/时区：6 段 Spring cron 以 JVM 默认时区解释（UTC 与上海差恰 8h），非法表达式拒绝")
    void cronSemanticsFollowTheJvmDefaultZone() throws Exception {
        runner().withPropertyValues(ALL_GATES_ON)
                .withPropertyValues("rag.knowledge.schedule.min-interval-seconds=60")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KnowledgeScheduleProperties properties = context.getBean(KnowledgeScheduleProperties.class);
                    assertThat(properties.getMinIntervalSeconds()).isEqualTo(60L);
                    assertThat(properties.getScanDelayMs()).isEqualTo(10000L);

                    assertThat(CronScheduleHelper.nextRunTime("0 0 3 * * ?", new Date())).isNotNull();
                    assertThatThrownBy(() -> CronScheduleHelper.nextRunTime("not-a-cron", new Date()))
                            .isInstanceOf(IllegalArgumentException.class);

                    Date from = Date.from(Instant.parse("2026-10-09T18:00:00Z"));
                    TimeZone original = TimeZone.getDefault();
                    try {
                        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
                        Date utcNext = CronScheduleHelper.nextRunTime("0 0 3 * * ?", from);
                        assertThat(LocalDateTime.ofInstant(utcNext.toInstant(), ZoneOffset.UTC))
                                .isEqualTo(LocalDateTime.of(2026, 10, 10, 3, 0));

                        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
                        Date shanghaiNext = CronScheduleHelper.nextRunTime("0 0 3 * * ?", from);
                        assertThat(LocalDateTime.ofInstant(shanghaiNext.toInstant(), ZoneId.of("Asia/Shanghai")))
                                .isEqualTo(LocalDateTime.of(2026, 10, 10, 3, 0));
                        assertThat(utcNext.getTime() - shanghaiNext.getTime())
                                .as("同一 cron 在不同 JVM 默认时区下解释为不同瞬时——语义 = systemDefault")
                                .isEqualTo(8L * 3600 * 1000);
                    } finally {
                        TimeZone.setDefault(original);
                    }

                    assertThat(CronScheduleHelper.isIntervalLessThan("*/5 * * * * ?", new Date(), 60))
                            .as("5 秒间隔必须被 60 秒下限拦住").isTrue();
                    assertThat(CronScheduleHelper.isIntervalLessThan("0 0 3 * * ?", new Date(), 60))
                            .isFalse();
                });
    }

    // ------------------------------------------------------------------ 维度 2：启停

    @Test
    @DisplayName("启停：文档侧关闭时同步把 schedule 行置 enabled=0 且清空 next_run_time；开启时置 1 并重算")
    void scheduleSyncFollowsTheDocumentSwitches() throws Exception {
        runner().withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            KnowledgeDocumentScheduleServiceImpl service =
                    context.getBean(KnowledgeDocumentScheduleServiceImpl.class);
            when(scheduleMapper.selectOne(any())).thenReturn(existingScheduleRow());

            KnowledgeDocumentDO off = urlDocument();
            off.setScheduleEnabled(0);
            service.syncScheduleIfExists(off);

            ArgumentCaptor<Wrapper> offUpdate = wrapperCaptor();
            verify(scheduleMapper).update(offUpdate.capture());
            assertThat(setSql(offUpdate.getValue())).contains("enabled").contains("next_run_time");
            assertThat(params(offUpdate.getValue()).values()).contains(0);

            reset(scheduleMapper);
            when(scheduleMapper.selectOne(any())).thenReturn(existingScheduleRow());
            KnowledgeDocumentDO on = urlDocument();
            on.setScheduleEnabled(1);
            on.setScheduleCron("0 0 3 * * ?");
            service.upsertSchedule(on);

            ArgumentCaptor<Wrapper> onUpdate = wrapperCaptor();
            verify(scheduleMapper).update(onUpdate.capture());
            assertThat(params(onUpdate.getValue()).values()).contains(1);
            assertThat(params(onUpdate.getValue()).values().stream()
                    .filter(Date.class::isInstance).map(Date.class::cast).count())
                    .as("开启时 next_run_time 必须是算出来的时间，不是 null")
                    .isEqualTo(1);

            // 文档本体禁用（schedule_enabled 仍为 1）同样必须把调度落为关闭
            reset(scheduleMapper);
            when(scheduleMapper.selectOne(any())).thenReturn(existingScheduleRow());
            KnowledgeDocumentDO documentOff = urlDocument();
            documentOff.setEnabled(0);
            service.upsertSchedule(documentOff);

            ArgumentCaptor<Wrapper> documentOffUpdate = wrapperCaptor();
            verify(scheduleMapper).update(documentOffUpdate.capture());
            assertThat(params(documentOffUpdate.getValue()).values())
                    .as("文档禁用 ⇒ 调度行 enabled=0")
                    .contains(0);
        });
    }

    @Test
    @DisplayName("启停（执行面复核）：扫描后文档被禁用 ⇒ 禁用调度行、不产生执行记录")
    void processorDisablesScheduleWhenTheDocumentWasTurnedOff() throws Exception {
        withBoundary(true).withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            ScheduleRefreshProcessor processor = context.getBean(ScheduleRefreshProcessor.class);
            when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(processorScheduleRow());
            KnowledgeDocumentDO disabled = urlDocument();
            disabled.setEnabled(0);
            when(documentMapper.selectById(DOC_ID)).thenReturn(disabled);
            when(scheduleMapper.update(any())).thenReturn(1);

            processor.process(new ScheduleLockLease(SCHEDULE_ID, "lock-user-1"));

            ArgumentCaptor<Wrapper> updates = wrapperCaptor();
            verify(scheduleMapper, atLeast(1)).update(updates.capture());
            Wrapper disable = updates.getAllValues().stream()
                    .filter(wrapper -> String.valueOf(wrapper.getSqlSet()).contains("enabled"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("禁用调度行的写回缺失"));
            assertThat(setSql(disable)).contains("next_run_time");
            assertThat(params(disable).values()).contains(0);
            verify(execMapper, never()).insert(any(KnowledgeDocumentScheduleExecDO.class));
        });
    }

    @Test
    @DisplayName("启停/防抖：小于最小周期或非法 cron 在副作用前拒绝（零 mapper 交互）")
    void invalidOrTooFrequentCronIsRejectedBeforeAnyWrite() throws Exception {
        runner().withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            KnowledgeDocumentScheduleServiceImpl service =
                    context.getBean(KnowledgeDocumentScheduleServiceImpl.class);

            KnowledgeDocumentDO fast = urlDocument();
            fast.setScheduleEnabled(1);
            fast.setScheduleCron("*/5 * * * * ?");
            ClientException tooFast = assertThrows(ClientException.class, () -> service.upsertSchedule(fast));
            assertThat(tooFast.getMessage()).contains("不能小于");

            KnowledgeDocumentDO broken = urlDocument();
            broken.setScheduleEnabled(1);
            broken.setScheduleCron("not-a-cron");
            ClientException invalid = assertThrows(ClientException.class, () -> service.upsertSchedule(broken));
            assertThat(invalid.getMessage()).contains("定时表达式不合法");

            verifyNoInteractions(scheduleMapper, execMapper);
        });
    }

    // ------------------------------------------------------ 维度 3：唯一触发键（CAS）

    @Test
    @DisplayName("唯一触发键：锁认领是带 lock_until 条件的原子 CAS；并发双发恰一胜（另一发拿到 null）")
    void lockClaimIsAConcurrentSafeConditionalCas() throws Exception {
        withBoundary(true).withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            ScheduleLockManager lock = context.getBean(ScheduleLockManager.class);

            // (a) SQL 形态：set lock_owner/lock_until；where id + (lock_until is null or lock_until < now)
            when(scheduleMapper.update(any())).thenReturn(0);
            assertThat(lock.tryAcquire(SCHEDULE_ID, new Date()))
                    .as("条件 UPDATE 影响 0 行 ⇒ 未抢到（返回 null，而不是放行）")
                    .isNull();
            ArgumentCaptor<Wrapper> claim = wrapperCaptor();
            verify(scheduleMapper).update(claim.capture());
            assertThat(setSql(claim.getValue()))
                    .as("认领 = 无条件写 lock_owner/lock_until（条件在 WHERE 里）")
                    .contains("lock_owner").contains("lock_until");
            String claimWhere = whereSql(claim.getValue());
            assertThat(claimWhere)
                    .as("认领 WHERE = 本行 id + 锁窗口（未持有 或 已过期）")
                    .contains("id").containsIgnoringCase("lock_until is null").contains("<");

            // (b) 并发双发：把 DB 条件 UPDATE 的原子性建模成"先到者 1、后到者 0"
            reset(scheduleMapper);
            AtomicInteger grants = new AtomicInteger(1);
            when(scheduleMapper.update(any())).thenAnswer(invocation -> grants.getAndSet(0));
            AtomicReference<ScheduleLockLease> first = new AtomicReference<>();
            AtomicReference<ScheduleLockLease> second = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            Thread one = claimThread(lock, start, first);
            Thread two = claimThread(lock, start, second);
            one.start();
            two.start();
            start.countDown();
            one.join();
            two.join();
            List<ScheduleLockLease> claimed = Stream.of(first.get(), second.get())
                    .filter(Objects::nonNull).toList();
            assertThat(claimed).as("并发双发只允许一个 lease 胜出").hasSize(1);

            // (c) 续约/释放同样带 token 守卫（where 必含 lock_owner = 本 lease token）
            reset(scheduleMapper);
            when(scheduleMapper.update(any())).thenReturn(1);
            ScheduleLockLease lease = lock.tryAcquire(SCHEDULE_ID, new Date());
            assertThat(lease).isNotNull();
            assertThat(lock.renew(lease)).isTrue();
            assertThat(lock.release(lease)).isTrue();
            ArgumentCaptor<Wrapper> guarded = wrapperCaptor();
            verify(scheduleMapper, times(3)).update(guarded.capture());
            Wrapper granted = guarded.getAllValues().get(0);
            assertThat(setSql(granted)).contains("lock_owner");
            assertThat(params(granted).values()).contains(lease.lockToken());
            for (Wrapper guardedByToken : guarded.getAllValues().subList(1, 3)) {
                assertThat(whereSql(guardedByToken))
                        .as("续约/释放 WHERE 必须带 lock_owner = 本 lease token（不是只按 id）")
                        .contains("lock_owner");
                assertThat(params(guardedByToken).values()).contains(lease.lockToken());
            }
            assertThat(setSql(guarded.getAllValues().get(2)))
                    .as("释放 = 把 lock_owner/lock_until 置空").contains("lock_owner").contains("lock_until");
        });
    }

    // ---------------------------------------------------------------- 维度 4：错过触发

    @Test
    @DisplayName("错过触发：扫描查询取 enabled=1 且 next_run_time<=now 的过期行（含锁空闲过滤、排序、限批），一拍恰提交一次")
    void scanCatchesOverdueRowsAndSubmitsOncePerTick() throws Exception {
        withBoundary(true).withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            when(scheduleMapper.selectList(any())).thenReturn(List.of(overdueScheduleRow()));
            when(scheduleMapper.update(any())).thenReturn(1);

            context.getBean(KnowledgeDocumentScheduleJob.class).scan();

            ArgumentCaptor<Wrapper> query = wrapperCaptor();
            verify(scheduleMapper).selectList(query.capture());
            String sql = whereSql(query.getValue());
            assertThat(sql)
                    .as("过期多久都在下个 tick 被捞起：<= now + enabled + 锁空闲 + 排序限批")
                    .contains("enabled").contains("next_run_time").contains("<=")
                    .contains("lock_until").containsIgnoringCase("is null")
                    .containsIgnoringCase("order by next_run_time")
                    .contains("LIMIT 20");
            assertThat(submitted).as("一拍之内每个过期行只提交一次").hasSize(1);
        });
    }

    @Test
    @DisplayName("错过触发补跑后 next 从本次起算（不回放错过的拍）＋ exec 记录 RUNNING→SKIPPED 落档")
    void processorRecomputesNextFromStartTimeAndFinalizesExecRecord() throws Exception {
        withBoundary(true).withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            ScheduleRefreshProcessor processor = context.getBean(ScheduleRefreshProcessor.class);
            when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(processorScheduleRow());
            when(documentMapper.selectById(DOC_ID)).thenReturn(urlDocument());
            when(scheduleMapper.update(any())).thenReturn(1);
            when(execMapper.insert(any(KnowledgeDocumentScheduleExecDO.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, KnowledgeDocumentScheduleExecDO.class).setId("exec-1");
                return 1;
            });
            when(remoteFileFetcher.fetchIfChanged(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(RemoteFileFetcher.RemoteFetchResult.skipped(
                            "远程文件未变化", "etag-1", "last-modified-1", "hash-1"));

            Date startedAfter = new Date();
            processor.process(new ScheduleLockLease(SCHEDULE_ID, "lock-user-1"));

            ArgumentCaptor<KnowledgeDocumentScheduleExecDO> inserted =
                    ArgumentCaptor.forClass(KnowledgeDocumentScheduleExecDO.class);
            verify(execMapper).insert(inserted.capture());
            assertThat(inserted.getValue().getStatus())
                    .as("启动即落 RUNNING 执行记录（先落档后干活）")
                    .isEqualTo(ScheduleRunStatus.RUNNING.getCode());
            assertThat(inserted.getValue().getStartTime()).isNotNull();
            assertThat(inserted.getValue().getScheduleId()).isEqualTo(SCHEDULE_ID);

            ArgumentCaptor<KnowledgeDocumentScheduleExecDO> finished =
                    ArgumentCaptor.forClass(KnowledgeDocumentScheduleExecDO.class);
            verify(execMapper).updateById(finished.capture());
            assertThat(finished.getValue().getStatus()).isEqualTo(ScheduleRunStatus.SKIPPED.getCode());
            assertThat(finished.getValue().getMessage()).isEqualTo("远程文件未变化");
            assertThat(finished.getValue().getEndTime()).isNotNull();
            assertThat(finished.getValue().getEtag()).isEqualTo("etag-1");

            ArgumentCaptor<Wrapper> updates = wrapperCaptor();
            verify(scheduleMapper, atLeast(1)).update(updates.capture());
            Wrapper mainLedgerUpdate = updates.getAllValues().stream()
                    .filter(wrapper -> String.valueOf(wrapper.getSqlSet()).contains("last_status"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("主状态写回缺失：没有 last_status 的 update"));
            assertThat(params(mainLedgerUpdate).values()).contains(ScheduleRunStatus.SKIPPED.getCode());
            assertThat(params(mainLedgerUpdate).values().stream()
                    .filter(Date.class::isInstance).map(Date.class::cast)
                    .anyMatch(date -> date.after(startedAfter)))
                    .as("next_run_time 从本次 startTime 起算（未来），不是错过点回放")
                    .isTrue();
        });
    }

    // ---------------------------------------------------------------- 维度 5：并发语义

    @Test
    @DisplayName("并发语义：CAS 输者不得提交；线程池拒绝时释放锁供下拍重试")
    void claimLosersAreSkippedAndRejectedExecutionReleasesTheLock() throws Exception {
        withBoundary(true).withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            KnowledgeDocumentScheduleJob job = context.getBean(KnowledgeDocumentScheduleJob.class);

            when(scheduleMapper.selectList(any())).thenReturn(List.of(overdueScheduleRow()));
            when(scheduleMapper.update(any())).thenReturn(0);
            job.scan();
            assertThat(submitted).as("CAS 输 ⇒ 不提交").isEmpty();

            reset(scheduleMapper);
            when(scheduleMapper.selectList(any())).thenReturn(List.of(overdueScheduleRow()));
            when(scheduleMapper.update(any())).thenReturn(1);
            doThrow(new RejectedExecutionException("queue full"))
                    .when(chunkExecutor).execute(any(Runnable.class));
            job.scan();

            ArgumentCaptor<Wrapper> updates = wrapperCaptor();
            verify(scheduleMapper, times(2)).update(updates.capture());
            Wrapper release = updates.getAllValues().get(1);
            assertThat(setSql(release)).as("释放 = lock_owner/lock_until 置空").contains("lock_owner");
            assertThat(whereSql(release)).contains("lock_owner");
            assertThat(params(release).values().stream().map(String::valueOf)
                    .anyMatch(value -> value.startsWith("kb-schedule-")))
                    .as("释放必须带本次 lease 的实例 token")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("并发语义：主行写回失败（锁已易主）时 exec 记录如实标注，不冒充已写回")
    void leaseLossKeepsTheExecRecordHonest() throws Exception {
        withBoundary(true).withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            ScheduleStateManager stateManager = context.getBean(ScheduleStateManager.class);
            when(scheduleMapper.update(any())).thenReturn(0);

            ScheduleStateContext state = ScheduleStateContext.builder()
                    .scheduleId(SCHEDULE_ID).execId("exec-1")
                    .cronExpr("0 0/5 * * * ?")
                    .startTime(new Date())
                    .nextRunTime(Date.from(Instant.now().plusSeconds(600)))
                    .build();
            StoredFileDTO stored = StoredFileDTO.builder()
                    .url("https://new-file").detectedType("pdf").size(128L)
                    .originalFilename("remote.pdf").build();
            RemoteFileFetcher.RemoteFetchResult fetchResult = RemoteFileFetcher.RemoteFetchResult.changed(
                    Path.of("."), 1L, "application/pdf", "remote.pdf", "h", "e", "lm");

            boolean updated = stateManager.markSuccessIfOwned(
                    new ScheduleLockLease(SCHEDULE_ID, "lock-user-1"), state, fetchResult, stored);
            assertThat(updated).isFalse();

            ArgumentCaptor<KnowledgeDocumentScheduleExecDO> exec =
                    ArgumentCaptor.forClass(KnowledgeDocumentScheduleExecDO.class);
            verify(execMapper).updateById(exec.capture());
            assertThat(exec.getValue().getMessage())
                    .as("锁已失效 ⇒ exec 记录带显式注记（原文：调度锁已失效，未写回调度状态）")
                    .contains("调度锁已失效");
            assertThat(exec.getValue().getStatus()).isEqualTo(ScheduleRunStatus.SUCCESS.getCode());

            reset(execMapper);
            stateManager.markLeaseLost(state, "执行文档分块");
            ArgumentCaptor<KnowledgeDocumentScheduleExecDO> lost =
                    ArgumentCaptor.forClass(KnowledgeDocumentScheduleExecDO.class);
            verify(execMapper).updateById(lost.capture());
            assertThat(lost.getValue().getStatus()).isEqualTo(ScheduleRunStatus.FAILED.getCode());
            assertThat(lost.getValue().getMessage()).contains("调度锁已失效，终止执行").contains("执行文档分块");
        });
    }

    // ---------------------------------------------------------------- 维度 6：能力门

    @Test
    @DisplayName("能力门：开放边界（测试注入）时 scan 才到达账本查询；关闭/缺席一律在 SQL 前受控拒绝")
    void capabilityBoundaryGatesTheScanExactlyAsDocumented() throws Exception {
        // (a) 开放：正对照，证明"关闭态零交互"断言不是空转
        withBoundary(true).withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            when(scheduleMapper.selectList(any())).thenReturn(List.of());
            context.getBean(KnowledgeDocumentScheduleJob.class).scan();
            verify(scheduleMapper).selectList(any());
        });

        // (b) 显式关闭：受控异常 + 零交互（与 P1TriggerBoundaryTest 同一契约，重复不弱化）
        reset(scheduleMapper, documentMapper, chunkExecutor);
        withBoundary(false).withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            KnowledgeDocumentScheduleJob job = context.getBean(KnowledgeDocumentScheduleJob.class);
            SaasCapabilityBoundary.ClosedCapabilityException scanFailure = assertThrows(
                    SaasCapabilityBoundary.ClosedCapabilityException.class, job::scan);
            assertThat(scanFailure.capability())
                    .isEqualTo(SaasCapabilityBoundary.LegacyCapability.DOCUMENT_SCHEDULE_SCAN);
            SaasCapabilityBoundary.ClosedCapabilityException recoverFailure = assertThrows(
                    SaasCapabilityBoundary.ClosedCapabilityException.class,
                    job::recoverStuckRunningDocuments);
            assertThat(recoverFailure.capability())
                    .isEqualTo(SaasCapabilityBoundary.LegacyCapability.DOCUMENT_SCHEDULE_RECOVER);
            verifyNoInteractions(scheduleMapper, documentMapper, chunkExecutor);
        });

        // (c) 缺席按关闭：内嵌真实形态没有 SaasCapabilityBoundary bean（P1 成文语义）
        reset(scheduleMapper);
        runner().withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(KnowledgeDocumentScheduleJob.class).scan())
                    .isInstanceOf(SaasCapabilityBoundary.ClosedCapabilityException.class);
            verifyNoInteractions(scheduleMapper);
        });
    }

    // ------------------------------------------------------------------ 夹具与助手

    private ApplicationContextRunner runner() throws Exception {
        return new ApplicationContextRunner()
                // 裸 runner 缺 Boot 转换服务：替身上的 @Value DataSize 字段
                // （RemoteFileFetcher.maxFileSize）会在 bean 后处理阶段触发 String→DataSize 转换；
                // 没有转换器时容器起不来——那是夹具缺陷，不是被测事实。
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(scheduleConfigurationClass())
                // 只登记、不执行：@Scheduled 的登记面照常成立，背景 scan 永不与计数断言竞态。
                .withBean(TaskScheduler.class, AiEmbeddedKnowledgeScheduleMechanismTest::nonExecutingTaskScheduler)
                .withBean("knowledgeChunkExecutor", Executor.class, () -> chunkExecutor)
                .withBean(KnowledgeDocumentScheduleMapper.class, () -> scheduleMapper)
                .withBean(KnowledgeDocumentScheduleExecMapper.class, () -> execMapper)
                .withBean(KnowledgeDocumentMapper.class, () -> documentMapper)
                .withBean(KnowledgeBaseMapper.class, () -> kbMapper)
                .withBean(KnowledgeDocumentServiceImpl.class, () -> documentService)
                .withBean(FileStorageService.class, () -> fileStorageService)
                .withBean(RemoteFileFetcher.class, () -> remoteFileFetcher);
    }

    private static TaskScheduler nonExecutingTaskScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> neverRuns = mock(ScheduledFuture.class);
        doReturn(neverRuns).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));
        doReturn(neverRuns).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        doReturn(neverRuns).when(scheduler).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), any(Duration.class));
        doReturn(neverRuns).when(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        doReturn(neverRuns).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), any(Duration.class));
        doReturn(neverRuns).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        return scheduler;
    }

    private ApplicationContextRunner withBoundary(boolean legacyOpen) throws Exception {
        // 只登记边界本体：SaasBoundaryProperties 自带 @ConfigurationProperties，
        // 作为 bean 会被绑定后处理器再绑一次（record 无 setter ⇒ 启动失败）——
        // 那是夹具形态错误，不是被测事实；边界直接以构造参数持有属性。
        return runner()
                .withBean(SaasCapabilityBoundary.class, () -> new SaasCapabilityBoundary(
                        new SaasBoundaryProperties(false, true,
                                new SaasBoundaryProperties.CustomerApi(false), legacyOpen)));
    }

    private static Thread claimThread(ScheduleLockManager lock, CountDownLatch start,
                                      AtomicReference<ScheduleLockLease> outcome) {
        return new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            outcome.set(lock.tryAcquire(SCHEDULE_ID, new Date()));
        });
    }

    private static Class<?> scheduleConfigurationClass() throws IOException, ClassNotFoundException {
        List<String> matching = Files.readAllLines(locateImports()).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .filter(entry -> entry.endsWith("." + CONFIG_SIMPLE_NAME))
                .toList();
        assertThat(matching)
                .as("AutoConfiguration.imports 必须登记计划任务装配组 %s（缺行 = 接线没发生）", CONFIG_SIMPLE_NAME)
                .hasSize(1);
        return Class.forName(matching.get(0));
    }

    private static Path locateImports() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("services/platform/ruoyi-modules/ruoyi-ai-web/src/main/resources/"
                    + "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("cannot locate embedded AutoConfiguration.imports");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    private static String whereSql(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getSqlSegment();
    }

    private static Map<String, Object> params(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    private static String setSql(Wrapper<?> wrapper) {
        return String.valueOf(wrapper.getSqlSet());
    }

    private static KnowledgeDocumentDO urlDocument() {
        return KnowledgeDocumentDO.builder()
                .id(DOC_ID)
                .kbId("kb-1")
                .docName("doc.pdf")
                .sourceType(SourceType.URL.getValue())
                .sourceLocation("https://example.com/file.pdf")
                .scheduleEnabled(1)
                .scheduleCron("0 0/5 * * * ?")
                .enabled(1)
                .deleted(0)
                .status(DocumentStatus.SUCCESS.getCode())
                .fileUrl("https://old-file")
                .build();
    }

    private static KnowledgeDocumentScheduleDO existingScheduleRow() {
        return KnowledgeDocumentScheduleDO.builder()
                .id(SCHEDULE_ID)
                .docId(DOC_ID)
                .kbId("kb-1")
                .build();
    }

    private static KnowledgeDocumentScheduleDO overdueScheduleRow() {
        return KnowledgeDocumentScheduleDO.builder()
                .id(SCHEDULE_ID)
                .docId(DOC_ID)
                .kbId("kb-1")
                .enabled(1)
                .cronExpr("0 0/5 * * * ?")
                .nextRunTime(new Date(System.currentTimeMillis() - 300_000L))
                .build();
    }

    private static KnowledgeDocumentScheduleDO processorScheduleRow() {
        return KnowledgeDocumentScheduleDO.builder()
                .id(SCHEDULE_ID)
                .docId(DOC_ID)
                .kbId("kb-1")
                .cronExpr("0 0/5 * * * ?")
                .lastEtag("etag-prev")
                .lastModified("last-modified-prev")
                .lastContentHash("hash-prev")
                .build();
    }
}
