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

package com.nageoffer.ai.ragent.shortdrama;

import com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.web.DeliveryPermits;
import com.nageoffer.ai.ragent.shortdrama.dao.DramaRows;
import com.nageoffer.ai.ragent.shortdrama.service.DramaActor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/** F21 测试共用夹具：主体、行记录与交付许可。 */
final class DramaTestSupport {

    static final String TENANT = "tenant-1";
    static final String USER = "42";
    static final String MEMBERSHIP = "platform:tenant-1:42";

    private DramaTestSupport() {
    }

    static ExecutionPrincipal principal(String... scopes) {
        long now = Instant.now().getEpochSecond();
        return new ExecutionPrincipal(TENANT, USER, MEMBERSHIP, 1, 1,
                Set.of(scopes), UUID.randomUUID().toString(), "test-issuer", now, now + 3600);
    }

    static DramaActor actor() {
        return new DramaActor(TENANT, USER, MEMBERSHIP);
    }

    static DeliveryPermits.Permit permit() {
        RevocationGuard guard = mock(RevocationGuard.class);
        RevocationGuard.Operation operation =
                new RevocationGuard.Operation(guard, "permit-1", "operation-1");
        return new DeliveryPermits.Permit("permit-1", "operation-1", operation);
    }

    static Timestamp time() {
        return Timestamp.from(Instant.parse("2026-10-19T00:00:00Z"));
    }

    static DramaRows.ProjectRow projectRow(long id) {
        return new DramaRows.ProjectRow(id, 42L, "项目-" + id, "简介", "draft",
                "realistic", "9:16", time(), time(), TENANT);
    }

    static DramaRows.ScriptRow scriptRow(long id, long projectId) {
        return new DramaRows.ScriptRow(id, projectId, "剧本-" + id, "内景 客厅 白天\n内容",
                "大纲", "短剧", "manual", time(), time(), TENANT);
    }

    static DramaRows.CharacterRow characterRow(long id, long projectId) {
        return new DramaRows.CharacterRow(id, projectId, "角色-" + id, null, "介绍",
                "A", "女", "20-30", "果断", "长发", TENANT);
    }

    static DramaRows.LocationRow locationRow(long id, long projectId) {
        return new DramaRows.LocationRow(id, projectId, "场景-" + id, "说明", false,
                null, "[\"描述\"]", TENANT);
    }

    static DramaRows.StoryboardRow storyboardRow(long id, long projectId, long scriptId, int sceneNo) {
        return new DramaRows.StoryboardRow(id, projectId, scriptId, sceneNo, "第" + sceneNo + "场",
                "内容", "daily", "中景", "固定", null, "客厅", null, null, 15, TENANT);
    }
}
