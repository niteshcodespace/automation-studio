package com.automationstudio.engine.karate;
import java.io.*;
final class BoundedInputStream extends FilterInputStream {private long remaining;BoundedInputStream(InputStream in,long limit){super(in);remaining=limit;}@Override public int read()throws IOException{if(remaining==0)throw new IOException("Output limit exceeded");int v=super.read();if(v>=0)remaining--;return v;}@Override public int read(byte[] b,int o,int l)throws IOException{if(remaining==0)throw new IOException("Output limit exceeded");int n=super.read(b,o,(int)Math.min(l,remaining));if(n>0)remaining-=n;return n;}}
