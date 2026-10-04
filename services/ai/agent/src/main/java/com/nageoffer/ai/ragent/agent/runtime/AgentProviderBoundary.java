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

package com.nageoffer.ai.ragent.agent.runtime;

import com.nageoffer.ai.ragent.runtime.RunAccessService;
import com.nageoffer.ai.ragent.runtime.exec.RunExecution;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import com.nageoffer.ai.ragent.runtime.usage.EgressPolicy;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.Set;

@Component
@ConditionalOnProperty(name="p3.enabled",havingValue="true")
public class AgentProviderBoundary {
    private final RunAccessService access;
    private final RevocationGuard revocation;
    private final EgressPolicy egress;
    public AgentProviderBoundary(RunAccessService access,RevocationGuard revocation,EgressPolicy egress){this.access=access;this.revocation=revocation;this.egress=egress;}
    public RevocationGuard.Operation enter(RunExecution execution,String provider) {
        execution.guard().commitAtomic(()->null);
        var principal=access.current(execution.run(),Set.of("agent.execute","kb.read"));
        egress.requireAllowed(provider);
        return revocation.enter(principal,"agent.execute","run:"+execution.runId());
    }
}
