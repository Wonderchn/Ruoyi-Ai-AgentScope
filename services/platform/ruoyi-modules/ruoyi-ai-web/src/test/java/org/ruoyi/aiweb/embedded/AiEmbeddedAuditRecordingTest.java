package org.ruoyi.aiweb.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzt.logapi.beans.Operator;
import com.mzt.logapi.service.ILogRecordService;
import com.mzt.logapi.service.IOperatorGetService;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.dao.entity.BizChangeLogDO;
import com.nageoffer.ai.ragent.audit.dao.mapper.BizChangeLogMapper;
import com.nageoffer.ai.ragent.audit.service.impl.BizChangeLogRecordService;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Set;

/** Invoke the real SDK advisor and writer; database identity is verified separately on the live application. */
@Tag("dev")
class AiEmbeddedAuditRecordingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedObservabilityConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local", "p2.enabled=false");

    @Test
    void annotatedWriteReachesTheRealWriterEvenWithP2Disabled() {
        BizChangeLogMapper mapper = mock(BizChangeLogMapper.class);
        runner.withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(BizChangeLogMapper.class, () -> mapper)
                .withBean(AuditedWrite.class, AuditedWrite::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ILogRecordService.class)
                            .hasSingleBean(IOperatorGetService.class);
                    assertThat(context.getBean(ILogRecordService.class)).isInstanceOf(BizChangeLogRecordService.class);
                    assertThat(AopUtils.isAopProxy(context.getBean(AuditedWrite.class))).isTrue();
                    PrincipalContext.set(principal());
                    try {
                        Operator operator = context.getBean(IOperatorGetService.class).getUser();
                        assertThat(operator.getOperatorId()).isEqualTo("42");
                        context.getBean(AuditedWrite.class).write();
                        ArgumentCaptor<BizChangeLogDO> captured = ArgumentCaptor.forClass(BizChangeLogDO.class);
                        verify(mapper).insert(captured.capture());
                        assertThat(captured.getValue().getBizType()).isEqualTo("AGENT_SKILL");
                        assertThat(captured.getValue().getBizId()).isEqualTo("audit-fixture");
                        assertThat(captured.getValue().getOperationType()).isEqualTo("UPDATE");
                        assertThat(captured.getValue().getActionDesc()).isEqualTo("fixture changed");
                        assertThat(captured.getValue().getOperatorId()).isEqualTo("42");
                        assertThat(captured.getValue().getTenantId()).isEqualTo("tenant-a");
                        assertThat(captured.getValue().getMemberId()).isEqualTo("platform:tenant-a:42");
                        assertThat(captured.getValue().getSuccess()).isTrue();
                    } finally {
                        PrincipalContext.clear();
                    }
                });
    }

    @Test
    void closedEmbeddedGatesDoNotAssembleAuditOrRequireItsDependencies() {
        for (String property : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http"}) {
            runner.withPropertyValues(property).run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(ILogRecordService.class)
                        .doesNotHaveBean(IOperatorGetService.class);
            });
        }
    }

    @Test
    void legacyUserContextCannotReplaceTheExecutionPrincipal() {
        BizChangeLogMapper mapper = mock(BizChangeLogMapper.class);
        runner.withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(BizChangeLogMapper.class, () -> mapper)
                .withBean(AuditedWrite.class, AuditedWrite::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserContext.set(LoginUser.builder().userId("platform:tenant-a:42")
                            .username("audit-fixture-user").role("admin").build());
                    PrincipalContext.set(principal());
                    try {
                        assertThat(context.getBean(IOperatorGetService.class).getUser().getOperatorId())
                                .isEqualTo("42");
                        context.getBean(AuditedWrite.class).write();
                        ArgumentCaptor<BizChangeLogDO> captured = ArgumentCaptor.forClass(BizChangeLogDO.class);
                        verify(mapper).insert(captured.capture());
                        assertThat(captured.getValue().getOperatorId()).isEqualTo("42");
                        assertThat(captured.getValue().getOperatorName()).isNull();
                        assertThat(captured.getValue().getOperatorRole()).isNull();
                    } finally {
                        UserContext.clear();
                        PrincipalContext.clear();
                    }
                });
    }

    @Test
    void writerWithoutExecutionPrincipalRejectsBeforeInsert() {
        BizChangeLogMapper mapper = mock(BizChangeLogMapper.class);
        BizChangeLogRecordService writer = new BizChangeLogRecordService(mapper, new ObjectMapper());
        assertThatThrownBy(() -> writer.record(com.mzt.logapi.beans.LogRecord.builder()
                .type("AGENT_SKILL").bizNo("missing-principal").subType("UPDATE")
                .action("fixture changed").operator("untrusted").build())).isInstanceOf(ClientException.class);
        verifyNoInteractions(mapper);
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal("tenant-a", "42", "platform:tenant-a:42", 1, 1,
                Set.of("skill.write"), "audit-fixture-jti", "test", 0, Long.MAX_VALUE);
    }

    @Test
    void independentlyDiscoveredNestedConfigurationKeepsBothGates() {
        for (String property : new String[]{"ai.integration.enabled=false", "ai.integration.transport=http"}) {
            new ApplicationContextRunner().withUserConfiguration(AiEmbeddedObservabilityConfiguration.AuditRecording.class)
                    .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local", property)
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ILogRecordService.class)
                            .doesNotHaveBean(IOperatorGetService.class));
        }
    }

    static class AuditedWrite {
        @LogRecord(success = "fixture changed", type = "AGENT_SKILL", bizNo = "audit-fixture", subType = "UPDATE")
        public void write() {
        }
    }
}
