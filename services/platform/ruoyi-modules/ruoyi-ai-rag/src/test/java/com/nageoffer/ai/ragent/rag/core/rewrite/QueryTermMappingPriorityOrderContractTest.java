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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F07-A2 · 词映射排序口径契约判据（priority 定稿）。
 *
 * <p><b>矛盾</b>：读侧加载排序曾为 {@code comparing(priority).reversed()}（数值大者先生效），
 * 与权威口径相反——原库表 DDL 注释「优先级，数值越小优先级越高（先匹配长词）」、
 * DO/DTO javadoc「数值越小优先级越高（一般长词在前）」、管理面列表
 * {@code orderByAsc(priority)}（小数值在前）三处一致。
 *
 * <p><b>定稿口径</b>：priority 升序（数值越小越先应用；未设值排最后），
 * 同优先级按源词长度降序（长词在前，防长词被短词改写打断）。
 * 归一化是"按顺序逐条替换"的链式语义，先应用谁、结果就归谁，故顺序即行为。
 *
 * <p>本组用替身 mapper + 替身缓存管理器隔离数据库与 Redis；
 * 被判的是 {@code QueryTermMappingService.loadMappings} 的排序与 {@code normalize} 的应用顺序。
 */
@Tag("dev")
class QueryTermMappingPriorityOrderContractTest {

    private QueryTermMappingCacheManager cacheManager;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                QueryTermMappingDO.class);
    }

    // ---------------------------------------------------------------- priority 方向（核心红例）

    @Test
    @DisplayName("priority 方向：数值小的规则必须先被应用（1 先于 100，链式结果可区分）")
    void smallerPriorityValueIsAppliedFirst() {
        QueryTermMappingService service = serviceWithDbRows(
                mapping("阿里巴巴", "阿里集团", 100),
                mapping("阿里", "阿里巴巴", 1));

        assertThat(service.normalize("阿里"))
                .as("priority=1 的「阿里→阿里巴巴」先应用，其输出再被 priority=100 的规则改写")
                .isEqualTo("阿里集团");
        assertThat(cachedWriteOrder())
                .as("回填缓存的顺序即应用顺序：小数值在前")
                .containsExactly("阿里", "阿里巴巴");
    }

    @Test
    @DisplayName("回填顺序：priority 升序，未设优先级（null）排在显式优先级之后")
    void nullPrioritySortsAfterExplicitPriorities() {
        QueryTermMappingService service = serviceWithDbRows(
                mapping("钉钉", "DingTalk", null),
                mapping("阿里巴巴", "阿里集团", 100),
                mapping("阿里", "阿里巴巴", 1));

        service.normalize("无命中文本");

        assertThat(cachedWriteOrder())
                .as("1 → 100 → null（null 只兜脏数据，不应抢占最前）")
                .containsExactly("阿里", "阿里巴巴", "钉钉");
    }

    @Test
    @DisplayName("同优先级：长源词在前（现状钉死，与「先匹配长词」一致）")
    void samePriorityLongestSourceTermFirst() {
        QueryTermMappingService service = serviceWithDbRows(
                mapping("阿里", "阿里软件", 5),
                mapping("阿里巴巴", "阿里集团", 5));

        assertThat(service.normalize("阿里巴巴"))
                .as("长词「阿里巴巴」先被替换，短词「阿里」后应用其链式结果")
                .isEqualTo("阿里软件集团");
        assertThat(cachedWriteOrder())
                .as("同优先级按源词长度降序")
                .containsExactly("阿里巴巴", "阿里");
    }

    // ---------------------------------------------------------------- 夹具

    private QueryTermMappingService serviceWithDbRows(QueryTermMappingDO... rows) {
        QueryTermMappingMapper mapper = mock(QueryTermMappingMapper.class);
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
        cacheManager = mock(QueryTermMappingCacheManager.class);
        when(cacheManager.getMappingsFromCache()).thenReturn(null);
        return new QueryTermMappingService(mapper, cacheManager);
    }

    private List<String> cachedWriteOrder() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QueryTermMappingDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(cacheManager).saveMappingsToCache(captor.capture());
        return captor.getValue().stream().map(QueryTermMappingDO::getSourceTerm).toList();
    }

    private static QueryTermMappingDO mapping(String source, String target, Integer priority) {
        QueryTermMappingDO mapping = new QueryTermMappingDO();
        mapping.setId("m-" + source);
        mapping.setSourceTerm(source);
        mapping.setTargetTerm(target);
        mapping.setMatchType(1);
        mapping.setPriority(priority);
        mapping.setEnabled(1);
        return mapping;
    }
}
