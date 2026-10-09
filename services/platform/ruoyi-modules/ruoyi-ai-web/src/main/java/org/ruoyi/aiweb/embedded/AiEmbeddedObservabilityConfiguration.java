package org.ruoyi.aiweb.embedded;

import com.nageoffer.ai.ragent.audit.controller.BizChangeLogController;
import com.nageoffer.ai.ragent.audit.service.impl.BizChangeLogServiceImpl;
import com.nageoffer.ai.ragent.rag.controller.RagTraceController;
import com.nageoffer.ai.ragent.rag.service.impl.RagTraceQueryServiceImpl;
import com.nageoffer.ai.ragent.rag.eval.EvalController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** RW-23: explicit tenant-scoped read surfaces; Dashboard is assembled separately after RW-23-R1/R2. */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedObservabilityConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @Import({RagTraceQueryServiceImpl.class, RagTraceController.class,
            BizChangeLogServiceImpl.class, BizChangeLogController.class})
    static class ReadSurfaces { }

    /** Enabling eval requires the actual rewrite/intent/retrieval chain, never a fallback. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @ConditionalOnProperty(name = "p2.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "ragent.eval.enabled", havingValue = "true")
    @Import(EvalController.class)
    static class Evaluation { }
}
