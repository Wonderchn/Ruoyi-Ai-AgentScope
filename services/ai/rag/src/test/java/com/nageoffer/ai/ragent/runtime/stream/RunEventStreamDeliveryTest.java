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

package com.nageoffer.ai.ragent.runtime.stream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunEventStreamDeliveryTest {
    @Test void initialPrivateDenialAndUnavailableSourceWriteNoStreamOrPermit() throws Exception {
        var lifecycle=mock(com.nageoffer.ai.ragent.runtime.RunLifecycleService.class);
        var bus=mock(NotificationBus.class);
        var permits=mock(com.nageoffer.ai.ragent.runtime.web.DeliveryPermits.class);
        var principal=new com.nageoffer.ai.ragent.framework.context.ExecutionPrincipal("t1","1001","platform:t1:1001",1,1,java.util.Set.of("run.stream"),"j","platform",1,9999999999L);
        var service=new RunEventStreamService(null,lifecycle,bus,null,new com.fasterxml.jackson.databind.ObjectMapper(),null,permits);
        when(lifecycle.get(principal,"private")).thenThrow(new com.nageoffer.ai.ragent.runtime.RunApiException(com.nageoffer.ai.ragent.runtime.RunErrorCode.RESOURCE_NOT_FOUND_OR_FORBIDDEN));
        var denied=new MockHttpServletResponse();assertFalse(service.stream(principal,"private",0,denied));assertEquals(404,denied.getStatus());
        doThrow(new IllegalStateException("source unavailable")).when(lifecycle).get(principal,"private");
        var unknown=new MockHttpServletResponse();assertFalse(service.stream(principal,"private",0,unknown));assertEquals(503,unknown.getStatus());
        assertFalse(denied.getContentAsString().contains("id:"));assertFalse(unknown.getContentAsString().contains("id:"));
        verifyNoInteractions(bus,permits);
    }
    @Test void terminalDrainsEveryQueuedFrame() throws Exception {
        var response=new MockHttpServletResponse();
        var calls=new AtomicInteger();
        var sink=new RunEventStreamService.BoundedSink(response,20,10000,1000,s->{calls.incrementAndGet();return s;});
        for(int i=1;i<=11;i++) assertTrue(sink.enqueue("id: "+i+"\nevent: "+(i==11?"run.terminal":"run.status")+"\n\n"));
        sink.finish();
        var writer=sink.startWriter(new AtomicBoolean());writer.join(2000);
        assertFalse(writer.isAlive());assertEquals(11,calls.get());
        assertTrue(response.getContentAsString().endsWith("event: run.terminal\n\n"));
    }
    @Test void authorizationCheckedAtDeliveryAndRevocationDropsQueue() throws Exception {
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        var sink=new RunEventStreamService.BoundedSink(response,20,10000,1000,s->{
            if(calls.incrementAndGet()==2) throw new IllegalStateException("revoked");return s;});
        assertTrue(sink.enqueue("id: 1\n\n"));assertTrue(sink.enqueue("id: 2\n\n"));assertTrue(sink.enqueue("id: 3\n\n"));
        sink.finish();var writer=sink.startWriter(new AtomicBoolean());writer.join(2000);
        assertEquals("id: 1\n\n",response.getContentAsString());assertEquals(2,calls.get());assertTrue(sink.isClosed());
    }
    @Test void overflowDoesNotForgeCursorExpired() throws Exception {
        var response=new MockHttpServletResponse();var sink=new RunEventStreamService.BoundedSink(response,1,1024,1000,s->s);
        assertTrue(sink.enqueue("id: 1\n\n"));assertFalse(sink.enqueue("id: 2\n\n"));
        sink.close();assertEquals("",response.getContentAsString());assertFalse(sink.enqueue("id: 3\n\n"));
    }
}
