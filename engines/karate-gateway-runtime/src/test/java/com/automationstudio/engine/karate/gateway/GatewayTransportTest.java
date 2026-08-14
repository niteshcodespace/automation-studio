package com.automationstudio.engine.karate.gateway;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GatewayTransportTest {
    @Test void rejectsRedirectWithoutContactingLocation() throws Exception {
        AtomicInteger redirected=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().set("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        server.start();
        try {
            int port=server.getAddress().getPort();URI uri=new URI("http://localhost:"+port+"/redirect");
            var target=new GatewayTargetPolicy.Authorized(uri,"localhost",List.of(InetAddress.getLoopbackAddress()));
            var request=new GatewayTransport.Request("GET",uri.toString(),Map.of(),new byte[0]);
            var failure=assertThrows(GatewayTargetPolicy.GatewayFailure.class,()->new GatewayTransport().execute(target,request,5_000));
            assertEquals("GATEWAY_REDIRECT_DENIED",failure.code());assertEquals(0,redirected.get());
        } finally { server.stop(0); }
    }
}
