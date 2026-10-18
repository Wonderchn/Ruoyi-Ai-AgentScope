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
        // F13:2：flow.run 的资源是运行实例（ai_resource RUN），不是知识库/文档；
        // 若仍走 kb.read 分支，执行首步会因缺 kb scope 403（坑 2）。
        if (FlowRunActions.FLOW_RUN.equals(execution.run().action())) {
            var flowPrincipal=access.current(execution.run(),Set.of(FlowRunActions.FLOW_RUN));
            var flowGuard=guards.getIfAvailable();
            if(flowGuard==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
            return flowGuard.enter(flowPrincipal,FlowRunActions.FLOW_RUN,"run:"+execution.runId());
        }
        boolean ingest=java.util.Set.of("document.ingest","ingestion.run").contains(execution.run().action());
        var principal=access.current(execution.run(),ingest?Set.of("kb.read","document.read"):Set.of("kb.read"));
        var guard=guards.getIfAvailable();
        if(guard==null) throw new RunApiException(RunErrorCode.AUTHORIZATION_UNAVAILABLE);
        return guard.enter(principal,ingest?"document.read":"kb.read","run:"+execution.runId());
    }

    /** 字面量锚点：runtime 模块不反向依赖 flow 模块，故动作名落在这里。 */
    private static final class FlowRunActions {
        private static final String FLOW_RUN = "flow.run";

        private FlowRunActions() {
        }
    }
}
