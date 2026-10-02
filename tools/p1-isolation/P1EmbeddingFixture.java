/* Licensed under the Apache License, Version 2.0. */
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned loopback embedding fixture; authentication and retrieval remain actual production beans. */
public class P1EmbeddingFixture {
    public static void main(String[] args) throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        String vector = "[1," + "0,".repeat(1534) + "0]";
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes(); count.incrementAndGet();
            byte[] body = ("{\"data\":[{\"embedding\":"+vector+",\"index\":0}],\"model\":\"synthetic\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/count", exchange -> {
            byte[] body = String.valueOf(count.get()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); System.out.println("P1 owned embedding fixture ready");
    }
}
