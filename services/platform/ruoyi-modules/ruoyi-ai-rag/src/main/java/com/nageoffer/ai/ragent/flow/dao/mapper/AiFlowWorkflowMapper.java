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

package com.nageoffer.ai.ragent.flow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowWorkflowDO;

/**
 * AIFlow 工作流定义 Mapper。
 *
 * <p><b>装配要求</b>：本接口位于 {@code com.nageoffer.ai.ragent.flow.dao.mapper}，<b>不在</b>
 * platform 的 {@code @MapperScan("${mybatis-plus.mapperPackage}")} 值
 * （{@code org.ruoyi.**.mapper}，{@code application.yml:205}）覆盖范围内。
 * 必须由 {@code AiEmbeddedMapperConfiguration} 的显式 {@code @MapperScan} 数组登记本包，
 * 否则注入本 Mapper 的服务会以"缺 bean"启动失败。见交付说明的 imports 补丁。
 */
public interface AiFlowWorkflowMapper extends BaseMapper<AiFlowWorkflowDO> {
}
