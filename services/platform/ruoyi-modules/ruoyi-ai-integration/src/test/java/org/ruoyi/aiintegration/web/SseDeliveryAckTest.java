package org.ruoyi.aiintegration.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class SseDeliveryAckTest {
    @Test void completeFrameStripsPrivateMetadataAndAcknowledgesTrustedIdentity() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var body=new AtomicReference<String>();var calls=new AtomicInteger();
        server.createContext("/ack",x->{calls.incrementAndGet();body.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            x.sendResponseHeaders(204,-1);x.close();});server.start();
        try {
            var client=new AiGatewayClient(2000);var response=new MockHttpServletResponse();
            var ack=new AiGatewayClient.DeliveryAck(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/ack"),"t1","platform:t1:1001","test-only");
            client.deliverFrame(": ai-delivery permit-1 operation-1\nid: 1\ndata: {\"text\":\"ok\"}\n\n".getBytes(StandardCharsets.UTF_8),response,ack);
            assertEquals(1,calls.get());assertEquals("id: 1\ndata: {\"text\":\"ok\"}\n\n",response.getContentAsString());
            assertTrue(body.get().contains("\"memberId\":\"platform:t1:1001\""));assertTrue(body.get().contains("\"tenantId\":\"t1\""));
        } finally {server.stop(0);}
    }
    @Test void unprotectedOrDuplicateMetadataNeverReachesClient() {
        var client=new AiGatewayClient(2000);var response=new MockHttpServletResponse();
        assertThrows(AiGatewayClient.UpstreamUnavailableException.class,()->client.deliverFrame("id: 1\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8),response,null));
        assertThrows(AiGatewayClient.UpstreamUnavailableException.class,()->client.deliverFrame(": ai-delivery p1 o1\n: ai-delivery p2 o2\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8),response,null));
        assertEquals(0,response.getContentAsByteArray().length);
    }
    @Test void failedAckStopsBeforeFollowingFrame() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var count=new AtomicInteger();
        server.createContext("/",x->{count.incrementAndGet();x.sendResponseHeaders(503,-1);x.close();});server.start();
        try {
            var client=new AiGatewayClient(2000);var response=new MockHttpServletResponse();
            var ack=new AiGatewayClient.DeliveryAck(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"t1","platform:t1:1001","test-only");
            assertThrows(AiGatewayClient.UpstreamUnavailableException.class,()->{
                client.deliverFrame(": ai-delivery p1 o1\nid: 1\n\n".getBytes(StandardCharsets.UTF_8),response,ack);
                client.deliverFrame(": ai-delivery p2 o2\nid: 2\n\n".getBytes(StandardCharsets.UTF_8),response,ack);
            });assertEquals(1,count.get());assertEquals("id: 1\n\n",response.getContentAsString());
        } finally {server.stop(0);}
    }
}
