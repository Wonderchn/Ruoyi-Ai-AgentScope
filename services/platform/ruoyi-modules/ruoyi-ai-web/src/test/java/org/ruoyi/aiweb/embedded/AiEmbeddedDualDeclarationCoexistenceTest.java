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

import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryRepository;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.state.PgAgentStateStore;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RW-31-ACT-R7（T3）：{@code pgAgentStateStore} / {@code agentRunGate} 被<b>两个</b>
 * auto-configuration 同名同型声明时的<b>共存判据</b>。
 *
 * <p><b>被审事实（锚 207d13e5）。</b>{@code AiEmbeddedAgentConversationConfiguration}
 * （{@code AutoConfiguration.imports} 第 9 行）与 {@code AiEmbeddedAgentEngineConfiguration}
 * （第 11 行）都在各自的 {@code …LocalTransport.…Enabled} 里声明了
 * {@code pgAgentStateStore}（返回 {@code PgAgentStateStore}）与 {@code agentRunGate}
 * （返回 {@code AgentRunGate}），<b>同名、同返回类型、同构造</b>，两侧都带
 * {@code @ConditionalOnMissingBean}。因此同一容器里每个名字只可能有一个定义，
 * 不会出现 {@code BeanDefinitionOverrideException}。
 *
 * <p><b>"谁赢"由什么决定——本判据先纠正一个常见误解。</b>不是
 * {@code AutoConfiguration.imports} 的<b>行序</b>。Spring Boot 的
 * {@code AutoConfigurationSorter#getInPriorityOrder} 先对候选类名做
 * {@code Collections.sort(...)}（字典序），再按 {@code @AutoConfigureOrder}，
 * 最后才应用 {@code @AutoConfigureBefore/@AutoConfigureAfter} 约束；imports 文件的行序
 * 在这一步被丢弃。证据是 Boot 4.1.0 的字节码（
 * {@code javap -c org.springframework.boot.autoconfigure.AutoConfigurationSorter}，
 * 偏移 9–10 与 41–43 两处 {@code Collections.sort}，随后 46–54 才按 order 排序）。
 * 字典序下 {@code …AgentConversationConfiguration} &lt; {@code …AgentEngineConfiguration}，
 * 所以会话配置的两个定义赢、引擎配置的退让。
 *
 * <p><b>本类因此断言四件事，而不是断言行序。</b>
 * <ol>
 *   <li>两配置同容器：容器<b>真的起来</b>（{@code allow-bean-definition-overriding=false} 下无
 *       {@code BeanDefinitionOverrideException}），且两个名字<b>各恰好一个</b> bean；</li>
 *   <li>赢家是<b>会话配置</b>——用 bean definition 的 {@code factoryBeanName} 证明是哪个
 *       {@code @Bean} 方法注册的，而不是只看实例类型（两侧实例类型相同，看类型证明不了）；
 *       <b>但请注意这一条钉的是"现状"而不是"正确性"</b>（见第 4 条）；</li>
 *   <li>赢家与 {@code AutoConfigurations.of(...)} 的<b>传入顺序无关</b>，
 *       且两个配置都<b>没有</b>显式 {@code @AutoConfigureBefore/@AutoConfigureAfter/@AutoConfigureOrder}
 *       ——即今天的顺序来自 Boot 的默认字典序规则，不是任何显式契约。将来有人加显式顺序注解
 *       会在这里变红，需要重新确认"谁赢"；</li>
 *   <li><b>两侧声明构造出等价的 bean</b>（{@link #bothDeclarationsConstructEquivalentBeans()}）。
 *       这才是让"谁赢"<b>无关紧要</b>的依据：只要两侧由同一组协作者构造出逐字段等价的产物，
 *       换哪个方法赢都不改变行为；一旦两侧构造不再等价，"谁赢"才真正开始有意义，
 *       那时必须补显式顺序契约。</li>
 * </ol>
 *
 * <p><b>为什么第 2 条与第 4 条必须并存。</b>第 2 条单独存在时是<b>身份钉</b>：它只回答
 * "哪个 {@code @Bean} 方法赢了"，<b>从不检查构造出来的东西对不对</b>。实证是：把<b>输家</b>的构造
 * 换成语义不同的实现，第 2 条仍然全绿。⇒ 第 2 条保护的是"顺序契约没被悄悄改动"，
 * 第 4 条保护的才是"赢家与输家真的等价"。**两条都不承重时，这个共存面等于没有护栏。**
 *
 * <p><b>不签什么。</b>本判据只证明"两个声明共存时恰好一个定义存活、赢家是会话配置、
 * 且两侧构造等价"。它<b>不</b>证明真实 platform 全上下文启动、不证明 Redis 能力、不证明引擎链就绪——
 * 下面那 11 个类型只作为"类型在场"的替身，满足 {@code EngineChainReadiness} 的点名，
 * 不对它们做任何行为断言。
 */
@Tag("dev")
class AiEmbeddedDualDeclarationCoexistenceTest {

    private static final String CONVERSATION_CONFIG =
            "org.ruoyi.aiweb.embedded.AiEmbeddedAgentConversationConfiguration";
    private static final String ENGINE_CONFIG =
            "org.ruoyi.aiweb.embedded.AiEmbeddedAgentEngineConfiguration";
    /** 会话配置的简单名：赢家的 factoryBeanName 必须含它。 */
    private static final String WINNER_MARKER = "AiEmbeddedAgentConversationConfiguration";
    private static final String LOSER_MARKER = "AiEmbeddedAgentEngineConfiguration";

    private static final String STATE_STORE = "pgAgentStateStore";
    private static final String RUN_GATE = "agentRunGate";

    /**
     * 两配置同容器的最小上下文：只把<b>业务协作者</b>换成替身，
     * 网关/控制器/配置类本身都是真实实现。
     */
    private ApplicationContextRunner runner(Class<?>... autoConfigurations) {
        return new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(false)
                .withConfiguration(AutoConfigurations.of(autoConfigurations))
                .withPropertyValues(
                        "ai.integration.enabled=true",
                        "ai.integration.transport=local",
                        "agent.conversation.enabled=true",
                        "ragent.engine.type=agent")
                // ---- 会话面 / 引擎面的构造协作者（只满足构造依赖，不做行为断言）
                .withBean(AgentStateMapper.class, () -> mock(AgentStateMapper.class))
                .withBean(AgentConversationMapper.class, () -> mock(AgentConversationMapper.class))
                .withBean(AgentMessageMapper.class, () -> mock(AgentMessageMapper.class))
                .withBean(RedissonClient.class, AiEmbeddedDualDeclarationCoexistenceTest::redisson)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(TransactionOperations.class, () -> mock(TransactionOperations.class))
                .withBean(AgentChatService.class, () -> mock(AgentChatService.class))
                .withBean(AgentToolCatalog.class, () -> mock(AgentToolCatalog.class))
                // ---- EngineChainReadiness 的类型点名：只登记"类型在场"的定义，不实例化、不断言行为
                .withBean(ReActAgentProvider.class, () -> null)
                .withBean(AgentPromptResolver.class, () -> null)
                .withBean(KnowledgeSearchFacade.class, () -> null)
                .withBean(IntentNodeRegistry.class, () -> null)
                .withBean(AgentMcpClients.class, () -> null)
                .withBean(AgentSkillRegistry.class, () -> null)
                .withBean(AgentMemoryPipeline.class, () -> null)
                .withBean(AgentMemoryRepository.class, () -> null)
                .withBean(OpenAIChatModel.class, () -> null);
    }

    @Test
    @DisplayName("两配置同容器：启动成功、两个名字各恰好一个 bean、赢家是会话配置、关闭覆盖下不抛")
    void bothDeclarationsCoexistWithExactlyOneBeanPerName() {
        runner(AiEmbeddedAgentConversationConfiguration.class, AiEmbeddedAgentEngineConfiguration.class)
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .as("两配置同容器必须真的装配成功，否则下面的计数断言是对空上下文的空转")
                            .isNull();

                    assertThat(context.getBeansOfType(PgAgentStateStore.class))
                            .as("pgAgentStateStore 必须恰好 1 个（两侧同名同型，另一侧须 @ConditionalOnMissingBean 退让）")
                            .hasSize(1);
                    assertThat(context.getBeansOfType(AgentRunGate.class))
                            .as("agentRunGate 必须恰好 1 个")
                            .hasSize(1);
                    assertThat(beanDefinitionNames(context, STATE_STORE))
                            .as("容器里只允许存在一个名为 pgAgentStateStore 的 bean definition")
                            .hasSize(1);
                    assertThat(beanDefinitionNames(context, RUN_GATE))
                            .as("容器里只允许存在一个名为 agentRunGate 的 bean definition")
                            .hasSize(1);
                    assertThat(context.containsBean(STATE_STORE)).isTrue();
                    assertThat(context.containsBean(RUN_GATE)).isTrue();

                    assertThat(declaringConfig(context, STATE_STORE))
                            .as("赢家必须是会话配置那条声明。注意：这钉的是 Boot 默认字典序下的"
                                    + "【现状】，不是行为正确性；行为等价性由"
                                    + " bothDeclarationsConstructEquivalentBeans 负责")
                            .contains(WINNER_MARKER)
                            .doesNotContain(LOSER_MARKER);
                    assertThat(declaringConfig(context, RUN_GATE))
                            .as("agentRunGate 的赢家同样必须是会话配置（同上：现状钉，非正确性钉）")
                            .contains(WINNER_MARKER)
                            .doesNotContain(LOSER_MARKER);
                });
    }

    /**
     * <b>本类真正该钉住的不变量：两侧声明构造出等价的 bean。</b>（类 javadoc 第 4 条）
     *
     * <p>上面的共存判据只在"哪个 {@code @Bean} 方法赢了"这一层作答；把<b>输家</b>的构造换成
     * 语义不同的实现，它仍然全绿。本判据把"构造得对不对"补上：
     *
     * <ol>
     *   <li><b>结构等价</b>：两侧 {@code @Bean} 方法的返回类型相同、<b>形参类型序列相同</b>——
     *       否则两侧消费的依赖就不一样，赢家会真的改变行为；</li>
     *   <li><b>产物等价</b>：用<b>同一组协作者实例</b>分别调用两侧方法，断言产物
     *       <b>具体类相同</b>且<b>逐字段等价</b>（引用按同一性、基本类型按值）。</li>
     * </ol>
     *
     * <p><b>为什么不是空断言</b>：把任一侧的构造换成换协作者、换具体类、换参数顺序，
     * 第 1 或第 2 条立刻变红。
     *
     * <p><b>不签什么</b>：本判据<b>不</b>启动 Spring 上下文、不证明真实装配下谁会赢；
     * 它只证明"两个声明在构造意义上等价"，因而"谁赢"不改变行为。
     */
    @Test
    @DisplayName("两侧声明构造等价：同一组协作者下具体类相同且逐字段等价（这才是「谁赢都无所谓」的依据）")
    void bothDeclarationsConstructEquivalentBeans() throws Exception {
        assertConstructionEquivalent(STATE_STORE);
        assertConstructionEquivalent(RUN_GATE);
    }

    // ------------------------------------------------------ 构造等价性夹具

    /**
     * 对某个 bean 名字执行"结构等价 + 产物等价"两步断言。
     *
     * <p>先按<b>名字</b>（不按签名）取两侧的 {@code @Bean} 方法：若谁改了形参，本判据要在
     * <b>断言</b>上失败并说清差在哪，而不是在查找阶段抛"找不到方法"——后者会把
     * "签名不同"伪装成"方法不存在"。
     */
    private static void assertConstructionEquivalent(String beanName) throws Exception {
        Method conversation = findBeanMethodByName(Class.forName(CONVERSATION_CONFIG), beanName);
        Method engine = findBeanMethodByName(Class.forName(ENGINE_CONFIG), beanName);
        assertThat(conversation)
                .as("%s：会话配置里必须有一个同名的 @Bean 方法（找不到即红，不允许静默跳过）", beanName)
                .isNotNull();
        assertThat(engine)
                .as("%s：引擎配置里必须有一个同名的 @Bean 方法（找不到即红）", beanName)
                .isNotNull();

        // ---- 第 1 步：结构等价
        assertThat(engine.getReturnType())
                .as("%s：两侧声明的返回类型必须相同（按具体类比较）", beanName)
                .isEqualTo(conversation.getReturnType());
        assertThat(engine.getParameterTypes())
                .as("%s：两侧声明的形参类型【序列】必须相同——顺序也算，它决定注入的是哪一个依赖",
                        beanName)
                .containsExactly(conversation.getParameterTypes());

        // ---- 第 2 步：产物等价（同一组协作者实例喂给两侧）
        Object[] collaborators = collaboratorsFor(conversation.getParameterTypes());
        Object fromConversation = invokeBeanMethod(conversation, collaborators);
        Object fromEngine = invokeBeanMethod(engine, collaborators);

        assertThat(fromEngine.getClass())
                .as("%s：同一组协作者下，两侧必须构造出同一个具体类", beanName)
                .isEqualTo(fromConversation.getClass());
        assertThat(fieldValues(fromEngine))
                .as("%s：同一组协作者下，两侧产物必须逐字段等价（引用按同一性）。"
                        + "不等价即意味着【谁赢】会改变行为，必须补显式顺序契约", beanName)
                .isEqualTo(fieldValues(fromConversation));
    }

    /** 按<b>名字</b>在类自身与全部嵌套类里找 {@code @Bean} 方法；找不到返回 {@code null}，由调用方断言。 */
    private static Method findBeanMethodByName(Class<?> type, String methodName) {
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName) && candidate.isAnnotationPresent(Bean.class)) {
                return candidate;
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            Method found = findBeanMethodByName(nested, methodName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 按形参类型列表造一组<b>两侧共用</b>的协作者实例。
     *
     * <p>{@code RedissonClient} 走 {@link #redisson()}：裸 mock 在
     * {@code StreamTaskManager.@PostConstruct subscribe()} 那条路径上会让容器初始化失败，
     * 用同一个夹具更贴近真实装配。
     */
    private static Object[] collaboratorsFor(Class<?>[] parameterTypes) {
        Object[] collaborators = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            collaborators[index] = parameterTypes[index] == RedissonClient.class
                    ? redisson()
                    : mock(parameterTypes[index]);
        }
        return collaborators;
    }

    /** 反射调用 {@code @Bean} 方法：目标实例是声明它的那个静态嵌套配置类。 */
    private static Object invokeBeanMethod(Method method, Object[] collaborators) throws Exception {
        Class<?> declaring = method.getDeclaringClass();
        Object target = declaring.getDeclaredConstructor().newInstance();
        method.setAccessible(true);
        return method.invoke(target, collaborators);
    }

    /**
     * 产物的逐字段快照：沿继承链收集所有<b>非静态</b>字段到值。
     *
     * <p>引用按同一性比较（{@code Object.equals} 对替身即同一性），基本类型按值。
     * 这两个被构造的类里没有"每次构造都新建"的字段，故不存在假红。
     */
    private static Map<String, Object> fieldValues(Object target) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    values.put(type.getSimpleName() + "." + field.getName(), field.get(target));
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException("无法读取字段 " + field, failure);
                }
            }
        }
        return values;
    }

    @Test
    @DisplayName("赢家与 AutoConfigurations.of(...) 的传入顺序无关（证明顺序不来自调用方，而来自 Boot 的排序规则）")
    void winnerIsIndependentOfTheOrderTheConfigurationsAreSupplied() {
        String[] forward = new String[2];
        String[] backward = new String[2];

        runner(AiEmbeddedAgentConversationConfiguration.class, AiEmbeddedAgentEngineConfiguration.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    forward[0] = declaringConfig(context, STATE_STORE);
                    forward[1] = declaringConfig(context, RUN_GATE);
                });
        runner(AiEmbeddedAgentEngineConfiguration.class, AiEmbeddedAgentConversationConfiguration.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    backward[0] = declaringConfig(context, STATE_STORE);
                    backward[1] = declaringConfig(context, RUN_GATE);
                });

        assertThat(forward[0]).as("正序下赢家必须是会话配置").contains(WINNER_MARKER);
        assertThat(backward[0]).as("反序下赢家仍是会话配置").contains(WINNER_MARKER);
        assertThat(backward[0]).as("两种传入顺序的赢家必须相同").isEqualTo(forward[0]);
        assertThat(backward[1]).as("agentRunGate 在两种传入顺序下的赢家必须相同").isEqualTo(forward[1]);
    }

    @Test
    @DisplayName("两个配置都在真实 imports 清单里，且都没有显式 @AutoConfigureBefore/After/Order")
    void bothConfigurationsAreRegisteredAndCarryNoExplicitOrderingContract() throws IOException {
        List<String> entries = Files.readAllLines(locateImports()).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        assertThat(entries)
                .as("两个配置都必须被真实 imports 清单登记（删掉任一条即红）")
                .contains(CONVERSATION_CONFIG, ENGINE_CONFIG);

        assertThat(explicitOrderingAnnotations(AiEmbeddedAgentConversationConfiguration.class))
                .as("会话配置当前不声明显式顺序；加了就需要重新确认赢家")
                .isEmpty();
        assertThat(explicitOrderingAnnotations(AiEmbeddedAgentEngineConfiguration.class))
                .as("引擎配置当前不声明显式顺序；加了就需要重新确认赢家")
                .isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Redisson 替身：{@code StreamTaskManager.@PostConstruct subscribe()} 会对
     * {@code getTopic(...)} 的返回值直接调 {@code addListener}，裸 mock 返回 null 会让
     * 容器在初始化阶段失败——那是夹具缺陷，不是被测事实。
     */
    private static RedissonClient redisson() {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getTopic(anyString())).thenReturn(mock(org.redisson.api.RTopic.class));
        return client;
    }

    private static List<String> beanDefinitionNames(ApplicationContext context, String name) {
        List<String> hits = new ArrayList<>();
        for (String candidate : context.getBeanDefinitionNames()) {
            if (name.equals(candidate)) {
                hits.add(candidate);
            }
        }
        return hits;
    }

    /**
     * 该 bean name 由哪个 {@code @Bean} 方法注册：返回 {@code factoryBeanName + "#" + factoryMethodName}。
     *
     * <p>必须用 definition 的来源而不是实例类型：两侧都返回同一个具体类，看实例类型区分不出赢家。
     */
    private static String declaringConfig(ApplicationContext context, String beanName) {
        BeanDefinition definition = ((org.springframework.beans.factory.support.DefaultListableBeanFactory)
                context.getAutowireCapableBeanFactory()).getBeanDefinition(beanName);
        String factoryBean = definition.getFactoryBeanName();
        String factoryMethod = definition.getFactoryMethodName();
        return (factoryBean == null ? "?" : factoryBean) + "#" + (factoryMethod == null ? "?" : factoryMethod);
    }

    /** 类自身与全部嵌套类上的显式顺序注解简单名。 */
    private static List<String> explicitOrderingAnnotations(Class<?> type) {
        List<String> found = new ArrayList<>();
        collectOrdering(type, found);
        return found;
    }

    private static void collectOrdering(Class<?> type, List<String> found) {
        for (Annotation annotation : type.getAnnotations()) {
            if (annotation instanceof AutoConfigureBefore || annotation instanceof AutoConfigureAfter
                    || annotation instanceof AutoConfigureOrder) {
                found.add(type.getSimpleName() + ":" + annotation.annotationType().getSimpleName());
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            // 只为把"注解藏在 @Bean 方法上"这一形态也扫到；@AutoConfigure* 不在方法上是常态。
            for (Annotation annotation : method.getAnnotations()) {
                if (annotation instanceof AutoConfigureBefore || annotation instanceof AutoConfigureAfter
                        || annotation instanceof AutoConfigureOrder) {
                    found.add(type.getSimpleName() + "." + method.getName() + ":"
                            + annotation.annotationType().getSimpleName());
                }
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectOrdering(nested, found);
        }
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
}
