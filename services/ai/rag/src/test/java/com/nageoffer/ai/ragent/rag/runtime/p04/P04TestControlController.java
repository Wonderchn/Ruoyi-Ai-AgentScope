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

package com.nageoffer.ai.ragent.rag.runtime.p04;

import com.nageoffer.ai.ragent.framework.security.ApiEnvelope;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * <b>测试专用</b>控制面：在靶场运行期改动合成 ACL。
 *
 * <p>服务于 N07「撤销 AI ACL 但 platform pv 不变 → 资源拒绝」这类场景。它改的是合成替身，
 * 不触达任何真实资源事实。只存在于测试源集。
 */
@RestController
public class P04TestControlController {

    private final SyntheticAclProvider aclProvider;

    public P04TestControlController(SyntheticAclProvider aclProvider) {
        this.aclProvider = aclProvider;
    }

    public record AclRequest(String tenantId, String membershipId, String action, String resourceRef,
                             boolean granted) {
    }

    @PostMapping("/p04/control/acl")
    public ApiEnvelope<Map<String, Object>> setAcl(@RequestBody AclRequest request) {
        aclProvider.setGranted(request.tenantId(), request.membershipId(), request.action(),
                request.resourceRef(), request.granted());
        return ApiEnvelope.ok(Map.of("granted", request.granted(), "action", request.action(),
                "resourceRef", request.resourceRef()));
    }
}
