package com.automationstudio.engine.karate.gateway;

import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;

final class GatewayWire {
    static GatewayTransport.Request readRequest(InputStream raw)throws IOException{var in=new DataInputStream(new Limited(raw,GatewayTransport.MAX_REQUEST+600_000));String method=text(in,16),url=text(in,8192);int count=in.readInt();if(count<0||count>128)throw new IOException();Map<String,List<String>> headers=new LinkedHashMap<>();for(int i=0;i<count;i++){String k=text(in,128);int values=in.readInt();if(values<0||values>32)throw new IOException();List<String> v=new ArrayList<>();for(int j=0;j<values;j++)v.add(text(in,4096));if(headers.put(k,List.copyOf(v))!=null)throw new IOException();}byte[] body=bytes(in,GatewayTransport.MAX_REQUEST);return new GatewayTransport.Request(method,url,Map.copyOf(headers),body);}
    static void writeResponse(OutputStream raw,GatewayTransport.Response response)throws IOException{var out=new DataOutputStream(raw);out.writeInt(response.status());out.writeInt(response.headers().size());for(var e:response.headers().entrySet()){text(out,e.getKey());out.writeInt(e.getValue().size());for(String v:e.getValue())text(out,v);}bytes(out,response.body());out.flush();}
    static void writeFailure(OutputStream raw,String code)throws IOException{var out=new DataOutputStream(raw);out.writeInt(599);out.writeInt(1);text(out,"X-AS-Error");out.writeInt(1);text(out,code);bytes(out,new byte[0]);out.flush();}
    private static String text(DataInputStream in,int max)throws IOException{return new String(bytes(in,max),StandardCharsets.UTF_8);}
    private static void text(DataOutputStream out,String value)throws IOException{bytes(out,value.getBytes(StandardCharsets.UTF_8));}
    private static byte[] bytes(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException();byte[] value=in.readNBytes(n);if(value.length!=n)throw new EOFException();return value;}
    private static void bytes(DataOutputStream out,byte[] b)throws IOException{out.writeInt(b.length);out.write(b);}
    private static final class Limited extends FilterInputStream{private long left;Limited(InputStream in,long max){super(in);left=max;}public int read()throws IOException{if(left==0)throw new IOException();int v=super.read();if(v>=0)left--;return v;}public int read(byte[] b,int o,int l)throws IOException{if(left==0)throw new IOException();int n=super.read(b,o,(int)Math.min(l,left));if(n>0)left-=n;return n;}}
}
