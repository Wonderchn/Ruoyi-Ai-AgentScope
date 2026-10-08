package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.controller.BizChangeLogController;
import com.nageoffer.ai.ragent.audit.dao.mapper.BizChangeLogMapper;
import com.nageoffer.ai.ragent.audit.service.impl.BizChangeLogServiceImpl;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.ingestion.service.impl.IntentTreeServiceImpl;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.controller.IntentTreeController;
import com.nageoffer.ai.ragent.rag.controller.QueryTermMappingController;
import com.nageoffer.ai.ragent.rag.controller.RagTraceController;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.eval.EvalController;
import com.nageoffer.ai.ragent.rag.service.impl.QueryTermMappingAdminServiceImpl;
import com.nageoffer.ai.ragent.rag.service.impl.RagTraceQueryServiceImpl;
import com.nageoffer.ai.ragent.sample.controller.SampleQuestionController;
import com.nageoffer.ai.ragent.sample.dao.mapper.SampleQuestionMapper;
import com.nageoffer.ai.ragent.sample.service.impl.SampleQuestionServiceImpl;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Real assembly implementations; persistence/Redis collaborators are test doubles. */
@Tag("dev")
class AiEmbeddedAdminActivationTest {
    private final ApplicationContextRunner configurations = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedIntentConfiguration.class,
                    AiEmbeddedObservabilityConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local", "p2.enabled=true");

    private ApplicationContextRunner collaborators(boolean redis) {
        var runner = configurations.withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(BizChangeLogContext.class, () -> new BizChangeLogContext(new ObjectMapper()))
                .withBean(IntentNodeMapper.class, () -> mock(IntentNodeMapper.class))
                .withBean(KnowledgeBaseMapper.class, () -> mock(KnowledgeBaseMapper.class))
                .withBean(QueryTermMappingMapper.class, () -> mock(QueryTermMappingMapper.class))
                .withBean(SampleQuestionMapper.class, () -> mock(SampleQuestionMapper.class))
                .withBean(RagTraceRunMapper.class, () -> mock(RagTraceRunMapper.class))
                .withBean(RagTraceNodeMapper.class, () -> mock(RagTraceNodeMapper.class))
                .withBean(UserMapper.class, () -> mock(UserMapper.class))
                .withBean(BizChangeLogMapper.class, () -> mock(BizChangeLogMapper.class));
        return redis ? runner.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class)) : runner;
    }

    @Test void actualServicesAndControllersAreAssembledExactlyOnce() {
        collaborators(true).run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : new Class<?>[]{IntentTreeController.class, QueryTermMappingController.class,
                    SampleQuestionController.class, RagTraceController.class, BizChangeLogController.class,
                    IntentTreeServiceImpl.class, QueryTermMappingAdminServiceImpl.class,
                    SampleQuestionServiceImpl.class, RagTraceQueryServiceImpl.class, BizChangeLogServiceImpl.class}) {
                assertThat(context.getBeansOfType(type)).hasSize(1);
                assertThat(context.getBean(type).getClass()).isEqualTo(type);
            }
            assertThat(context).doesNotHaveBean(EvalController.class);
        });
    }

    @Test void missingRedisFailsLoudlyInsteadOfInstallingPartialIntentServices() {
        collaborators(false).run(context -> assertThat(context).hasFailed());
    }

    @Test void enablingEvalWithoutItsRetrievalChainFailsLoudly() {
        collaborators(true).withPropertyValues("ragent.eval.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void everyGateClosesTheNewSurfacesWithoutRequiringCollaborators() {
        for (String property : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http", "p2.enabled=false"}) {
            configurations.withPropertyValues(property).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(IntentTreeController.class)
                        .doesNotHaveBean(RagTraceController.class).doesNotHaveBean(BizChangeLogController.class);
            });
        }
    }
}
