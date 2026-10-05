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

package com.nageoffer.ai.ragent.runtime.exec;

import com.nageoffer.ai.ragent.runtime.*;
import com.nageoffer.ai.ragent.framework.security.RevocationGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Provider I/O is covered independently from a worker lease and output delivery. */
@Component
public class ProviderCallBoundary {
    private final RunAccessService access;
    private final ObjectProvider<RevocationGuard> guards;
    public ProviderCallBoundary(RunAccessService access,ObjectProvider<RevocationGuard> guards){this.access=access;this.guards=guards;}
    public RevocationGuard.Operation enter(RunExecution execution) {
        execution.guard().commitAtomic(()->null);
        boolean ingest="document.ingest".equals(execution.run().action());
        var principal=access.current(execution.run(),ingest?Set.of("kb.read","document.read"):Set.of("kb.read"));
        var guard=guards.getIfAvailable();
        if(guard==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
        return guard.enter(principal,ingest?"document.read":"kb.read","run:"+execution.runId());
    }
}
