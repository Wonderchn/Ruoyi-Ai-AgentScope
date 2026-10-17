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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.dao.entity.BizChangeLogDO;
import com.nageoffer.ai.ragent.audit.dao.mapper.BizChangeLogMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.authorization.AiResourceAuthorizationService;
import com.nageoffer.ai.ragent.authorization.AiResourceController;
import com.nageoffer.ai.ragent.authorization.AiResourceWriteService;
import com.nageoffer.ai.ragent.authorization.AuthorizedDownloadService;
import com.nageoffer.ai.ragent.authorization.AuthorizedExportService;
import com.nageoffer.ai.ragent.authorization.TenantConversationReadRepository;
import com.nageoffer.ai.ragent.authorization.TenantEventReadRepository;
import com.nageoffer.ai.ragent.authorization.TenantRunReadRepository;
import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.context.PrincipalContext;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.security.ResourceAuthorizationService.Verdict;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F18:2 AI 资源面（复数 {@code /knowledge-bases}）的 KB 创建/删除必须落
 * {@code ai_biz_change_log}。
 *
 * <p><b>为什么必须有这条行为判据（而不是源码字符串断言）。</b>
 * {@code @LogRecord} 是<b>运行期 AOP</b>：注解在源码里存在、但 controller bean 没有被
 * advisor 代理时，日志一行都不会写，而所有静态检查都会通过。本测试因此：
 * <ol>
 *   <li>加载真实的 {@link AiEmbeddedObservabilityConfiguration.AuditRecording}
 *       （{@code @EnableLogRecord} + 真实 {@code BizChangeLogRecordService}）；</li>
 *   <li>注册<b>真实</b>的 {@link AiResourceController} bean（写服务用 mock，避免真库）；</li>
 *   <li>断言 bean 是 AOP 代理（{@link AopUtils#isAopProxy}），再调用真实方法；</li>
 *   <li>捕获 {@link BizChangeLogMapper#insert}，逐项核对 bizType/operationType/bizId/
 *       快照/operator/tenant/member 与失败行 success=false。</li>
 * </ol>
 * 改前：controller 无 {@code @LogRecord}（或 context 未接线）⇒ 无代理 / 无 insert ⇒ 红。
 */
@Tag("dev")
class AiResourceAuditCoverageTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "42";
    private static final String MEMBER = "platform:tenant-a:42";

    private final AiResourceAuthorizationService authorization = mock(AiResourceAuthorizationService.class);
    private final AiResourceWriteService writeService = mock(AiResourceWriteService.class);
    private final BizChangeLogMapper mapper = mock(BizChangeLogMapper.class);

    /**
     * 最小但真实的审计装配：{@code AuditRecording} 提供 {@code @EnableLogRecord} 的
     * advisor + 真实 writer；其余按 {@link AiResourceController} 的必需 setter 补齐 mock，
     * 不启动数据库、不加载整个 embedded 闭包。
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiEmbeddedObservabilityConfiguration.class)
            .withPropertyValues("ai.integration.enabled=true", "ai.integration.transport=local", "p2.enabled=false")
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(BizChangeLogMapper.class, () -> mapper)
            .withBean(BizChangeLogContext.class, () -> new BizChangeLogContext(new ObjectMapper()))
            .withBean(RevocationGuard.class, () -> mock(RevocationGuard.class))
            .withBean(AuthorizedDownloadService.class, () -> mock(AuthorizedDownloadService.class))
            .withBean(AuthorizedExportService.class, () -> mock(AuthorizedExportService.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(TenantConversationReadRepository.class, () -> mock(TenantConversationReadRepository.class))
            .withBean(TenantRunReadRepository.class, () -> mock(TenantRunReadRepository.class))
            .withBean(TenantEventReadRepository.class, () -> mock(TenantEventReadRepository.class))
            .withBean(AiResourceAuthorizationService.class, () -> authorization)
            .withBean(AiResourceWriteService.class, () -> writeService)
            .withBean(AiResourceController.class, () -> new AiResourceController(authorization, writeService));

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    @DisplayName("创建 KB 成功 → 真实代理写入 KNOWLEDGE_BASE/CREATE，bizId=kbId，after 快照，身份取主体")
    void createKnowledgeBaseProducesCreateAuditRow() {
        when(writeService.createKnowledgeBase(any())).thenReturn("kb-123");
        runner.run(context -> {
            AiResourceController controller = context.getBean(AiResourceController.class);
            assertThat(AopUtils.isAopProxy(controller))
                    .as("@LogRecord 必须真的代理真实 controller；仅源码有注解不算生效")
                    .isTrue();
            PrincipalContext.set(principal());
            try {
                controller.createKnowledgeBase(new AiResourceController.CreateKnowledgeBaseRequest(
                        "知识库A", "text-embedding-3", "coll-1"));

                BizChangeLogDO row = captureSingleInsert();
                assertThat(row.getBizType()).isEqualTo(BizChangeBizType.KNOWLEDGE_BASE);
                assertThat(row.getOperationType()).isEqualTo(BizChangeOperationType.CREATE);
                assertThat(row.getBizId()).isEqualTo("kb-123");
                assertThat(row.getActionDesc()).contains("创建知识库").contains("kb-123");
                assertThat(row.getAfterSnapshot())
                        .as("创建必须有 after 快照（至少一侧存在）")
                        .isNotNull().contains("kb-123");
                assertThat(row.getOperatorId()).isEqualTo(USER);
                assertThat(row.getTenantId()).isEqualTo(TENANT);
                assertThat(row.getMemberId()).isEqualTo(MEMBER);
                assertThat(row.getSuccess()).isTrue();
            } finally {
                PrincipalContext.clear();
            }
        });
    }

    @Test
    @DisplayName("删除 KB 成功 → KNOWLEDGE_BASE/DELETE，bizId=kbId，快照存在")
    void deleteKnowledgeBaseProducesDeleteAuditRow() {
        when(authorization.check(any(), anyString(), anyString())).thenReturn(Verdict.GRANT);
        runner.run(context -> {
            AiResourceController controller = context.getBean(AiResourceController.class);
            assertThat(AopUtils.isAopProxy(controller)).isTrue();
            PrincipalContext.set(principal());
            try {
                controller.deleteKnowledgeBase("kb-123");

                BizChangeLogDO row = captureSingleInsert();
                assertThat(row.getBizType()).isEqualTo(BizChangeBizType.KNOWLEDGE_BASE);
                assertThat(row.getOperationType()).isEqualTo(BizChangeOperationType.DELETE);
                assertThat(row.getBizId()).isEqualTo("kb-123");
                assertThat(row.getActionDesc()).contains("删除知识库").contains("kb-123");
                assertThat(row.getAfterSnapshot())
                        .as("删除必须有快照（至少一侧存在）")
                        .isNotNull().contains("TOMBSTONED");
                assertThat(row.getOperatorId()).isEqualTo(USER);
                assertThat(row.getTenantId()).isEqualTo(TENANT);
                assertThat(row.getMemberId()).isEqualTo(MEMBER);
                assertThat(row.getSuccess()).isTrue();
            } finally {
                PrincipalContext.clear();
            }
        });
    }

    @Test
    @DisplayName("创建 KB 失败 → 仍落行且 success=false（@LogRecord 既有失败语义）")
    void failedCreateStillWritesFailureRow() {
        when(writeService.createKnowledgeBase(any())).thenThrow(new ServiceException("写入 permit/授权服务不可用"));
        runner.run(context -> {
            AiResourceController controller = context.getBean(AiResourceController.class);
            PrincipalContext.set(principal());
            try {
                assertThatThrownBy(() -> controller.createKnowledgeBase(
                        new AiResourceController.CreateKnowledgeBaseRequest("知识库A", "text-embedding-3", "coll-1")))
                        .isInstanceOf(ServiceException.class);

                BizChangeLogDO row = captureSingleInsert();
                assertThat(row.getBizType()).isEqualTo(BizChangeBizType.KNOWLEDGE_BASE);
                assertThat(row.getOperationType()).isEqualTo(BizChangeOperationType.CREATE);
                assertThat(row.getSuccess())
                        .as("失败路径必须落 success=false 的行，而不是静默丢失")
                        .isFalse();
                assertThat(row.getErrorMessage()).as("失败描述不得为空").isNotNull().contains("失败");
                assertThat(row.getOperatorId()).isEqualTo(USER);
                assertThat(row.getTenantId()).isEqualTo(TENANT);
                assertThat(row.getMemberId()).isEqualTo(MEMBER);
            } finally {
                PrincipalContext.clear();
            }
        });
    }

    private BizChangeLogDO captureSingleInsert() {
        ArgumentCaptor<BizChangeLogDO> captor = ArgumentCaptor.forClass(BizChangeLogDO.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private static ExecutionPrincipal principal() {
        return new ExecutionPrincipal(TENANT, USER, MEMBER, 1, 1,
                Set.of("kb.write", "kb.delete"), "f18-2-jti", "platform", 0L, Long.MAX_VALUE);
    }
}
