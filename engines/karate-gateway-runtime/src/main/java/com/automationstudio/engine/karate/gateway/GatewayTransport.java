package com.automationstudio.engine.karate.gateway;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.conn.DnsResolver;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.HttpClients;

/** One-attempt transport: pinned DNS, verified TLS, no redirect/proxy/cookie state, bounded bytes. */
final class GatewayTransport {
    static final int MAX_REQUEST=1_048_576,MAX_WIRE=10_485_760,MAX_DECOMPRESSED=10_485_760;
    record Request(String method,String url,Map<String,List<String>> headers,byte[] body){}
    record Response(int status,Map<String,List<String>> headers,byte[] body){}
    Response execute(GatewayTargetPolicy.Authorized target,Request request,long remainingMillis){
        int timeout=(int)Math.max(1,Math.min(60_000,remainingMillis));
        DnsResolver pinned=host->{if(!host.equalsIgnoreCase(target.host()))throw new java.net.UnknownHostException();return target.addresses().toArray(java.net.InetAddress[]::new);};
        RequestConfig config=RequestConfig.custom().setConnectTimeout(Math.min(5_000,timeout)).setSocketTimeout(timeout).setConnectionRequestTimeout(Math.min(5_000,timeout)).setRedirectsEnabled(false).build();
        HttpUriRequest outbound=build(target,request); AtomicBoolean expired=new AtomicBoolean();
        try(var scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());var client=HttpClients.custom().setDnsResolver(pinned).disableRedirectHandling().disableAutomaticRetries().disableContentCompression().disableCookieManagement().setDefaultRequestConfig(config).build()){
            var deadline=scheduler.schedule(()->{expired.set(true);outbound.abort();},timeout,TimeUnit.MILLISECONDS);
            try(var response=client.execute(outbound)){
                int status=response.getStatusLine().getStatusCode();if(status>=300&&status<=399)throw fail("GATEWAY_REDIRECT_DENIED");Map<String,List<String>> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);int count=0;
                for(var h:response.getAllHeaders()){if(++count>128||h.getName().length()>128||h.getValue().length()>4096||control(h.getName())||control(h.getValue()))throw fail("GATEWAY_RESPONSE_HEADERS_INVALID");headers.computeIfAbsent(h.getName(),k->new ArrayList<>()).add(h.getValue());}
                byte[] wire=response.getEntity()==null?new byte[0]:bounded(response.getEntity().getContent(),MAX_WIRE,"GATEWAY_RESPONSE_LIMIT");
                String encoding=first(headers,"Content-Encoding");byte[] body;
                if(encoding==null||encoding.isBlank()||encoding.equalsIgnoreCase("identity"))body=wire;
                else if(encoding.equalsIgnoreCase("gzip")){try(var gzip=new GZIPInputStream(new ByteArrayInputStream(wire))){body=bounded(gzip,MAX_DECOMPRESSED,"GATEWAY_RESPONSE_LIMIT");}headers.remove("Content-Encoding");headers.remove("Content-Length");}
                else throw fail("GATEWAY_RESPONSE_ENCODING_DENIED");
                var immutable=new TreeMap<String,List<String>>(String.CASE_INSENSITIVE_ORDER);headers.forEach((k,v)->immutable.put(k,List.copyOf(v)));return new Response(status,Collections.unmodifiableMap(immutable),body);
            }finally{deadline.cancel(false);}
        }catch(GatewayTargetPolicy.GatewayFailure e){throw e;}catch(java.net.SocketTimeoutException e){throw fail("GATEWAY_TIMEOUT");}catch(IOException|RuntimeException e){throw fail(expired.get()?"GATEWAY_TIMEOUT":"GATEWAY_UPSTREAM_FAILED");}
    }
    private static HttpUriRequest build(GatewayTargetPolicy.Authorized target,Request request){
        String method=request.method().toUpperCase(Locale.ROOT);if(!Set.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS").contains(method))throw fail("GATEWAY_METHOD_DENIED");
        var b=RequestBuilder.create(method).setUri(target.uri());int count=0;for(var e:request.headers().entrySet()){String name=e.getKey().toLowerCase(Locale.ROOT);if(++count>128||Set.of("host","connection","proxy-connection","proxy-authorization","cookie","transfer-encoding","upgrade","forwarded").contains(name))throw fail("GATEWAY_REQUEST_HEADERS_DENIED");for(String value:e.getValue()){if(value.length()>4096||control(value)||control(name))throw fail("GATEWAY_REQUEST_HEADERS_DENIED");b.addHeader(e.getKey(),value);}}
        if(request.body().length>MAX_REQUEST)throw fail("GATEWAY_REQUEST_LIMIT");if(request.body().length>0)b.setEntity(new ByteArrayEntity(request.body()));return b.build();
    }
    private static String first(Map<String,List<String>> h,String k){var v=h.get(k);return v==null||v.isEmpty()?null:v.getFirst();}
    private static boolean control(String s){return s.codePoints().anyMatch(c->c<0x20||c==0x7f);}
    private static byte[] bounded(InputStream in,int max,String code)throws IOException{var out=new ByteArrayOutputStream(Math.min(max,8192));byte[] b=new byte[8192];int total=0,n;while((n=in.read(b,0,Math.min(b.length,max+1-total)))!=-1){total+=n;if(total>max)throw fail(code);out.write(b,0,n);}return out.toByteArray();}
    private static GatewayTargetPolicy.GatewayFailure fail(String code){return new GatewayTargetPolicy.GatewayFailure(code);}
}
