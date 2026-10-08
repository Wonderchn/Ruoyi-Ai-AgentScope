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

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.controller.RecommendedQuestionController;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.service.impl.RecommendedQuestionGenerator;
import com.nageoffer.ai.ragent.rag.service.impl.RecommendedQuestionServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RW-22-R1-R7：推荐追问面的<b>真实装配与门控</b>判据。
 *
 * <p>装配实现类是真的，只有持久化/模型协作者是替身 —— 因此本类证明的是
 * "三件套齐全且门控闭合"，<b>不是</b>部署或真实 provider 端到端。
 *
 * <p>三个断言方向各有独立价值，缺任何一个都会留下盲区：
 * <ol>
 *   <li><b>开关全开 ⇒ 三个类各恰一个</b>：防"登记了但根本没装"（那是 404 的根因）；</li>
 *   <li><b>任一开关关闭 ⇒ 面无 bean 且上下文不失败</b>：防"门控写错导致关不掉"，
 *       并证明关掉时不要求任何协作者在场（否则缺 Redis/缺模型会在关闭态也炸启动）；</li>
 *   <li><b>开关全开但缺模型协作者 ⇒ 响亮失败</b>：这是本面当初被刻意延后的唯一原因
 *       （"no qualifying bean"会让整个应用起不来）。判据必须钉住"缺前置时失败"，
 *       否则将来有人把 {@code @ConditionalOnMissingBean} 加进来做静默降级，
 *       就会重新变成运行期 500 而不是启动期失败。</li>
 * </ol>
 */
@Tag("dev")
class AiEmbeddedRecommendationActivationTest {

    /** 三开关全开；协作者单独注入，便于逐个撤掉。 */
    private final ApplicationContextRunner configurations = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedRecommendationConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                    "p2.enabled=true", "ai.model.enabled=true");

    /** 推荐面的三个真实协作者（替身）：会话消息 mapper、提示词解析器、模型服务。 */
    private ApplicationContextRunner collaborators() {
        return configurations
                .withBean(ConversationMessageMapper.class, () -> mock(ConversationMessageMapper.class))
                .withBean(AgentPromptResolver.class, () -> mock(AgentPromptResolver.class))
                .withBean(LLMService.class, () -> mock(LLMService.class));
    }

    @Test
    void controllerServiceAndGeneratorAreAssembledExactlyOnce() {
        collaborators().run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : new Class<?>[]{RecommendedQuestionController.class,
                    RecommendedQuestionServiceImpl.class, RecommendedQuestionGenerator.class}) {
                assertThat(context.getBeansOfType(type)).as(type.getSimpleName()).hasSize(1);
                assertThat(context.getBean(type).getClass()).isEqualTo(type);
            }
            // 服务必须是接口的实现本体，而不是被别的实现顶替
            assertThat(context.getBean(RecommendedQuestionController.class)).isNotNull();
        });
    }

    @Test
    void everyGateClosesTheFaceWithoutRequiringAnyCollaborator() {
        for (String property : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http",
                "p2.enabled=false", "ai.model.enabled=false"}) {
            configurations.withPropertyValues(property).run(context -> {
                assertThat(context).as(property).hasNotFailed();
                assertThat(context).as(property)
                        .doesNotHaveBean(RecommendedQuestionController.class)
                        .doesNotHaveBean(RecommendedQuestionServiceImpl.class)
                        .doesNotHaveBean(RecommendedQuestionGenerator.class);
            });
        }
    }

    @Test
    void missingModelCollaboratorsFailLoudlyInsteadOfInstallingAPartialFace() {
        // 只给 mapper：AgentPromptResolver / LLMService 缺席 ⇒ 必须启动期失败
        configurations
                .withBean(ConversationMessageMapper.class, () -> mock(ConversationMessageMapper.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingMapperFailsLoudlyAsWell() {
        // 只给模型协作者：ConversationMessageMapper 缺席 ⇒ 同样必须启动期失败
        configurations
                .withBean(AgentPromptResolver.class, () -> mock(AgentPromptResolver.class))
                .withBean(LLMService.class, () -> mock(LLMService.class))
                .run(context -> assertThat(context).hasFailed());
    }
}
