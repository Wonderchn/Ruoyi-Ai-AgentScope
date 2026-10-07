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

import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport;
import com.nageoffer.ai.ragent.infra.chat.DeepSeekChatClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F-3：内嵌形态下 {@code AbstractOpenAIStyleChatClient} 的协作者必须按**名字**解析。
 *
 * <p><b>这是 T8 在真环境实测过的启动阻塞。</b>WP-038 阶段 2 里应用 20 秒内退出，
 * {@code deepSeekChatClient} 创建失败：
 * <pre>
 * No qualifying bean of type 'java.util.concurrent.Executor' available:
 *   more than one 'primary' bean found among candidates:
 *   [modelStreamExecutor, applicationTaskExecutor, scheduledExecutorService,
 *    knowledgeParseExecutor, mainExecutor]
 * </pre>
 * 根因不是"少了一个 bean"，而是 platform 把**两套应用**放进同一个上下文之后，
 * 类型级注入不再成立：{@code ThreadPoolConfig} 的
 * {@code @Primary scheduledExecutorService}（{@code ScheduledExecutorService extends Executor}）
 * 与 {@code @Primary mainExecutor}（{@code AsyncTaskExecutor extends TaskExecutor extends Executor}）
 * <b>同时是 {@code Executor} 的 primary 候选</b>，容器直接拒绝解析。
 * 独立 AI 应用不炸只是因为那边没有 platform 的 {@code ThreadPoolConfig}。
 *
 * <p><b>为什么这个判据要启真实容器，而不是只读注解。</b>T0 的验收口径明确：
 * "仅编译通过不算修好"。{@code @Qualifier} 写错名字、或者只给其中一个字段加、
 * 或者加了限定符但平台的 {@code @Primary} 仍然先命中 —— 这些都在编译期看不出来，
 * 只有在**真实 bean 解析**时才暴露。因此这里用 {@link AnnotationConfigApplicationContext}
 * 把 F-3 的候选集合原样搭出来（两个 {@code @Primary} + 一个 {@code Executor} +
 * Spring Boot 风格的 {@code applicationTaskExecutor}），再真正取一次
 * {@code DeepSeekChatClient}。
 *
 * <p><b>第二层判据：注入的必须是"对的那个"而不是"随便一个能解析的"。</b>
 * {@code OkHttpClient} 那一侧不报错、更危险：{@code streamingHttpClient} 带 {@code @Primary}，
 * 于是裸 {@code @Autowired OkHttpClient syncHttpClient} 会**静默**拿到无读超时/无调用超时的
 * 流式客户端。所以这里除了"能创建"，还要按 bean 身份断言注入结果。
 */
@Tag("dev")
class EmbeddedChatClientInjectionDisambiguationTest {

