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

package com.nageoffer.ai.ragent.authorization;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F16 op1 债卡 #10 · 池化线程主体残留负例（P3 §3 判据形态，红先行）。
 *
 * <p><b>机理（P3 §3 唯一与全部证据相容的候选）。</b>{@code PrincipalContext.CONTEXT} 是
 * {@code TransmittableThreadLocal}（继承 {@code InheritableThreadLocal}）：即使没有任何
 * TTL 装饰，线程在<b>创建时刻</b>也会拷贝父线程的值；reactor boundedElastic 的 worker
 * 惰性创建于触发池增长的提交线程。于是"带主体的请求线程创建了 worker"这一事实，
 * 会让该 worker 在其后所有任务上一直持有创建者的完整主体对象（含版本、签发信息）。
 * 真机旁证：r17/f10p1-win/app-6044.log 中 boundedElastic 线程的<b>首个</b>有日志任务
 * 即通过 {@code PrincipalContext.require()}——此前不可能有本线程的显式绑定。
 *
 * <p><b>判据（§3 原文形状，私有 boundedElastic）。</b>线程 A（持 P1）触发 worker 创建；
 * 清空后干净线程 B（无主体）提交新任务 → 断言 worker 上 {@code hasPrincipal()==false}。
 *
 * <p><b>今日必红（存量负债，修复属另卡 #10 二级：封继承）。</b>本卡（s2-f10p3-a1）只做
 * 任务边界纪律（受理期捕获＋任务体显式重绑＋finally 恢复），<b>不改</b>
 * {@code childValue}／不改 {@code PrincipalContext} 语义。红证据存档于
 * {@code r17/f10p3/residue-red/}；修复落地后去掉 {@code @Disabled} 即为该卡的验收判据。
 */
@Tag("dev")
class WorkerThreadPrincipalInheritanceResidueTest {

    private Scheduler residuePool;

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
        if (residuePool != null) {
            residuePool.dispose();
        }
    }

    @Test
    @Disabled("今日仍红（2026-10-12 实证：Expecting value to be false but was true；红证据存档 "
            + "r17/f10p3/residue-red/）。该残留的修复属另卡 #10 二级（封继承：childValue→null 或显式装饰），"
            + "本卡（s2-f10p3-a1）只做任务边界纪律、禁改 PrincipalContext 语义；@Disabled 只为保持模块构建绿。"
            + "封继承落地后去掉本注解，此判据即为该卡验收锚点。")
    @DisplayName("残留负例：带主体的线程创建 worker 后，无主体线程提交的任务不得继承该主体（修复属另卡 #10，今日仍红）")
    void workerCreatedByPrincipalCarrierLeaksInheritedPrincipalToAnonymousSubmitters() throws Exception {
        // 私有池、单 worker：确保两个任务落在同一个线程上，判据不受全局池状态影响
        residuePool = Schedulers.newBoundedElastic(1, 1, "f10p3-residue", 60, true);

        // 阶段 A：持 P1 的线程触发 worker 创建（真机：worker 惰性创建于提交线程）
        PrincipalContext.set(principal());
        CountDownLatch created = new CountDownLatch(1);
        residuePool.schedule(created::countDown);
        assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
        PrincipalContext.clear();

        // 阶段 B：干净线程（无主体）提交新任务
        AtomicBoolean principalPresent = new AtomicBoolean(true);
        AtomicReference<String> identityOnWorker = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread cleanSubmitter = new Thread(() -> residuePool.schedule(() -> {
            principalPresent.set(PrincipalContext.hasPrincipal());
            ExecutionPrincipal observed = PrincipalContext.get();
            identityOnWorker.set(observed == null ? "null" : observed.membershipId());
            finished.countDown();
        }), "f10p3-clean-submitter");
        cleanSubmitter.start();
        cleanSubmitter.join(5000);
        assertThat(finished.await(5, TimeUnit.SECONDS)).as("干净提交者的任务应执行完成").isTrue();

        assertThat(principalPresent.get())
                .as("池化 worker 不得把创建期继承的主体带给无主体提交者——线程复用＝跨用户串身份；"
                        + "今日必红（修复属另卡 #10 二级封继承），本判据为该卡验收锚点")
                .isFalse();
        assertThat(identityOnWorker.get())
                .as("worker 上不应出现创建者的完整主体对象")
                .isEqualTo("null");
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal("T-RESIDUE-CREATOR", "930001", "platform:T-RESIDUE-CREATOR:930001",
                1, 1, Set.of(), "f10p3-residue-test", "test", 1, Long.MAX_VALUE);
    }
}
