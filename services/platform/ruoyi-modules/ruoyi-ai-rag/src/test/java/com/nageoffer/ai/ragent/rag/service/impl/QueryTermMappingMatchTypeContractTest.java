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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.security.P04AiErrorCode;
import com.nageoffer.ai.ragent.framework.security.P04AiException;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.QueryTermMappingUpdateRequest;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F07-A1 · match_type 口径定稿判据：仅 =1 生效，其余在管理面明确拒绝。
 *
 * <p><b>缺陷</b>：create/update 对 match_type 无任何校验——传 2/3/4 会 200 落库，
 * 而消费侧（{@code QueryTermMappingService.normalize}）只认 match_type=1，
 * 其余值被静默跳过 ⇒ "配了但不生效"，用户无从察觉。
 *
 * <p><b>判据</b>：非 1（2/3/4 及一切其它值）必须以 {@link P04AiErrorCode#BAD_REQUEST}
 * （HTTP 400 语义，经 {@code AiInternalExceptionResolver} 映射）明确拒绝，
 * 消息含"当前仅支持 match_type=1"；缺省与显式 =1 照常放行；拒绝时不得落库、不得清缓存。
 */
@Tag("dev")
class QueryTermMappingMatchTypeContractTest {

    private final QueryTermMappingMapper mapper = mock(QueryTermMappingMapper.class);
    private final QueryTermMappingCacheManager cacheManager = mock(QueryTermMappingCacheManager.class);
    private final BizChangeLogContext bizChangeLogContext = mock(BizChangeLogContext.class);
    private final QueryTermMappingAdminServiceImpl service =
            new QueryTermMappingAdminServiceImpl(mapper, cacheManager, bizChangeLogContext);

    @BeforeEach
    void stubExistingRule() {
        QueryTermMappingDO existing = new QueryTermMappingDO();
        existing.setId("m-1");
        existing.setSourceTerm("阿里");
        existing.setTargetTerm("阿里巴巴");
        existing.setMatchType(1);
        existing.setEnabled(1);
        when(mapper.selectById("m-1")).thenReturn(existing);
    }

    @Test
    @DisplayName("create：match_type=2/3/4 明确拒绝（400 语义），不落库、不清缓存")
    void createRejectsUnsupportedMatchTypes() {
        for (int unsupported : new int[]{2, 3, 4}) {
            QueryTermMappingCreateRequest request = new QueryTermMappingCreateRequest();
            request.setSourceTerm("阿里");
            request.setTargetTerm("阿里巴巴");
            request.setMatchType(unsupported);

            assertThatThrownBy(() -> service.create(request))
                    .as("create match_type=%s 必须被拒绝（现在会被静默落库，读侧却永不生效）", unsupported)
                    .isInstanceOfSatisfying(P04AiException.class, thrown -> {
                        assertThat(thrown.errorCode()).isEqualTo(P04AiErrorCode.BAD_REQUEST);
                        assertThat(thrown.getMessage()).contains("当前仅支持 match_type=1");
                    });
        }
        verify(mapper, never()).insert(any(QueryTermMappingDO.class));
        verifyNoInteractions(cacheManager);
    }

    @Test
    @DisplayName("update：match_type=2/3/4 同样拒绝；不入库、不清缓存")
    void updateRejectsUnsupportedMatchTypes() {
        for (int unsupported : new int[]{2, 3, 4}) {
            QueryTermMappingUpdateRequest request = new QueryTermMappingUpdateRequest();
            request.setMatchType(unsupported);

            assertThatThrownBy(() -> service.update("m-1", request))
                    .as("update match_type=%s 必须被拒绝（读侧只认 1，静默落库等于配置失效）", unsupported)
                    .isInstanceOfSatisfying(P04AiException.class, thrown -> {
                        assertThat(thrown.errorCode()).isEqualTo(P04AiErrorCode.BAD_REQUEST);
                        assertThat(thrown.getMessage()).contains("当前仅支持 match_type=1");
                    });
        }
        verify(mapper, never()).updateById(any(QueryTermMappingDO.class));
        verifyNoInteractions(cacheManager);
    }

    @Test
    @DisplayName("缺省=1 与显式 =1 照常放行；update 不传 match_type 不动原值")
    void defaultAndExplicitOneAreAccepted() {
        QueryTermMappingCreateRequest defaulted = new QueryTermMappingCreateRequest();
        defaulted.setSourceTerm("阿里");
        defaulted.setTargetTerm("阿里巴巴");
        service.create(defaulted);

        QueryTermMappingCreateRequest explicit = new QueryTermMappingCreateRequest();
        explicit.setSourceTerm("钉钉");
        explicit.setTargetTerm("DingTalk");
        explicit.setMatchType(1);
        service.create(explicit);

        ArgumentCaptor<QueryTermMappingDO> inserted = ArgumentCaptor.forClass(QueryTermMappingDO.class);
        verify(mapper, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues())
                .extracting(QueryTermMappingDO::getMatchType)
                .containsOnly(1);

        QueryTermMappingUpdateRequest update = new QueryTermMappingUpdateRequest();
        update.setTargetTerm("阿里巴巴集团");
        service.update("m-1", update);
        ArgumentCaptor<QueryTermMappingDO> updated = ArgumentCaptor.forClass(QueryTermMappingDO.class);
        verify(mapper, times(1)).updateById(updated.capture());
        assertThat(updated.getValue().getMatchType())
                .as("update 缺省 match_type 不得把原值改掉")
                .isEqualTo(1);
    }
}
