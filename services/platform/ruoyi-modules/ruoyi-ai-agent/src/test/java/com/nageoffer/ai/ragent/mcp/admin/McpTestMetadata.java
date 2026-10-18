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

package com.nageoffer.ai.ragent.mcp.admin;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketDO;
import com.nageoffer.ai.ragent.agent.dao.entity.McpMarketToolDO;
import com.nageoffer.ai.ragent.agent.dao.entity.McpToolDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯单元测试里 {@code LambdaQueryWrapper.getSqlSegment()} 需要 MyBatis-Plus 的列缓存；
 * 正常由 Mapper 注册时初始化。这里显式注册三个新实体（不启动容器），
 * 使测试能对 wrapper 的 SQL 片段断言 tenant_id 谓词。
 */
final class McpTestMetadata {

    private static boolean loaded;

    private McpTestMetadata() {
    }

    static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        init(McpMarketDO.class);
        init(McpMarketToolDO.class);
        init(McpToolDO.class);
        loaded = true;
    }

    private static void init(Class<?> entity) {
        if (TableInfoHelper.getTableInfo(entity) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }
}
