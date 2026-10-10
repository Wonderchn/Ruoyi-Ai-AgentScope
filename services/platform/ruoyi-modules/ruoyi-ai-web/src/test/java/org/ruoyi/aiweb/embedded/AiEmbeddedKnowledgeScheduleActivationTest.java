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

import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import com.nageoffer.ai.ragent.knowledge.schedule.KnowledgeDocumentScheduleJob;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleLockManager;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleRefreshProcessor;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleStateManager;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentScheduleServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * S2-F06-A2：计划任务（{@code /knowledge/schedule} 机制）内嵌装配的**激活**判据。
 *
 * <p><b>为什么本类在装配前必须红。</b>与 {@code AiEmbeddedIngestionActivationTest} 同形，
 * 但多一道"登记面"判据：本类不直接 import 新装配组，而是**从真实
 * {@code AutoConfiguration.imports} 里读出登记名再 {@code Class.forName}**
 * —— 装配没接线时先在这里红（清单缺行），接线后转向组内门控与 bean 齐缺断言。
 * 这样"红→绿"两态跑的是**同一份测试**，不靠事后改测试制造绿灯。
 *
 * <p><b>门控先例（树内成文）。</b>装配条件必须**包含** FullAdmin 闭包条件
 * （{@code ai.embedded.knowledge-admin.full=true}：刷新处理器构造依赖
 * {@code KnowledgeDocumentServiceImpl} 大闭包，见 {@code AiEmbeddedKnowledgeAdminConfiguration}
 * 类注释），并额外带运行期同一判定所用的旧能力开关
 * （{@code ai.integration.legacy-listeners-enabled=true}：与
 * {@code AiEmbeddedFeedbackConfiguration.LegacyFeedbackConsumerAssembly} "装配开关同值"
 * 先例一致）。默认交付：零 bean、零调度、零副作用。
 */
@Tag("dev")
class AiEmbeddedKnowledgeScheduleActivationTest {

    /** 计划任务装配组的登记名后缀；缺行 = 接线没发生（红色判据的锚点）。 */
    private static final String CONFIG_SIMPLE_NAME = "AiEmbeddedKnowledgeScheduleConfiguration";

    /** 四个开关全开：master + local 传输 + FullAdmin 闭包门 + 旧能力门（同值先例）。 */
    private static final String[] ALL_GATES_ON = {
            "ai.integration.enabled=true",
            "ai.integration.transport=local",
            "ai.embedded.knowledge-admin.full=true",
            "ai.integration.legacy-listeners-enabled=true"
    };

    @Test
    @DisplayName("四个开关全开：计划任务组六个 bean 齐 + knowledgeChunkExecutor + 两个 @Scheduled 真的登记")
    void scheduleChainIsFullyAssembledWhenAllGatesAreOn() throws Exception {
        withCollaborators().withPropertyValues(ALL_GATES_ON).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KnowledgeDocumentScheduleJob.class);
            assertThat(context).hasSingleBean(ScheduleLockManager.class);
            assertThat(context).hasSingleBean(ScheduleStateManager.class);
            assertThat(context).hasSingleBean(ScheduleRefreshProcessor.class);
            assertThat(context).hasSingleBean(DocumentStatusHelper.class);
            assertThat(context).hasSingleBean(KnowledgeDocumentScheduleServiceImpl.class);
            assertThat(context).hasSingleBean(KnowledgeScheduleProperties.class);
            assertThat(context).hasBean("knowledgeChunkExecutor");

