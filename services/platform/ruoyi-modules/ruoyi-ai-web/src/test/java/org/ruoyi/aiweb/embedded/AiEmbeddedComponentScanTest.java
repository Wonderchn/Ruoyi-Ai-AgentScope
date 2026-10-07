package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.ConversationBatchDeleteService;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Exercise the component discovery used by the platform's org.ruoyi scan. */
@Tag("dev")
class AiEmbeddedComponentScanTest {

    private static final String PACKAGE = "org.ruoyi.aiweb.embedded";
    private static final Class<?> CONVERSATIONS = AiEmbeddedAgentConversationConfiguration
            .LocalTransport.ConversationEnabled.ConversationSurface.class;
    private static final Class<?> FEEDBACK = AiEmbeddedFeedbackConfiguration
            .LocalTransportAssembly.FeedbackSurface.class;

    private final ApplicationContextRunner controllers = new ApplicationContextRunner()
            .withInitializer(context -> {
                var scanner = new ClassPathBeanDefinitionScanner(
                        (BeanDefinitionRegistry) context.getBeanFactory(), false, context.getEnvironment());
                scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
                scanner.addExcludeFilter(testFixtures());
                scanner.scan(PACKAGE);
            });

    @Test
    void httpControllersDoNotRequireEmbeddedServicesEvenWithConversationEnabled() {
        controllers.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=http",
                "agent.conversation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CONVERSATIONS).doesNotHaveBean(FEEDBACK);
        });
    }

    @Test
    void disabledIntegrationDoesNotDiscoverLocalControllers() {
        controllers.withPropertyValues("ai.integration.enabled=false", "ai.integration.transport=local",
                "agent.conversation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CONVERSATIONS).doesNotHaveBean(FEEDBACK);
        });
    }

    @Test
    void enabledLocalControllersAreDiscoveredExactlyOnce() {
        controllers.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "agent.conversation.enabled=true")
                .withBean(AgentConversationService.class, () -> mock(AgentConversationService.class))
                .withBean(ConversationBatchDeleteService.class, () -> mock(ConversationBatchDeleteService.class))
                .withBean(MessageFeedbackService.class, () -> mock(MessageFeedbackService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CONVERSATIONS).hasSingleBean(FEEDBACK);
                });
    }

    @Test
    void conversationFlagCanCloseItsSurfaceWithoutClosingFeedback() {
        controllers.withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local",
                        "agent.conversation.enabled=false")
                .withBean(MessageFeedbackService.class, () -> mock(MessageFeedbackService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CONVERSATIONS).hasSingleBean(FEEDBACK);
                });
    }

    @Test
    void httpScanExcludesAllLocalConfigurationsEvenWhenFeaturesAreEnabled() {
        assertThat(discover(allFeatures("true", "http"))).isEmpty();
    }

    @Test
    void masterSwitchClosesEveryNestedAssemblyIncludingLegacyListeners() {
        var properties = allFeatures("false", "local");
        properties.put("ai.integration.legacy-listeners-enabled", "true");
        assertThat(discover(properties)).isEmpty();
    }

    @Test
    void localScanCanDiscoverBothControllersAndNestedRunWorker() {
        assertThat(discover(allFeatures("true", "local"))).contains(
                CONVERSATIONS.getName(), FEEDBACK.getName(),
                AiEmbeddedWorkerConfiguration.LocalTransport.WorkerEnabled.AgentRunEnabled.class.getName());
    }

    @Test
    void agentWorkerCannotEscapeEitherP2WorkerGate() {
        for (String gate : new String[]{"p2.enabled", "p2.worker.enabled"}) {
            var properties = allFeatures("true", "local");
            properties.put(gate, "false");
            assertThat(discover(properties)).doesNotContain(
                    AiEmbeddedWorkerConfiguration.LocalTransport.WorkerEnabled.AgentRunEnabled.class.getName());
        }
    }

    @Test
    void missingMasterSwitchAndTransportRemainClosed() {
        var properties = allFeatures("true", "local");
        properties.remove("ai.integration.enabled");
        assertThat(discover(properties)).isEmpty();
        properties = allFeatures("true", "local");
        properties.remove("ai.integration.transport");
        assertThat(discover(properties)).isEmpty();
    }

    private static Map<String, Object> allFeatures(String enabled, String transport) {
        var properties = new HashMap<String, Object>();
        properties.put("ai.integration.enabled", enabled);
        properties.put("ai.integration.transport", transport);
        properties.put("agent.conversation.enabled", "true");
        properties.put("p2.enabled", "true");
        properties.put("p2.worker.enabled", "true");
        properties.put("p3.enabled", "true");
        properties.put("ai.model.enabled", "true");
        properties.put("ragent.engine.type", "agent");
        properties.put("rag.vector.type", "pg");
        properties.put("ai.integration.legacy-listeners-enabled", "false");
        return properties;
    }

    private static Set<String> discover(Map<String, Object> properties) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        var registry = new DefaultListableBeanFactory();
        var scanner = new ClassPathBeanDefinitionScanner(registry, true, environment);
        // SpringBootApplication excludes registered top-level auto-configurations.
        // Its default component filter still considers their static nested types.
        scanner.addExcludeFilter(new AnnotationTypeFilter(AutoConfiguration.class));
        scanner.addExcludeFilter(testFixtures());
        scanner.scan(PACKAGE);
        return Arrays.stream(registry.getBeanDefinitionNames())
                .map(name -> registry.getBeanDefinition(name).getBeanClassName())
                .filter(name -> name != null && name.startsWith(PACKAGE + "."))
                .collect(Collectors.toSet());
    }

    private static RegexPatternTypeFilter testFixtures() {
        // Test fixtures share the package but are absent from a deployed JAR.
        return new RegexPatternTypeFilter(Pattern.compile(".*Test(?:\\$.*)?"));
    }
}
