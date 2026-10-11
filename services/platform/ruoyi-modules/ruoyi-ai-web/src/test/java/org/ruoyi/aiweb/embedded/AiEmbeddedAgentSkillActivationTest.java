package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.controller.AgentSkillController;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillCacheManager;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentSkillMapper;
import com.nageoffer.ai.ragent.rag.service.impl.AgentSkillAdminServiceImpl;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * S2-F11-A1：Skills 管理面装配的**激活**判据（与 {@code AiEmbeddedIngestionActivationTest} 同形）。
 *
 * <p>装的是真实实现类，持久层/意图/交付协作对象是测试替身。四道门
 * （{@code ai.integration.enabled} / {@code transport=local} / {@code p2.enabled} /
 * {@code ragent.engine.type=agent}）任一关闭时该面必须整体消失——不装半个面，
 * 也不在关闭时要求协作对象在场（否则"关掉开关的应用起不来"）。
 *
 * <p><b>为什么必须随引擎门。</b>{@code AgentSkillAdminServiceImpl} 构造依赖
 * {@code IntentNodeRegistry}（由 S1 引擎闭包装配，F11-A1 不重复迁入）——“闭包未闭合时不装”
 * 是本模块族纪律；门开着且 S1 闭包在场的形态下该 bean 必然存在（真链装配由 S1 判据与
 * {@code AiEmbeddedAgentEngineClosureAssemblyTest} 负责，本判据只钉本面的门控与登记）。
 *
 * <p><b>双份定义防范（Spec §4.2）。</b>{@code AgentSkillCacheManager} 同时被 S1 引擎块与
 * 本面登记——同类 {@code @Import} 幂等：模拟"两个配置各 import 一次"的形态，
 * bean 必须恰好一个（多一个即注入歧义，少一个即闭包断裂）。
 */
@Tag("dev")
class AiEmbeddedAgentSkillActivationTest {

    private final ApplicationContextRunner configurations = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedAgentSkillConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                    "p2.enabled=true", "ragent.engine.type=agent");

    private ApplicationContextRunner collaborators() {
        return configurations
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(AgentSkillMapper.class, () -> mock(AgentSkillMapper.class))
                .withBean(IntentNodeRegistry.class, () -> mock(IntentNodeRegistry.class))
                .withBean(DeliveryPermits.class, () -> mock(DeliveryPermits.class));
    }

    @Test
    void skillAdminControllerAndServiceAreAssembledExactlyOnce() {
        collaborators().run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : new Class<?>[]{AgentSkillController.class, AgentSkillAdminServiceImpl.class,
                    AgentSkillCacheManager.class}) {
                assertThat(context.getBeansOfType(type)).hasSize(1);
                assertThat(context.getBean(type).getClass()).isEqualTo(type);
            }
        });
    }

    @Test
    void everyGateClosesTheSkillSurfaceWithoutRequiringCollaborators() {
        for (String property : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http",
                "p2.enabled=false", "ragent.engine.type=legacy"}) {
            configurations.withPropertyValues(property).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(AgentSkillController.class)
                        .doesNotHaveBean(AgentSkillAdminServiceImpl.class);
            });
        }
    }

    @Test
    void dualDeclarationWithTheS1EngineClosureYieldsExactlyOneCacheManagerBean() {
        collaborators()
                .withUserConfiguration(SimulatedS1EngineClosure.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(AgentSkillCacheManager.class))
                            .as("两个配置各 @Import 一次同一类 ⇒ 同类幂等，bean 必须恰好一个"
                                    + "（两个即注入歧义，零个即闭包断裂）")
                            .hasSize(1);
                });
    }

    /** 模拟 S1 引擎闭包：{@code @Import} 同一个 {@code AgentSkillCacheManager}（同类幂等，非第二个 bean）。 */
    @Configuration(proxyBeanMethods = false)
    @Import(AgentSkillCacheManager.class)
    static class SimulatedS1EngineClosure {
    }
}
