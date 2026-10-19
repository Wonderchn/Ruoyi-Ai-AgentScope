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

package com.nageoffer.ai.ragent.shortdrama.service;

import com.nageoffer.ai.ragent.shortdrama.controller.ShortDramaDtos;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;

/**
 * 行 → 公开视图的纯函数映射。
 *
 * <p>不回传 {@code tenantId} / {@code userId}：租户是服务端边界，属主是查询谓词；
 * 把二者放进响应只会给客户端提供不存在用途的身份字段。
 */
final class DramaViews {

    private DramaViews() {
    }

    static ShortDramaDtos.ProjectView project(DramaRows.ProjectRow row) {
        return new ShortDramaDtos.ProjectView(
                row.id(),
                row.projectName(),
                row.description(),
                row.status(),
                row.artStyle(),
                row.aspectRatio(),
                DramaInput.isoTime(row.createTime()),
                DramaInput.isoTime(row.updateTime()));
    }

    static ShortDramaDtos.ScriptView script(DramaRows.ScriptRow row) {
        return new ShortDramaDtos.ScriptView(
                row.id(),
                row.projectId(),
                row.scriptName(),
                row.scriptText(),
                row.outlineText(),
                row.tone(),
                row.sourceType(),
                DramaInput.isoTime(row.createTime()),
                DramaInput.isoTime(row.updateTime()));
    }

    static ShortDramaDtos.CharacterView character(DramaRows.CharacterRow row) {
        return new ShortDramaDtos.CharacterView(
                row.id(),
                row.name(),
                row.aliases(),
                row.introduction(),
                row.roleLevel(),
                row.gender(),
                row.ageRange(),
                row.personalityTags(),
                row.visualDescription());
    }

    static ShortDramaDtos.LocationView location(DramaRows.LocationRow row) {
        return new ShortDramaDtos.LocationView(
                row.id(),
                row.name(),
                row.summary(),
                row.hasCrowd(),
                row.crowdDescription(),
                row.descriptions());
    }
}
