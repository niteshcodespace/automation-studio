package com.automationstudio.engine.karate.gateway;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Request-scoped credential material obtained only after target authorization and collision checks. */
final class GatewayAuthentication implements AutoCloseable {
    private static final int REQUEST=1,OK=0,MAX=65_536;private final List<char[]> values;
    private GatewayAuthentication(List<char[]> values){this.values=values;}
    record AuthorizedRequest(GatewayTargetPolicy.Authorized target,GatewayTransport.Request request,GatewayAuthentication material) implements AutoCloseable{public void close(){material.close();}}
    static AuthorizedRequest materialize(DataInputStream in,DataOutputStream out,String type,String placement,GatewayTargetPolicy.Authorized target,GatewayTransport.Request request){preflight(type,placement,target,request);List<char[]> values=new ArrayList<>();try{out.writeInt(REQUEST);out.flush();if(in.readInt()!=OK)throw fail("SECRET_RESOLUTION_FAILED");int count=in.readInt();if(count<0||count>2)throw new IOException();for(int i=0;i<count;i++)values.add(text(in,MAX).toCharArray());GatewayAuthentication material=new GatewayAuthentication(values);try{return material.inject(type,placement,target,request);}catch(RuntimeException e){material.close();throw e;}}catch(IOException e){values.forEach(value->Arrays.fill(value,'\0'));throw fail("SECRET_RESOLUTION_FAILED");}}
    private static void preflight(String type,String placement,GatewayTargetPolicy.Authorized target,GatewayTransport.Request request){validateHeaders(request.headers());conflict(request.headers(),"Authorization");switch(type){case "NONE","BEARER","BASIC"->{}case "API_KEY_HEADER"->conflict(request.headers(),placement);case "API_KEY_QUERY"->{if(queryContains(target.uri().getRawQuery(),placement))throw fail("AUTH_CONFIGURATION_CONFLICT");}default->throw fail("AUTH_INJECTION_FAILED");}}
    private AuthorizedRequest inject(String type,String placement,GatewayTargetPolicy.Authorized target,GatewayTransport.Request request){Map<String,List<String>> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);headers.putAll(request.headers());GatewayTargetPolicy.Authorized updated=target;switch(type){case "NONE"->{if(!values.isEmpty())throw fail("AUTH_INJECTION_FAILED");}case "BEARER"->{require(1);conflict(headers,"Authorization");headers.put("Authorization",List.of("Bearer "+value(0)));}case "BASIC"->{require(2);conflict(headers,"Authorization");String encoded=Base64.getEncoder().encodeToString((value(0)+":"+value(1)).getBytes(StandardCharsets.UTF_8));headers.put("Authorization",List.of("Basic "+encoded));}case "API_KEY_HEADER"->{require(1);conflict(headers,placement);headers.put(placement,List.of(value(0)));}case "API_KEY_QUERY"->{require(1);updated=query(target,placement,value(0));}default->throw fail("AUTH_INJECTION_FAILED");}return new AuthorizedRequest(updated,new GatewayTransport.Request(request.method(),request.url(),Collections.unmodifiableMap(headers),request.body()),this);}
    private static void validateHeaders(Map<String,List<String>> headers){Set<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);for(String name:headers.keySet())if(!names.add(name))throw fail("AUTH_CONFIGURATION_CONFLICT");}
    private static void conflict(Map<String,List<String>> headers,String name){if(headers.keySet().stream().anyMatch(k->k.equalsIgnoreCase(name)))throw fail("AUTH_CONFIGURATION_CONFLICT");}
    private static GatewayTargetPolicy.Authorized query(GatewayTargetPolicy.Authorized target,String name,String value){try{URI uri=target.uri();if(queryContains(uri.getRawQuery(),name))throw fail("AUTH_CONFIGURATION_CONFLICT");String pair=encode(name)+"="+encode(value),query=uri.getRawQuery()==null?pair:uri.getRawQuery()+"&"+pair;URI result=new URI(uri.getScheme(),uri.getRawAuthority(),uri.getRawPath(),query,null);return new GatewayTargetPolicy.Authorized(result,target.host(),target.addresses());}catch(GatewayTargetPolicy.GatewayFailure e){throw e;}catch(Exception e){throw fail("AUTH_INJECTION_FAILED");}}
    private static boolean queryContains(String query,String name){if(query==null)return false;try{return Arrays.stream(query.split("&",-1)).map(p->p.split("=",2)[0]).map(v->java.net.URLDecoder.decode(v,StandardCharsets.UTF_8)).anyMatch(name::equals);}catch(IllegalArgumentException e){throw fail("AUTH_CONFIGURATION_CONFLICT");}}
    private static String encode(String value){return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private void require(int count){if(values.size()!=count)throw fail("AUTH_INJECTION_FAILED");}
    private String value(int index){return new String(values.get(index));}
    @Override public void close(){values.forEach(v->Arrays.fill(v,'\0'));}
    private static String text(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException();byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
    private static GatewayTargetPolicy.GatewayFailure fail(String code){return new GatewayTargetPolicy.GatewayFailure(code);}
}
