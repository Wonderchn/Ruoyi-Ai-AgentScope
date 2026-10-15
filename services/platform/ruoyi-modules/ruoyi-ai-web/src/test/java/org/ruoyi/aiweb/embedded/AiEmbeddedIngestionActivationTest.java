package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.ingestion.controller.IngestionPipelineController;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.nageoffer.ai.ragent.ingestion.service.impl.IngestionPipelineServiceImpl;
import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.RunAdmissionService;
import com.nageoffer.ai.ragent.runtime.RunLifecycleService;
import com.nageoffer.ai.ragent.runtime.usage.UsageLedgerService;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ai.api.runtime.DocumentPort;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * S2-F06-A1：摄取管线装配面的**激活**判据（与 {@code AiEmbeddedAdminActivationTest} 同形）。
 *
 * <p>装的是真实实现类，持久层协作对象是测试替身。三条门（integration.enabled /
 * transport=local / p2.enabled）任一关闭时该面必须整体消失——不装半个面，
 * 也不在关闭时要求协作对象在场（否则"关掉开关的应用起不来"）。
 */
@Tag("dev")
class AiEmbeddedIngestionActivationTest {

    private final ApplicationContextRunner configurations = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedIngestionConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local", "p2.enabled=true");

    private ApplicationContextRunner collaborators() {
        return configurations
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(BizChangeLogContext.class, () -> new BizChangeLogContext(new ObjectMapper()))
                .withBean(IngestionPipelineMapper.class, () -> mock(IngestionPipelineMapper.class))
                .withBean(IngestionPipelineNodeMapper.class, () -> mock(IngestionPipelineNodeMapper.class))
                // F06:1/2 集成（T0，2026-10-16）：p2.enabled 下新增的 canonical 任务面组
                // （IngestionTaskRuntime @Import 5 类）的协作替身；与上文同款"持久层/运行面替身"纪律。
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(RunAccessService.class, () -> mock(RunAccessService.class))
                .withBean(RunLifecycleService.class, () -> mock(RunLifecycleService.class))
                .withBean(RunAdmissionService.class, () -> mock(RunAdmissionService.class))
                .withBean(DocumentPort.class, () -> mock(DocumentPort.class))
                .withBean(UsageLedgerService.class, () -> mock(UsageLedgerService.class))
                .withBean(DeliveryPermits.class, () -> mock(DeliveryPermits.class));
    }

    @Test
    void pipelineServiceAndControllerAreAssembledExactlyOnce() {
        collaborators().run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : new Class<?>[]{IngestionPipelineController.class, IngestionPipelineServiceImpl.class}) {
                assertThat(context.getBeansOfType(type)).hasSize(1);
                assertThat(context.getBean(type).getClass()).isEqualTo(type);
            }
        });
    }

    @Test
    void everyGateClosesThePipelineSurfaceWithoutRequiringCollaborators() {
        for (String property : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http", "p2.enabled=false"}) {
            configurations.withPropertyValues(property).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(IngestionPipelineController.class)
                        .doesNotHaveBean(IngestionPipelineServiceImpl.class);
            });
        }
    }
}
