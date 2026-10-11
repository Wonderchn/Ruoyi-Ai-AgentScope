package org.ruoyi.aiweb.embedded;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import com.nageoffer.ai.ragent.audit.controller.BizChangeLogController;
import com.nageoffer.ai.ragent.audit.service.impl.BizChangeLogRecordService;
import com.nageoffer.ai.ragent.audit.service.impl.BizChangeLogServiceImpl;
import com.nageoffer.ai.ragent.audit.service.impl.RagentOperatorGetService;
import com.nageoffer.ai.ragent.rag.controller.RagTraceController;
import com.nageoffer.ai.ragent.rag.service.impl.RagTraceQueryServiceImpl;
import com.nageoffer.ai.ragent.rag.eval.EvalController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/** RW-23: explicit tenant-scoped read surfaces; Dashboard is assembled separately after RW-23-R1/R2. */
@AutoConfiguration
@ConditionalOnProperty(name = "ai.integration.enabled", havingValue = "true")
public class AiEmbeddedObservabilityConfiguration {
    /** All embedded writers need the SDK advisor and persistent sink, including writers outside P2. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEmbeddedLocal
    @EnableLogRecord(tenant = "ragent", proxyTargetClass = true)
    @ComponentScan(basePackageClasses = BizChangeLogRecordService.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {BizChangeLogRecordService.class, RagentOperatorGetService.class}))
    static class AuditRecording { }

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
