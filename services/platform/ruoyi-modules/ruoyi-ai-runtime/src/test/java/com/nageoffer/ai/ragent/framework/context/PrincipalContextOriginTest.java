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

package com.nageoffer.ai.ragent.framework.context;

import com.alibaba.ttl.TtlRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class PrincipalContextOriginTest {
    @AfterEach void clear(){PrincipalContext.clear();}
    @Test void pairedNestedRestoreKeepsOriginalSynchronousOrigin(){
        ExecutionPrincipal outer=principal("1"),inner=principal("2");
        ExecutionPrincipal empty=PrincipalContext.set(outer);assertThat(PrincipalContext.isSynchronousOrigin()).isTrue();
        ExecutionPrincipal previous=PrincipalContext.set(inner);assertThat(previous).isSameAs(outer);
        PrincipalContext.restore(previous);assertThat(PrincipalContext.get()).isSameAs(outer);assertThat(PrincipalContext.isSynchronousOrigin()).isTrue();
        PrincipalContext.restore(empty);assertThat(PrincipalContext.get()).isNull();assertThat(PrincipalContext.isSynchronousOrigin()).isFalse();
    }
    @Test void ttlOnlyHolderAndNestedRestoreNeverAcquireOrigin() throws Exception {
        ExecutionPrincipal outer=principal("1");PrincipalContext.set(outer);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=new Thread(TtlRunnable.get(()->{try{
            assertThat(PrincipalContext.get()).isSameAs(outer);assertThat(PrincipalContext.isSynchronousOrigin()).isFalse();
            ExecutionPrincipal previous=PrincipalContext.set(principal("2"));assertThat(PrincipalContext.isSynchronousOrigin()).isTrue();
            PrincipalContext.restore(previous);assertThat(PrincipalContext.get()).isSameAs(outer);assertThat(PrincipalContext.isSynchronousOrigin()).isFalse();
        }catch(Throwable e){failure.set(e);}finally{PrincipalContext.clear();}}));
        worker.start();worker.join(5000);assertThat(worker.isAlive()).isFalse();assertThat(failure.get()).isNull();assertThat(PrincipalContext.isSynchronousOrigin()).isTrue();
    }
    @Test void unpairedRestorePreservesHolderButCannotPromoteOrigin(){
        ExecutionPrincipal principal=principal("1");PrincipalContext.restore(principal);
        assertThat(PrincipalContext.require()).isSameAs(principal);assertThat(PrincipalContext.isSynchronousOrigin()).isFalse();
    }
    static ExecutionPrincipal principal(String id){return new ExecutionPrincipal("tenant-a",id,"platform:tenant-a:"+id,3,4,Set.of("run.submit"),"test","platform",1,9999999999L);}
}
