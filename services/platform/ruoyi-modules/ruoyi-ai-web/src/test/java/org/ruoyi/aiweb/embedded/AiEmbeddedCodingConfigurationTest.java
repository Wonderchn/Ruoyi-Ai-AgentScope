package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.app.CodingHarnessApplicationService;
import com.nageoffer.ai.ragent.coding.controller.CodingHarnessController;
import com.nageoffer.ai.ragent.coding.store.FileHarnessStore;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * F20-A 装配激活判据：三道门（ai.integration.enabled / transport=local / p2.enabled）
 * 任一关闭时整个编程助手面消失；打开时 store/service/controller 恰好各一个。
 */
@Tag("dev")
class AiEmbeddedCodingConfigurationTest {

    @TempDir
    Path temp;

    private ApplicationContextRunner configurations() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AiEmbeddedCodingConfiguration.class)
                .withPropertyValues(
                        "ai.integration.enabled=true",
                        "ai.integration.transport=local",
                        "p2.enabled=true",
                        "coding.harness.data-dir=" + temp.resolve("state"),
                        "coding.harness.workspace.owner-root=" + temp.resolve("ws"),
                        "coding.harness.workspace.shared-root=")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DeliveryPermits.class, () -> mock(DeliveryPermits.class));
    }

    @Test
    void codingSurfaceIsAssembledExactlyOnce() {
        configurations().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(FileHarnessStore.class)).hasSize(1);
            assertThat(context.getBeansOfType(CodingHarnessApplicationService.class)).hasSize(1);
            assertThat(context.getBeansOfType(CodingHarnessController.class)).hasSize(1);
            assertThat(context.getBean(CodingHarnessController.class).getClass())
                    .isEqualTo(CodingHarnessController.class);
        });
    }

    @Test
    void everyGateClosesTheSurface() {
        for (String property : new String[]{
                "ai.integration.enabled=false",
                "ai.integration.transport=http",
                "p2.enabled=false"}) {
            configurations().withPropertyValues(property).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(CodingHarnessController.class)
                        .doesNotHaveBean(CodingHarnessApplicationService.class);
            });
        }
    }
}
