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

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.alibaba.ttl.threadpool.TtlExecutors;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.schedule.DocumentStatusHelper;
import com.nageoffer.ai.ragent.knowledge.schedule.KnowledgeDocumentScheduleJob;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleLockManager;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleRefreshProcessor;
import com.nageoffer.ai.ragent.knowledge.schedule.ScheduleStateManager;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentScheduleServiceImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 知识库文档**计划任务**（定时刷新）机制的内嵌装配（S2-F06-A2，2026-10-10；已批 OP3 范围）。
 *
 * <p><b>为什么需要这一组。</b>{@link KnowledgeDocumentScheduleJob} /
 * {@link ScheduleLockManager} / {@link ScheduleStateManager} / {@link ScheduleRefreshProcessor} /
 * {@link DocumentStatusHelper} / {@link KnowledgeDocumentScheduleServiceImpl} 都在
 * {@code com.nageoffer.ai.ragent.knowledge.**} 下且是 {@code @Component}/{@code @Service}，
 * 而 platform 应用的扫描根是 {@code org.ruoyi} ⇒ 内嵌形态里它们<b>从来不是 bean</b>；
 * 同时两个 {@code @Scheduled} 入口需要 {@code @EnableScheduling}，而全 platform 只有
 * {@code SnailJobConfig}（门控 {@code snail-job.enabled}，内嵌不用）与
 * {@code AiEmbeddedWorkerConfiguration.WorkerEnabled}（P2 worker 组）打开过调度。
 * 本类必须出现在
 * {@code ruoyi-ai-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 才生效（已同批追加）。
 *
 * <p><b>闭包逐项核过（不是"看着差不多就装"）。</b>
 * <ul>
 *   <li>{@link ScheduleLockManager}/{@link ScheduleStateManager}/{@code DocumentStatusHelper} ←
 *       两个 schedule mapper + {@code KnowledgeDocumentMapper}
 *       （{@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 数组已含
 *       {@code knowledge.dao.mapper}）+ {@link KnowledgeScheduleProperties}；</li>
 *   <li>{@code KnowledgeDocumentScheduleServiceImpl} ← 两个 schedule mapper；</li>
 *   <li><b>整条链的闭包瓶颈在</b> {@link ScheduleRefreshProcessor}：其构造依赖
 *       {@code KnowledgeDocumentServiceImpl}（22 依赖：ParserRegistry / IngestionKernel /
 *       IngestionEngine / ChunkIndexWriter / FileStorageService / RemoteFileFetcher /
 *       MessageQueueProducer / IngestionPipelineService / …，逐项见
 *       {@code AiEmbeddedKnowledgeAdminConfiguration} 类注释）。该大闭包的归属开关是
 *       {@code ai.embedded.knowledge-admin.full=true}（同一注释原文："需要打开时把
 *       {@code ai.embedded.knowledge-admin.full=true} 并同时补齐上述闭包；缺 bean 时启动会响亮失败"）。
 *       ⇒ 本组条件必须**包含** FullAdmin 条件（同一闭包不拆两套门；与
 *       {@code AiEmbeddedWorkerConfigurationTest.agentRunGroupSharesTheSameGatesAsTheP3ActionGroup}
 *       的"依赖组的条件必须被包含"纪律同形）。</li>
 * </ul>
 *
 * <p><b>能力门处置（按树内成文先例，不新造放行口）。</b>运行期判定一字未动：
 * {@code KnowledgeDocumentScheduleJob.scan()/recoverStuckRunningDocuments()} 第一行仍调用
 * {@code SaasCapabilityBoundary.requireOpenOrClosed(…, DOCUMENT_SCHEDULE_SCAN/RECOVER)}，
 * 且内嵌形态没有 {@code SaasCapabilityBoundary} bean ⇒"缺席 = 关闭"（P1 成文语义，
 * {@code P1TriggerBoundaryTest} 钉住，不得弱化）。本组只把**装配开关**与运行期判定取同值
 * （{@code ai.integration.legacy-listeners-enabled=true}），与
 * {@code AiEmbeddedFeedbackConfiguration.LegacyFeedbackConsumerAssembly} 的
 * "装配开关同值 + 运行期同一关闭判定 + boundary 缺席按关闭"逐字一致。
 * ⇒ 默认交付（shipped {@code application-embedded.yml} 不含该键）：**不装配、不调度、零副作用**；
 * "打开旧能力"仍是显式部署决策（P1 口径：真开放属 canonical/action 能力表接续），
 * 不在本装配里静默补齐，也不新增任何放行开关。
 *
 * <p><b>本组不新增任何公开面。</b>零端点 / 零 ROUTES（启停面仍是
 * {@code KnowledgeDocumentController} 的既有 PATCH/PUT，随 FullAdmin 闭包，不在此重复登记）/
 * 零菜单号 / 零迁移 / 零 canonical 动作；幂等沿受理幂等旁证（W6 §1 F06-op3 口径），
 * 调度侧自身的防重是既有"唯一触发键"（扫描过滤 + 锁 CAS，本切片以测试钉住，不改机制）。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedKnowledgeScheduleConfiguration {

    /** 本地传输下的计划任务链装配。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    public static class LocalTransport {

        /**
         * 计划任务链门控：FullAdmin 闭包开关（刷新处理器依赖知识文档服务大闭包）
         * + 旧能力同值装配开关（默认关闭，见类注释）。
         *
         * <p>{@code @EnableScheduling} 放在门控组内部而不是全局（与
         * {@code AiEmbeddedWorkerConfiguration} 同形）：只有这条链真要跑时才打开调度，
         * 不改变 platform 其它模块的既有调度姿态。
         *
         * <p>{@code @EnableConfigurationProperties(KnowledgeScheduleProperties.class)}：
         * 该类在独立应用里靠组件扫描成为 bean，内嵌形态必须在此显式登记，
         * {@code scan-delay-ms / lock-seconds / batch-size / min-interval-seconds /
         * running-timeout-minutes} 才按 {@code rag.knowledge.schedule.*} 生效。
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEmbeddedLocal
        @ConditionalOnProperty(name = "ai.embedded.knowledge-admin.full", havingValue = "true")
        @ConditionalOnProperty(name = "ai.integration.legacy-listeners-enabled", havingValue = "true")
        @EnableConfigurationProperties(KnowledgeScheduleProperties.class)
        @EnableScheduling
        @Import({KnowledgeDocumentScheduleJob.class,
                ScheduleLockManager.class,
                ScheduleStateManager.class,
                ScheduleRefreshProcessor.class,
                DocumentStatusHelper.class,
                KnowledgeDocumentScheduleServiceImpl.class})
        public static class ScheduleEnabled {

            /**
             * 计划任务提交线程池：镜像独立应用
             * {@code ThreadPoolExecutorConfig#knowledgeChunkExecutor}（同名同型，
             * 尺寸/队列/拒绝策略/TTL 包装逐项一致），让内嵌形态不用引入整个线程池配置类
             * （那会连带注册 RAG 上下文/模型流等 6 个与本链无关的池）。
             *
             * <p>用"名字"而不是返回类型做缺省判定：构造注入点
             * （{@code KnowledgeDocumentScheduleJob.knowledgeChunkExecutor}）是按名字解析的。
             */
            @Bean(name = "knowledgeChunkExecutor")
            @ConditionalOnMissingBean(name = "knowledgeChunkExecutor")
            public Executor knowledgeChunkExecutor() {
                int cpuCount = Runtime.getRuntime().availableProcessors();
                ThreadPoolExecutor executor = new ThreadPoolExecutor(
                        Math.max(2, cpuCount >> 1),
                        Math.max(4, cpuCount),
                        60,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(200),
                        ThreadFactoryBuilder.create()
                                .setNamePrefix("kb_chunk_executor_")
                                .build(),
                        new ThreadPoolExecutor.AbortPolicy()
                );
                return TtlExecutors.getTtlExecutor(executor);
            }
        }
    }
}
