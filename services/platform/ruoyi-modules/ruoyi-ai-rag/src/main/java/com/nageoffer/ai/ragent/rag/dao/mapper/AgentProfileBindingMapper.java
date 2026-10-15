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

package com.nageoffer.ai.ragent.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileBindingDO;

/**
 * P3-BIND 绑定引用表 Mapper。
 *
 * <p>置于 {@code rag.dao.mapper} 包内：内嵌装配的 {@code @MapperScan} 已覆盖该包，
 * 因此新增此 Mapper **不需要**改装配面（零共享面变更）。
 */
public interface AgentProfileBindingMapper extends BaseMapper<AgentProfileBindingDO> {
}
