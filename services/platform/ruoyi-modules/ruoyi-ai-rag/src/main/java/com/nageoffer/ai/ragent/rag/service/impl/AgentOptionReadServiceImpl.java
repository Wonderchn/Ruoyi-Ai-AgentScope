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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentOptionVO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.service.AgentOptionReadService;
import com.nageoffer.ai.ragent.template.PublicTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

/**
 * 用户端智能体发现面读端实现（F09:4-A）。
 *
 * <p><b>数据来源恰两个</b>：
 * <ol>
 *   <li>租户域 {@code ai_agent_profile}：{@code selectList(Wrappers.lambdaQuery(...))}
 *       与 {@code AgentProfileAdminServiceImpl.list():77} 同形——<b>不显式加 tenant 条件</b>，
 *       租户过滤链（MyBatis-Plus 租户插件）+ {@code @TableLogic deleted=0} 由框架追加，
 *       显式再写一遍反而会与过滤链形成第二处口径；</li>
 *   <li>公共模板域：<b>仅当</b>租户域没有任何内置行时才调
 *       {@link PublicTemplateRepository#findProfiles()}（模板行落在保留租户
 *       {@code __public_template__}，租户过滤链看不到）。租户域已有内置行时不触碰模板域，
 *       与 {@code AgentProfileAdminServiceImpl.loadPrompts} 的"本域优先、缺席才回退"口径一致。</li>
 * </ol>
 *
 * <p><b>executable 真值表（唯一结论，逐行给出）</b>：
 * <ul>
 *   <li>有激活租户行 ⇒ 仅该激活行为 {@code true}；</li>
 *   <li>无激活行、有租户内置行 ⇒ 仅该租户内置行为 {@code true}；</li>
 *   <li>无激活行、无租户内置行、模板域有内置行 ⇒ 仅该模板内置行为 {@code true}
 *       （此时模板行才进入列表，且 {@code executable=true}）；</li>
 *   <li>三处皆无 ⇒ 列表里全部 {@code false}，并 log.warn（降级必须可观测，不编造可执行项）。</li>
 * </ul>
 * 同一真值表下，"模板行存在但租户已有激活/内置行"时模板行 {@code executable=false}——
 * 它只是可见兜底，不夺权。
 *
 * <p><b>列表口径（取舍已显式登记，供协调者终裁）</b>：
 * <ol>
 *   <li>租户域行：内置在前 → {@code createTime} 升序（nullsLast）→ {@code id} 升序。
 *       前两键与 {@code AgentProfileAdminServiceImpl.list():79-80} 的比较器同形，第三键是
 *       本实现补的确定性尾键（同一 createTime 的行顺序不再依赖数据库返回序）；</li>
 *   <li>模板域兜底行：<b>末尾追加</b>，不参与上面的排序，也不与租户行混排。</li>
 * </ol>
 * 备选口径 R1＝把模板内置行按"builtin 在前"并入统一排序（那样它会排在全部租户普通行之前）。
 * 本实现取 R2，依据：真值表第 1/3 行的字面行序写作"全部租户行 ＋（若…）模板内置行"，
 * 且模板行在本契约里的角色是"追加的兜底可执行项"。两口径的差异<b>只在模板行位置</b>，
 * {@code executable} 判定完全相同；若裁决定为 R1，只需改本方法里的追加为并入排序（一处）。
 */
@Service
@RequiredArgsConstructor
public class AgentOptionReadServiceImpl implements AgentOptionReadService {

    private static final Logger log = LoggerFactory.getLogger(AgentOptionReadServiceImpl.class);

    /**
     * 租户域排序：内置在前 → createTime 升序（nullsLast）→ id 升序。
     * 见类注释"列表口径"（第三键为本实现补的确定性尾键）。
     */
    private static final Comparator<AgentProfileDO> TENANT_ORDER =
            Comparator.comparing((AgentProfileDO each) -> !isTrue(each.getBuiltin()))
                    .thenComparing(AgentProfileDO::getCreateTime, Comparator.nullsLast(Comparator.<Date>naturalOrder()))
                    .thenComparing(AgentProfileDO::getId);

    private final AgentProfileMapper agentProfileMapper;
    private final PublicTemplateRepository templateRepository;

    @Override
    public List<AgentOptionVO> listOptions() {
        List<AgentProfileDO> rows = agentProfileMapper.selectList(Wrappers.lambdaQuery(AgentProfileDO.class));

        AgentProfileDO activeRow = firstEligible(rows, AgentProfileDO::getActive);
        AgentProfileDO tenantBuiltin = firstEligible(rows, AgentProfileDO::getBuiltin);

        // 仅当租户域没有内置行时，才去模板域找兜底内置行（本域优先，缺席才回退）。
        // 注意：这一步与"是否存在激活行"无关——真值表要求模板内置行在"租户无内置行"时
        // 进入列表（此时它 executable=false，不夺权），因此这里必须读。
        PublicTemplateRepository.TemplateProfile templateBuiltin = null;
        if (tenantBuiltin == null) {
            templateBuiltin = templateRepository.findProfiles().stream()
                    .filter(profile -> isTrue(profile.builtin()))
                    .findFirst()
                    .orElse(null);
        }

        // 唯一结论：真值表前三况按优先级取一个 id；三处皆无时为 null（全部 false）。
        String executableId = activeRow != null ? activeRow.getId()
                : tenantBuiltin != null ? tenantBuiltin.getId()
                : templateBuiltin != null ? templateBuiltin.id()
                : null;
        if (executableId == null) {
            log.warn("用户端智能体列表无可执行行：租户域既无激活行也无内置行，模板域也无内置行"
                    + "（degraded；按约定不编造可执行项，全部 executable=false）");
        }

        List<AgentOptionVO> options = new ArrayList<>();
        rows.stream()
                .sorted(TENANT_ORDER)
                .forEach(row -> options.add(new AgentOptionVO(
                        row.getId(),
                        row.getName(),
                        row.getDescription(),
                        row.getAvatar(),
                        isTrue(row.getActive()),
                        executableId != null && executableId.equals(row.getId()))));

        if (templateBuiltin != null) {
            options.add(new AgentOptionVO(
                    templateBuiltin.id(),
                    templateBuiltin.name(),
                    templateBuiltin.description(),
                    templateBuiltin.avatar(),
                    isTrue(templateBuiltin.active()),
                    executableId != null && executableId.equals(templateBuiltin.id())));
        }
        return options;
    }

    /**
     * 取该标志位上"最先生效"的行：{@code createTime} 升序（nullsLast）→ {@code id} 升序。
     *
     * <p>与 {@code AgentPromptResolver.loadFromDb}（{@code orderByAsc(createTime).orderByAsc(id)}）
     * 的选择口径一致：多行同时置位时，运行期真正生效的那一行必须也是本面标为 executable 的那一行，
     * 否则"列表说 A 可执行、运行期其实用 B"会静默成立。
     */
    private static AgentProfileDO firstEligible(List<AgentProfileDO> rows,
                                                Function<AgentProfileDO, Integer> flag) {
        return rows.stream()
                .filter(row -> isTrue(flag.apply(row)))
                .min(Comparator.comparing(AgentProfileDO::getCreateTime,
                                Comparator.nullsLast(Comparator.<Date>naturalOrder()))
                        .thenComparing(AgentProfileDO::getId))
                .orElse(null);
    }

    /** 与 {@code AgentProfileAdminServiceImpl.isTrue:399-401} 同形（null 不视为 1）。 */
    private static boolean isTrue(Integer flag) {
        return flag != null && flag == 1;
    }
}