            ScheduledTaskHolder holder = context.getBean(ScheduledTaskHolder.class);
            List<String> runnables = holder.getScheduledTasks().stream()
                    .map(task -> task.getTask().getRunnable().toString())
                    .toList();
            assertThat(runnables)
                    .as("@Scheduled 需要 @EnableScheduling：scan 与 recoverStuckRunningDocuments "
                            + "必须真的成为调度任务，而不是只有注解")
                    .anyMatch(name -> name.contains("KnowledgeDocumentScheduleJob.scan"))
                    .anyMatch(name -> name.contains("KnowledgeDocumentScheduleJob.recoverStuckRunningDocuments"));
        });
    }

    @Test
    @DisplayName("任一开关关闭：整组缺席（含 Scheduling 本身），且不要求协作对象在场")
    void everyGateClosesTheWholeScheduleChain() throws Exception {
        String[][] offCases = {
                {"ai.integration.enabled=false"},
                {"ai.integration.transport=http"},
                {"ai.embedded.knowledge-admin.full=false"},
                {"ai.integration.legacy-listeners-enabled=false"}
        };
        for (String[] off : offCases) {
            // 关态用**无协作替身**的上下文：开关关着 = 连 bean 都不是（先例原文），
            // 不允许出现"关掉开关的应用起不来"。
            baseRunner()
                    .withUserConfiguration(scheduleConfigurationClass())
                    .withPropertyValues(without(ALL_GATES_ON, off[0]))
                    .run(context -> {
                        assertThat(context)
                                .as("门控 %s 关闭时必须整体缺席", off[0])
                                .hasNotFailed();
                        assertThat(context)
                                .doesNotHaveBean(KnowledgeDocumentScheduleJob.class)
                                .doesNotHaveBean(ScheduleLockManager.class)
                                .doesNotHaveBean(ScheduleStateManager.class)
                                .doesNotHaveBean(ScheduleRefreshProcessor.class)
                                .doesNotHaveBean(DocumentStatusHelper.class)
                                .doesNotHaveBean(KnowledgeDocumentScheduleServiceImpl.class)
                                .doesNotHaveBean(KnowledgeScheduleProperties.class)
                                .doesNotHaveBean("knowledgeChunkExecutor")
                                .doesNotHaveBean(ScheduledTaskHolder.class);
                    });
        }
    }

    @Test
    @DisplayName("门控超集：本组条件包含 FullAdmin 闭包条件，并额外包含 legacy 同值门与 local 传输")
    void scheduleGroupCarriesTheFullAdminClosureGatePlusLegacyGate() throws Exception {
        Class<?> scheduleEnabled = nested(nested(scheduleConfigurationClass(), "LocalTransport"),
                "ScheduleEnabled");
        Class<?> fullAdmin = nested(AiEmbeddedKnowledgeAdminConfiguration.class, "FullAdmin");

        assertThat(scheduleEnabled.isAnnotationPresent(ConditionalOnEmbeddedLocal.class))
                .as("本组必须自带 local 传输门（嵌套类不会被外层条件覆盖）")
                .isTrue();
        assertThat(fullAdmin.isAnnotationPresent(ConditionalOnEmbeddedLocal.class)).isTrue();

        assertThat(conditionProperties(scheduleEnabled))
                .as("刷新处理器构造依赖 KnowledgeDocumentServiceImpl 闭包——本组条件必须包含其归属开关；"
                        + "再叠加运行期判定同值的旧能力开关")
                .containsAll(conditionProperties(fullAdmin))
                .contains("ai.embedded.knowledge-admin.full=true",
                        "ai.integration.legacy-listeners-enabled=true");
    }

    @Test
    @DisplayName("属性绑定：rag.knowledge.schedule.* 经同一组进 KnowledgeScheduleProperties；未覆盖=代码默认")
    void schedulePropertiesAreBoundThroughTheSameGroup() throws Exception {
        withCollaborators().withPropertyValues(ALL_GATES_ON)
                .withPropertyValues("rag.knowledge.schedule.scan-delay-ms=1234",
                        "rag.knowledge.schedule.batch-size=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KnowledgeScheduleProperties bound = context.getBean(KnowledgeScheduleProperties.class);
                    assertThat(bound.getScanDelayMs()).isEqualTo(1234L);
                    assertThat(bound.getBatchSize()).isEqualTo(7);
                    assertThat(bound.getLockSeconds()).as("未覆盖项保持代码默认").isEqualTo(900L);
                    assertThat(bound.getMinIntervalSeconds()).isEqualTo(60L);
                    assertThat(bound.getRunningTimeoutMinutes()).isEqualTo(30L);
                });
    }

    // ------------------------------------------------------------------ 夹具与助手

    /**
     * 只满足构造依赖的替身；正例里协作面不参与行为断言。
     * 刻意**不注册** {@code SaasCapabilityBoundary}：内嵌真实形态里没有该 bean，
     * 运行期判定按"缺席 = 关闭"处理（P1 成文语义）。
     */
    private ApplicationContextRunner withCollaborators() throws Exception {
        return baseRunner()
                .withUserConfiguration(scheduleConfigurationClass())
                .withBean(TaskScheduler.class, AiEmbeddedKnowledgeScheduleActivationTest::nonExecutingTaskScheduler)
                .withBean(KnowledgeDocumentScheduleMapper.class, () -> mock(KnowledgeDocumentScheduleMapper.class))
                .withBean(KnowledgeDocumentScheduleExecMapper.class,
                        () -> mock(KnowledgeDocumentScheduleExecMapper.class))
                .withBean(KnowledgeDocumentMapper.class, () -> mock(KnowledgeDocumentMapper.class))
                .withBean(KnowledgeBaseMapper.class, () -> mock(KnowledgeBaseMapper.class))
                .withBean(KnowledgeDocumentServiceImpl.class, () -> mock(KnowledgeDocumentServiceImpl.class))
                .withBean(FileStorageService.class, () -> mock(FileStorageService.class))
                .withBean(RemoteFileFetcher.class, () -> mock(RemoteFileFetcher.class));
    }

    /**
     * 裸 runner 缺 Boot 的转换服务：替身类上的 {@code @Value DataSize} 字段会在
     * bean 后处理阶段被注入并触发 String→DataSize 转换（{@code RemoteFileFetcher.maxFileSize}），
     * 没有转换器时容器起不来——那是夹具缺陷，不是被测事实。给上下文装上 Boot 的
     * 应用转换服务，与 {@code SpringApplication} 启动路径一致。
     */
    private static ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()));
    }

    /**
     * 只登记、不执行的调度器替身：{@code @Scheduled} 的登记面（{@link ScheduledTaskHolder}）
     * 照常成立，但任务永不真正开跑——否则背景 scan 会与断言竞态（判据要的是"接线被钉住"，
     * 不是"背景线程碰巧没抢跑"）。
     */
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

    /** 从真实 imports 清单解析登记名并加载装配组；缺行时以带语义的断言红，而不是静默跳过。 */
    private static Class<?> scheduleConfigurationClass() throws IOException, ClassNotFoundException {
        List<String> matching = importsEntries().stream()
                .filter(entry -> entry.endsWith("." + CONFIG_SIMPLE_NAME))
                .toList();
        assertThat(matching)
                .as("AutoConfiguration.imports 必须登记计划任务装配组 %s"
                        + "（缺行 = 调度接线没有发生；登记名对不上 = 行写错了）", CONFIG_SIMPLE_NAME)
                .hasSize(1);
        return Class.forName(matching.get(0));
    }

    private static List<String> importsEntries() throws IOException {
        return Files.readAllLines(locateImports()).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
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

    private static Class<?> nested(Class<?> owner, String simpleName) {
        return Arrays.stream(owner.getDeclaredClasses())
                .filter(candidate -> candidate.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        owner.getName() + " 缺嵌套组 " + simpleName + "（装配形态被改；不允许静默跳过）"));
    }

    private static Set<String> conditionProperties(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (ConditionalOnProperty annotation : type.getAnnotationsByType(ConditionalOnProperty.class)) {
            out.add(String.join(",", annotation.name()) + "=" + annotation.havingValue());
        }
        return out;
    }

    /** 把基准开关集里的某键替换为关闭值。 */
    private static String[] without(String[] base, String override) {
        String key = override.substring(0, override.indexOf('='));
        List<String> out = new ArrayList<>();
        for (String property : base) {
            if (!property.startsWith(key + "=")) {
                out.add(property);
            }
        }
        out.add(override);
        return out.toArray(String[]::new);
    }
}
