package com.automationstudio.engine.karate.worker;

import com.intuit.karate.core.Config;
import com.intuit.karate.http.HttpClient;
import com.intuit.karate.http.HttpRequest;
import com.intuit.karate.http.Response;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Karate transport that exposes only the execution gateway; feature proxy/TLS settings are ignored. */
final class GatewayHttpClient implements HttpClient {
    private final URI gateway; private final UUID correlation; private final AtomicReference<String> gatewayFailure; private Config config;
    GatewayHttpClient(String gateway,UUID correlation,AtomicReference<String> failure){this.gateway=validate(gateway,correlation);this.correlation=correlation;this.gatewayFailure=failure;}
    @Override public void setConfig(Config value){this.config=value;}
    @Override public Config getConfig(){return config;}
    @Override public Response invoke(HttpRequest request){
        try{
            HttpURLConnection connection=(HttpURLConnection)gateway.toURL().openConnection(Proxy.NO_PROXY);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5_000);connection.setReadTimeout(60_000);connection.setRequestProperty("Content-Type","application/octet-stream");connection.setRequestProperty("X-AS-Correlation",correlation.toString());
            try(var out=new DataOutputStream(connection.getOutputStream())){text(out,request.getMethod());text(out,request.getUrl());Map<String,List<String>> headers=request.getHeaders()==null?Map.of():request.getHeaders();out.writeInt(headers.size());for(var e:headers.entrySet()){text(out,e.getKey());out.writeInt(e.getValue().size());for(String v:e.getValue())text(out,v);}bytes(out,request.getBody()==null?new byte[0]:request.getBody());}
            if(connection.getResponseCode()!=200)throw fail("GATEWAY_PROTOCOL_ERROR");
            try(var in=new DataInputStream(new BufferedInputStream(connection.getInputStream()))){int status=in.readInt(),count=in.readInt();if(count<0||count>128)throw fail("GATEWAY_PROTOCOL_ERROR");Map<String,List<String>> headers=new LinkedHashMap<>();for(int i=0;i<count;i++){String k=text(in,128);int n=in.readInt();if(n<0||n>32)throw fail("GATEWAY_PROTOCOL_ERROR");List<String> values=new ArrayList<>();for(int j=0;j<n;j++)values.add(text(in,4096));headers.put(k,List.copyOf(values));}byte[] body=bytes(in,10_485_760);if(status==599){String code=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("X-AS-Error")).flatMap(e->e.getValue().stream()).findFirst().orElse("GATEWAY_FAILED");throw fail(code.matches("GATEWAY_[A-Z_]{2,40}")?code:"GATEWAY_FAILED");}return new Response(status,Map.copyOf(headers),body);}
        }catch(GatewayFailure e){gatewayFailure.compareAndSet(null,e.code);throw e;}catch(IOException|RuntimeException e){gatewayFailure.compareAndSet(null,"GATEWAY_FAILED");throw fail("GATEWAY_FAILED");}
    }
    private static URI validate(String raw,UUID id){try{URI u=new URI(raw);String expected="as-karate-gateway-"+id.toString().replace("-","");if(!u.getScheme().equals("http")||!expected.equals(u.getHost())||u.getPort()!=8080||!"/dispatch".equals(u.getPath())||u.getRawQuery()!=null||u.getRawUserInfo()!=null||u.getRawFragment()!=null)throw new IllegalArgumentException();return u;}catch(Exception e){throw fail("GATEWAY_CAPABILITY_INVALID");}}
    private static void text(DataOutputStream out,String s)throws IOException{bytes(out,s.getBytes(StandardCharsets.UTF_8));}
    private static String text(DataInputStream in,int max)throws IOException{return new String(bytes(in,max),StandardCharsets.UTF_8);}
    private static void bytes(DataOutputStream out,byte[] b)throws IOException{if(b.length>1_048_576)throw fail("GATEWAY_REQUEST_LIMIT");out.writeInt(b.length);out.write(b);}
    private static byte[] bytes(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw fail("GATEWAY_PROTOCOL_ERROR");byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();return b;}
    private static GatewayFailure fail(String code){return new GatewayFailure(code);}
    static final class GatewayFailure extends RuntimeException{final String code;GatewayFailure(String code){super(code);this.code=code;}}
}
