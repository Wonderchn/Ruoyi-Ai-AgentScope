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

package org.ruoyi.aiintegration.p04;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.aiintegration.authorization.InternalAuthorizationController;
import org.ruoyi.aiintegration.authorization.PlatformFaultInjector;
import org.ruoyi.aiintegration.config.P04PlatformProperties;
import org.ruoyi.aiintegration.web.P04ErrorCode;
import org.ruoyi.aiintegration.web.P04Exception;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class P04AuthorizationTest {

    @Test
    void unknownAndMissingActionsAreForbiddenIndependentlyOfAiValidation() {
        String credential = UUID.randomUUID().toString();
        P04PlatformProperties properties = new P04PlatformProperties();
        properties.setServiceCredential(credential);
        InternalAuthorizationController controller = new InternalAuthorizationController(
                new SyntheticPlatformIdentitySource(), properties,
                new StaticListableBeanFactory().getBeanProvider(PlatformFaultInjector.class));
        for (String action : new String[]{null, "", "unknown", "RAG.CHAT", "rag.chat "}) {
            var request = new InternalAuthorizationController.CheckRequest(
                    "T1", "sub-u1", "M1", 1, action, "KB-A");
            P04Exception exception = assertThrows(P04Exception.class,
                    () -> controller.check(credential, null, request));
            assertEquals(P04ErrorCode.FORBIDDEN, exception.errorCode());
        }
        var legal = new InternalAuthorizationController.CheckRequest("T1", "sub-u1", "M1", 1,
                "rag.chat", "KB-A");
        assertEquals(true, controller.check(credential, null, legal).data().allowed());
    }
}
