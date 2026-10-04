package org.ruoyi.aiintegration.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class PrivateDocumentDeliveryTest {
    @Test void boundedLargePdfAcknowledgesOnlyAfterFinalDelivery() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] pdf=new byte[3*1024*1024];System.arraycopy("%PDF-1.7".getBytes(),0,pdf,0,8);
        var acknowledgements=new AtomicInteger();
        server.createContext("/pdf",x->{
            x.getResponseHeaders().set("Content-Type","application/pdf");
            x.getResponseHeaders().set("X-AI-Delivery-Permit","permit1");x.getResponseHeaders().set("X-AI-Delivery-Operation","operation1");
            x.sendResponseHeaders(200,pdf.length);x.getResponseBody().write(pdf);x.close();
        });
        server.createContext("/missing",x->{x.getResponseHeaders().set("Content-Type","application/pdf");x.sendResponseHeaders(200,8);x.getResponseBody().write(Arrays.copyOf(pdf,8));x.close();});
        server.createContext("/ack",x->{acknowledgements.incrementAndGet();x.getRequestBody().readAllBytes();x.sendResponseHeaders(204,-1);x.close();});
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();var client=new AiGatewayClient(2000);
            var ack=new AiGatewayClient.DeliveryAck(URI.create(base+"/ack"),"t1","platform:t1:1001","test-only");
            var request=new AiGatewayClient.ForwardRequest("GET",URI.create(base+"/pdf"),Map.of(),null);
            var response=new MockHttpServletResponse();
            client.forwardPrivateDocument(request,response,50*1024*1024,10000,ack);
            assertArrayEquals(pdf,response.getContentAsByteArray());assertEquals(1,acknowledgements.get());
            var tooLarge=new MockHttpServletResponse();
            assertThrows(AiGatewayClient.UpstreamUnavailableException.class,()->client.forwardPrivateDocument(request,tooLarge,1024,10000,ack));
            assertEquals(0,tooLarge.getContentAsByteArray().length);assertEquals(2,acknowledgements.get());
            var missing=new MockHttpServletResponse();
            assertThrows(AiGatewayClient.UpstreamUnavailableException.class,()->client.forwardPrivateDocument(new AiGatewayClient.ForwardRequest("GET",URI.create(base+"/missing"),Map.of(),null),missing,1024,10000,ack));
            assertEquals(0,missing.getContentAsByteArray().length);assertEquals(2,acknowledgements.get());
        } finally {server.stop(0);}
    }
}
