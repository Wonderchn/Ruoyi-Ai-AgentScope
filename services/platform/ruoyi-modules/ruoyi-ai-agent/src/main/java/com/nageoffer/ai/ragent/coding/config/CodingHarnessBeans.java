package com.nageoffer.ai.ragent.coding.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.coding.app.CodingHarnessApplicationService;
import com.nageoffer.ai.ragent.coding.app.CodingModelGateway;
import com.nageoffer.ai.ragent.coding.app.CodingRunExecutor;
import com.nageoffer.ai.ragent.coding.app.CodingRunProcessor;
import com.nageoffer.ai.ragent.coding.app.UnconfiguredCodingModelGateway;
import com.nageoffer.ai.ragent.coding.event.HarnessEventHub;
import com.nageoffer.ai.ragent.coding.runtime.CodingModelCatalog;
import com.nageoffer.ai.ragent.coding.sandbox.DockerSandboxConfig;
import com.nageoffer.ai.ragent.coding.store.FileHarnessStore;
import com.nageoffer.ai.ragent.coding.tool.CodingToolRuntime;
import com.nageoffer.ai.ragent.coding.workspace.CodingWorkspaceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicit bean closure for the coding harness. Imported by
 * {@code org.ruoyi.aiweb.embedded.AiEmbeddedCodingConfiguration}; the {@code com.nageoffer}
 * package is not in the platform scan root, so nothing here is discovered implicitly.
 *
 * <p>All external collaboration is property-driven. The shipped defaults are fail-closed:
 * no model gateway (runs end in {@code MODEL_NOT_CONFIGURED}), execute_process disabled, and an
 * unconfigured Docker policy that refuses before starting any process.
 */
@Configuration(proxyBeanMethods = false)
public class CodingHarnessBeans {

    @Bean
    public FileHarnessStore codingHarnessStore(
            ObjectMapper objectMapper,
            @Value("${coding.harness.data-dir:./data/coding-harness}") String dataDirectory) {
        return new FileHarnessStore(objectMapper, dataDirectory);
    }

    @Bean
    public CodingWorkspaceService codingWorkspaceService(
            @Value("${coding.harness.workspace.owner-root:./data/coding-workspaces}")
            String ownerRoot,
            @Value("${coding.harness.workspace.shared-root:}") String sharedRoot) {
        return new CodingWorkspaceService(ownerRoot, sharedRoot);
    }

    @Bean
    public DockerSandboxConfig codingDockerSandboxConfig(
            @Value("${coding.harness.tools.execute-process.sandbox.docker-executable:}")
            String dockerExecutable,
            @Value("${coding.harness.tools.execute-process.sandbox.docker-executable-sha256:}")
            String dockerExecutableSha256,
            @Value("${coding.harness.tools.execute-process.sandbox.docker-config-directory:}")
            String dockerConfigDirectory,
            @Value("${coding.harness.tools.execute-process.sandbox.docker-host:}")
            String dockerHost,
            @Value("${coding.harness.tools.execute-process.sandbox.image:}") String image) {
        if (dockerExecutable.isBlank() && dockerExecutableSha256.isBlank()
                && dockerConfigDirectory.isBlank() && dockerHost.isBlank() && image.isBlank()) {
            return DockerSandboxConfig.UNCONFIGURED;
        }
        return DockerSandboxConfig.configured(dockerExecutable, dockerExecutableSha256,
                dockerConfigDirectory, dockerHost, image);
    }

    @Bean
    public CodingToolRuntime codingToolRuntime(
            CodingWorkspaceService workspaceService, DockerSandboxConfig sandboxConfig,
            @Value("${coding.harness.tools.execute-process.enabled:false}")
            boolean executeProcessEnabled) {
        return new CodingToolRuntime(workspaceService, sandboxConfig, executeProcessEnabled);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService codingHarnessExecutor() {
        AtomicInteger threadIds = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "coding-harness-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(2, factory);
    }

    /**
     * SSE replay/drain runs on its own pool: a model turn occupying every run thread must never
     * starve durable event delivery for other runs (or for the same run's subscribers).
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService codingHarnessDeliveryExecutor() {
        AtomicInteger threadIds = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "coding-harness-delivery-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    @Bean
    public HarnessEventHub codingHarnessEventHub(
            FileHarnessStore store,
            @Qualifier("codingHarnessDeliveryExecutor") ExecutorService executor) {
        return new HarnessEventHub(store, executor);
    }

    @Bean
    @ConditionalOnMissingBean(CodingModelGateway.class)
    public CodingModelGateway codingModelGateway() {
        return new UnconfiguredCodingModelGateway();
    }

    @Bean
    public CodingRunProcessor codingRunProcessor(FileHarnessStore store,
                                                 HarnessEventHub eventHub,
                                                 CodingModelGateway modelGateway,
                                                 CodingToolRuntime tools,
                                                 ObjectMapper objectMapper) {
        return new CodingRunProcessor(store, eventHub, modelGateway, tools, objectMapper);
    }

    @Bean
    public CodingRunExecutor codingRunExecutor(
            @Qualifier("codingHarnessExecutor") ExecutorService executor,
            CodingRunProcessor processor) {
        return new CodingRunExecutor(executor, processor);
    }

    @Bean
    public CodingHarnessApplicationService codingHarnessApplicationService(
            FileHarnessStore store, HarnessEventHub eventHub,
            CodingWorkspaceService workspaceService, CodingRunExecutor runExecutor,
            CodingToolRuntime tools) {
        return new CodingHarnessApplicationService(store, eventHub, workspaceService,
                runExecutor, tools);
    }

    @Bean
    public CodingModelCatalog codingModelCatalog(
            @Value("${coding.harness.models:}") String models) {
        return CodingModelCatalog.fromCsv(models);
    }
}
