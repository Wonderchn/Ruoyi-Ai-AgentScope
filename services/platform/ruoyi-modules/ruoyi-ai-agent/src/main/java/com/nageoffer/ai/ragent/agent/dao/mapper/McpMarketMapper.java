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

package com.nageoffer.ai.ragent.agent.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * MCP 市场 Mapper。
 *
 * <p>位于 {@code com.nageoffer.ai.ragent.agent.dao.mapper}——该包已被
 * {@code AiEmbeddedMapperConfiguration} 的 {@code @MapperScan} 覆盖，因此不需要
 * 修改任何 Mapper 扫描配置（三件套之一）。
 */
@Mapper
public interface McpMarketMapper extends BaseMapper<McpMarketDO> {
}
