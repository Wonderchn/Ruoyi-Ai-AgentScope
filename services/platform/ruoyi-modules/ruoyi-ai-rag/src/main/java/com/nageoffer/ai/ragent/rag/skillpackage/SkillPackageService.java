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

import org.springframework.web.multipart.MultipartFile;

/**
 * F11:4 有限技能包闭环：只做 {@code manifest.json + skill.md} 的往返。
 *
 * <p>实现由 {@code AgentSkillAdminServiceImpl} 提供（同一个 bean 也实现
 * {@code AgentSkillAdminService}），因此内嵌装配的
 * {@code @Import(AgentSkillAdminServiceImpl.class)} 无需新增登记。
 */
public interface SkillPackageService {

    /**
     * 校验并导入一个技能包；冲突默认拒绝，校验失败零写入。
     */
    SkillPackageImportResult importPackage(MultipartFile file);

    /**
     * 导出一个技能包；不导出内部 id/租户/创建人/凭据。
     */
    SkillPackageExport exportPackage(String id);
}