    @Test
    @DisplayName("两个 @Primary Executor 并存时，ChatClient 仍能创建（复现 F-3 的候选集合）")
    void chatClientIsCreatedWhenPlatformAndAiExecutorsCoexist() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(PlatformExecutors.class, AiInfraPorts.class,
                             ChatClients.class)) {
            assertThatCode(() -> context.getBean(DeepSeekChatClient.class))
                    .as("F-3：Executor 的类型级注入有多个 primary 候选时，容器直接拒绝创建 ChatClient"
                            + "（真环境后果：应用 20 秒内退出、READY=FALSE）")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("modelStreamExecutor 必须按名字解析到本链的线程池，不是平台某个 @Primary Executor")
    void modelStreamExecutorResolvesToTheAiChainPool() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(PlatformExecutors.class, AiInfraPorts.class,
                             ChatClients.class)) {
            DeepSeekChatClient client = context.getBean(DeepSeekChatClient.class);

            assertThat(injected(client, "modelStreamExecutor"))
                    .as("流式模型输出必须落在 modelStreamExecutor 上（AbortPolicy + 有界队列）；"
                            + "落到平台的 scheduledExecutorService/mainExecutor 会改变排队与拒绝语义")
                    .isSameAs(context.getBean("modelStreamExecutor"));
            assertThat(injected(client, "syncHttpClient"))
                    .as("syncHttpClient 字段不得因 streamingHttpClient 的 @Primary 而静默拿到流式客户端"
                            + "（后者 read/call 超时为 ZERO，同步调用可能永不超时）")
                    .isSameAs(context.getBean("syncHttpClient"));
            assertThat(injected(client, "streamingHttpClient"))
                    .isSameAs(context.getBean("streamingHttpClient"));
        }
    }

    @Test
    @DisplayName("结构护栏：四个协作者字段的限定符形状（RagStreamTraceSupport 刻意不加）")
    void qualifierShapeIsPinned() throws Exception {
        assertThat(qualifierOf(DeepSeekChatClient.class, "syncHttpClient")).isEqualTo("syncHttpClient");
        assertThat(qualifierOf(DeepSeekChatClient.class, "streamingHttpClient")).isEqualTo("streamingHttpClient");
        assertThat(qualifierOf(DeepSeekChatClient.class, "modelStreamExecutor")).isEqualTo("modelStreamExecutor");
        assertThat(qualifierOf(DeepSeekChatClient.class, "streamTraceSupport"))
                .as("RagStreamTraceSupport 在内嵌上下文只有一个候选；按名字注入会在独立 AI 应用里"
                        + "（实现类 bean 名不同）找不到 bean，所以刻意不加限定符")
                .isNull();
    }

    // ------------------------------------------------------------------ helpers

    private static Object injected(Object target, String fieldName) throws Exception {
        Field field = fieldInHierarchy(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String qualifierOf(Class<?> type, String fieldName) throws Exception {
        Field field = fieldInHierarchy(type, fieldName);
        Qualifier qualifier = field.getAnnotation(Qualifier.class);
        if (qualifier != null) {
            return qualifier.value();
        }
        // Spring 也接受 @Autowired 的 field-name 兜底（只在不带限定符时生效），这里显式区分：
        // 没有 @Qualifier 就是"允许按类型/字段名解析"，返回 null 让判据自己表态。
        assertThat(field.getAnnotation(Autowired.class)).as("%s 必须是 @Autowired", fieldName).isNotNull();
        return null;
    }

    private static Field fieldInHierarchy(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(fieldName);
            } catch (NoSuchFieldException missing) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + fieldName);
    }

    // ------------------------------------------------------------------ fixtures

    /** 复刻 platform {@code ThreadPoolConfig} 的候选集合（两个 {@code @Primary}）。 */
    @Configuration(proxyBeanMethods = false)
    static class PlatformExecutors {

        @Bean(name = "scheduledExecutorService", destroyMethod = "shutdownNow")
        @Primary
        ScheduledExecutorService scheduledExecutorService() {
            return Executors.newScheduledThreadPool(1);
        }

        @Bean(name = "mainExecutor")
        @Primary
        AsyncTaskExecutor mainExecutor() {
            return new AsyncTaskExecutor() {
                private final Executor delegate = Executors.newSingleThreadExecutor();

                @Override
                public void execute(Runnable task) {
                    delegate.execute(task);
                }
            };
        }

        @Bean(name = "knowledgeParseExecutor", destroyMethod = "shutdownNow")
        Executor knowledgeParseExecutor() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean(name = "applicationTaskExecutor")
        AsyncTaskExecutor applicationTaskExecutor() {
            return mainExecutor();
        }
    }

    /** 复刻 {@code AiEmbeddedModelConfiguration} 提供的四个基础设施 bean。 */
    @Configuration(proxyBeanMethods = false)
    static class AiInfraPorts {

        @Bean(name = "streamingHttpClient")
        @Primary
        OkHttpClient streamingHttpClient() {
            return new OkHttpClient.Builder().readTimeout(Duration.ZERO).callTimeout(Duration.ZERO).build();
        }

        @Bean(name = "syncHttpClient")
        OkHttpClient syncHttpClient() {
            return new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build();
        }

        @Bean(name = "modelStreamExecutor")
        Executor modelStreamExecutor() {
            return Executors.newFixedThreadPool(2);
        }

        @Bean
        RagStreamTraceSupport ragStreamTraceSupport() {
            return (name, type) -> RagStreamTraceSupport.NOOP_SPAN;
        }
    }

    /** 五个 ChatClient 中最简单的一个：只用来触发基类的字段注入。 */
    @Configuration(proxyBeanMethods = false)
    static class ChatClients {

        @Bean
        DeepSeekChatClient deepSeekChatClient() {
            return new DeepSeekChatClient();
        }
    }
}
