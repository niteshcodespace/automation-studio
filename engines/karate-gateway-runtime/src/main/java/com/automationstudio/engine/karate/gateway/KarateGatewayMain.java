package com.automationstudio.engine.karate.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.*;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Execution-scoped HTTP gateway. Arguments are provider-owned, bounded, and contain no secrets. */
public final class KarateGatewayMain {
    private KarateGatewayMain(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=4)System.exit(64);
        UUID correlation=UUID.fromString(args[0]);String base=args[1];long deadline=Long.parseLong(args[2]);boolean allowNonGlobal="test-loopback".equals(args[3]);
        GatewayTargetPolicy policy=new GatewayTargetPolicy(base,allowNonGlobal);GatewayTransport transport=new GatewayTransport();var secrets=new SecretChannel();
        HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",8080),8);server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("gateway-",0).factory()));
        server.createContext("/dispatch",exchange->dispatch(exchange,correlation,deadline,policy,transport,secrets));
        server.createContext("/health",exchange->{if(!correlation.toString().equals(exchange.getRequestHeaders().getFirst("X-AS-Correlation"))){exchange.sendResponseHeaders(404,-1);return;}exchange.sendResponseHeaders(204,-1);});
        server.start();
    }
    private static void dispatch(HttpExchange exchange,UUID correlation,long deadline,GatewayTargetPolicy policy,GatewayTransport transport,SecretChannel secrets)throws IOException{
        try{
            if(!"POST".equals(exchange.getRequestMethod())||!correlation.toString().equals(exchange.getRequestHeaders().getFirst("X-AS-Correlation"))){exchange.sendResponseHeaders(404,-1);return;}
            long remaining=deadline-Instant.now().toEpochMilli();if(remaining<=0)throw new GatewayTargetPolicy.GatewayFailure("GATEWAY_TIMEOUT");
            var request=GatewayWire.readRequest(exchange.getRequestBody());try(var authorized=authorize(policy,secrets,request)){var response=transport.execute(authorized.target(),authorized.request(),remaining);
            exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.sendResponseHeaders(200,0);GatewayWire.writeResponse(exchange.getResponseBody(),response);
            }
        }catch(GatewayTargetPolicy.GatewayFailure e){exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.sendResponseHeaders(200,0);GatewayWire.writeFailure(exchange.getResponseBody(),e.code());}
        catch(RuntimeException|IOException e){try{exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.sendResponseHeaders(200,0);GatewayWire.writeFailure(exchange.getResponseBody(),"GATEWAY_PROTOCOL_ERROR");}catch(IOException ignored){}}
        finally{exchange.close();}
    }
    static GatewayAuthentication.AuthorizedRequest authorize(GatewayTargetPolicy policy,SecretMaterializer secrets,GatewayTransport.Request request){var target=policy.authorize(request.url());return secrets.materialize(target,request);}
    interface SecretMaterializer{GatewayAuthentication.AuthorizedRequest materialize(GatewayTargetPolicy.Authorized target,GatewayTransport.Request request);}
    private static final class SecretChannel implements SecretMaterializer{private final DataInputStream in=new DataInputStream(new BufferedInputStream(System.in));private final DataOutputStream out=new DataOutputStream(new BufferedOutputStream(System.out));private final String type,placement;SecretChannel()throws IOException{type=secretText(in,32);placement=secretText(in,64);}public synchronized GatewayAuthentication.AuthorizedRequest materialize(GatewayTargetPolicy.Authorized target,GatewayTransport.Request request){return GatewayAuthentication.materialize(in,out,type,placement,target,request);}private static String secretText(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException();byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();return new String(b,java.nio.charset.StandardCharsets.UTF_8);}}
}
