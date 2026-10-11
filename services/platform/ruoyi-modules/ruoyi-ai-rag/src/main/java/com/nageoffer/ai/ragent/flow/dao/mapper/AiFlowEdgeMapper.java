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
import com.nageoffer.ai.ragent.flow.dao.entity.AiFlowEdgeDO;

/**
 * AIFlow 工作流边 Mapper。
 *
 * <p>本接口与 {@link AiFlowWorkflowMapper} 同包，已在
 * {@code AiEmbeddedMapperConfiguration} 的显式 {@code @MapperScan} 数组末项登记
 * —— 新增同包 Mapper 无需改扫描清单。
 */
public interface AiFlowEdgeMapper extends BaseMapper<AiFlowEdgeDO> {
}
