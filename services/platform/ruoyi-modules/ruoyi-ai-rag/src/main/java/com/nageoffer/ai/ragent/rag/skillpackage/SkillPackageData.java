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

package com.nageoffer.ai.ragent.rag.skillpackage;

import java.util.List;

/**
 * 包内业务数据（manifest 字段 + skill.md 正文）。
 *
 * <p>刻意只保留契约字段：内部主键、租户、创建人/更新时间、凭据都不在这里，
 * 从类型上保证导出无法带出内部身份，导入也无法恢复包内身份。
 */
public record SkillPackageData(
        String skillCode,
        String name,
        String description,
        List<String> toolIds,
        int sortOrder,
        boolean enabled,
        String content) {
}
