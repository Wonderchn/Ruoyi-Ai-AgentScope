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

class RunEventStreamDeliveryTest {
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
