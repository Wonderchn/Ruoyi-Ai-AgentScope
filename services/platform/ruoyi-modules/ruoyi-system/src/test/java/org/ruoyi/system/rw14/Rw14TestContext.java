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

package org.ruoyi.system.rw14;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.ruoyi.common.core.utils.SpringUtils;
import org.springframework.context.support.StaticApplicationContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RW-14（T4）定向单测环境。
 *
 * <p>ruoyi 的若干工具类是"静态取 Spring bean"（{@code RedisUtils}/{@code MessageUtils}/{@code JsonUtils}），
 * 这里用 {@link StaticApplicationContext} 注入最小 bean 集合：
 * 让认证语义与登录审计的定向单测可以在<b>不起 Spring Boot、不连 Redis、不连数据库</b>的前提下运行，
 * 以便在构建/实例窗口之外复现 G-53 与认证负例。</p>
 *
 * <p>事件捕获用子类覆写 {@code publishEvent(Object)}（而不是注册监听器）：生产者事件必须
 * "发布过"就能被断言，不依赖监听器泛型/多播器实现细节。</p>
 *
 * <p>注意：{@code RedisUtils}/{@code MessageUtils}/{@code JsonUtils} 的静态字段在 JVM 内只初始化一次，
 * 因此本类的 mock 必须是全 JVM 单例（下面的 static 字段），并由 {@link #redisErrorCount(Integer)} 控制每个用例的行为。</p>
 */
public final class Rw14TestContext {

    private static final RedissonClient REDISSON = Mockito.mock(RedissonClient.class);

    @SuppressWarnings("rawtypes")
    private static final RBucket BUCKET = Mockito.mock(RBucket.class);

    private static final RecordingApplicationContext CONTEXT = new RecordingApplicationContext();

    private static volatile Integer redisErrorCount;

    private static boolean installed;

    private Rw14TestContext() {
    }

    /**
     * 幂等安装：第一个测试类触达时建立静态上下文。
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        Mockito.when(REDISSON.getBucket(Mockito.anyString())).thenReturn(BUCKET);
        Mockito.when(BUCKET.get()).thenAnswer(invocation -> redisErrorCount);
        CONTEXT.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
        CONTEXT.getBeanFactory().registerSingleton("redissonClient", REDISSON);
        // ValidatorUtils 静态取名为 "validator" 的 bean（hibernate-validator 在 classpath 上）
        CONTEXT.getBeanFactory().registerSingleton("validator",
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        // messageSource 用 StaticApplicationContext 自带的 StaticMessageSource：
        // 未注册的 i18n key 会抛 NoSuchMessageException，MessageUtils 捕获后返回 key 本身 ——
        // 与"资源文件缺失"时的既有行为一致，测试可用 key 断言审计文案。
        // hutool SpringUtil#setApplicationContext 是实例方法但写的是静态字段
        new SpringUtils().setApplicationContext(CONTEXT);
        installed = true;
    }

    /**
     * 每个用例开始前清理：发布事件列表 + Redis 计数器 + mock 调用记录（保留 stubbing）。
     */
    public static void reset() {
        install();
        CONTEXT.published.clear();
        redisErrorCount = null;
        Mockito.clearInvocations(REDISSON, BUCKET);
    }

    /**
     * 控制 {@code RedisUtils.getCacheObject} 的返回值：非 null 表示"已有失败次数/已锁定"。
     */
    public static void redisErrorCount(Integer count) {
        redisErrorCount = count;
    }

    /**
     * 已发布的事件（按发布顺序）。
     */
    public static List<Object> publishedEvents() {
        return List.copyOf(CONTEXT.published);
    }

    /**
     * Redis bucket mock：用于断言重试计数写入（{@code set(value, duration)}）。
     */
    @SuppressWarnings("rawtypes")
    public static RBucket redisBucket() {
        return BUCKET;
    }

    /**
     * 最近发布的一个事件。
     */
    public static Object lastPublishedEvent() {
        return CONTEXT.published.isEmpty() ? null : CONTEXT.published.get(CONTEXT.published.size() - 1);
    }

    /**
     * 已发布的 {@code LogininforEvent} 列表（按发布顺序）。
     */
    public static List<org.ruoyi.common.log.event.LogininforEvent> publishedLoginEvents() {
        return CONTEXT.published.stream()
            .filter(org.ruoyi.common.log.event.LogininforEvent.class::isInstance)
            .map(org.ruoyi.common.log.event.LogininforEvent.class::cast)
            .toList();
    }

    /**
     * 记录 {@code publishEvent} 的 ApplicationContext（其余能力继承 StaticApplicationContext）。
     */
    private static final class RecordingApplicationContext extends StaticApplicationContext {

        private final List<Object> published = new CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(Object event) {
            published.add(event);
            super.publishEvent(event);
        }
    }
}
