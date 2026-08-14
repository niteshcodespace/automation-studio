package com.automationstudio.engine.karate.worker;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Fixed worker bootstrap for bounded source projection and sequential Karate execution. */
public final class KarateWorkerMain {
    private static final Set<String> INPUT_TYPES=Set.of("HELLO","SOURCE","COMPLETE","EXECUTE","SHUTDOWN");
    private KarateWorkerMain(){}
    public static void main(String[] args){try{run(System.in,System.out,Path.of("/work/source"),true);}catch(Exception failure){System.err.println("WORKER_BOOTSTRAP_FAILED:"+category(failure));System.exit(70);}}
    static void run(InputStream in,OutputStream out,Path root)throws IOException{run(in,out,root,false);}
    private static void run(InputStream in,OutputStream out,Path root,boolean requirePosixSeal)throws IOException{
        UUID correlation=null; long aggregateInput=0; boolean complete=false,executed=false;
        try(SourceProjection projection=new SourceProjection(root,32L*1024*1024,requirePosixSeal)){
            while(true){
                WorkerProtocol.Message m=WorkerProtocol.read(in,WorkerProtocol.DEFAULT_MAX_FRAME); aggregateInput+=WorkerProtocol.encode(m).getBytes(java.nio.charset.StandardCharsets.UTF_8).length; if(aggregateInput>40L*1024*1024)throw new WorkerProtocol.ProtocolException("AGGREGATE_INPUT_EXCEEDED");
                if(!INPUT_TYPES.contains(m.type()))throw new WorkerProtocol.ProtocolException("UNKNOWN_MESSAGE");
                if(correlation==null){if(!m.type().equals("HELLO"))throw new WorkerProtocol.ProtocolException("UNEXPECTED_MESSAGE");correlation=m.correlationId();WorkerProtocol.write(out,WorkerProtocol.Message.of("READY",correlation),WorkerProtocol.DEFAULT_MAX_FRAME);continue;}
                if(!correlation.equals(m.correlationId()))throw new WorkerProtocol.ProtocolException("CORRELATION_MISMATCH");
                switch(m.type()){
                    case "SOURCE"->{if(complete||!m.fields().keySet().equals(Set.of("path","size","digest","content")))throw new WorkerProtocol.ProtocolException("MALFORMED_MESSAGE");byte[] bytes;try{bytes=Base64.getDecoder().decode(m.fields().get("content"));projection.accept(m.fields().get("path"),Long.parseLong(m.fields().get("size")),m.fields().get("digest"),bytes,768*1024);}catch(IllegalArgumentException e){throw new WorkerProtocol.ProtocolException("MALFORMED_MESSAGE");}WorkerProtocol.write(out,new WorkerProtocol.Message("ACCEPTED_SOURCE",correlation,Map.of("path",m.fields().get("path"))),WorkerProtocol.DEFAULT_MAX_FRAME);}
                    case "COMPLETE"->{if(complete)throw new WorkerProtocol.ProtocolException("UNEXPECTED_MESSAGE");complete=true;projection.seal();WorkerProtocol.write(out,WorkerProtocol.Message.of("COMPLETED_FOUNDATION_PROOF",correlation),WorkerProtocol.DEFAULT_MAX_FRAME);}
                    case "EXECUTE"->{if(!complete||executed||!m.fields().keySet().equals(Set.of("features","includeTags","excludeTags","variables","gateway")))throw new WorkerProtocol.ProtocolException("MALFORMED_MESSAGE");executed=true;PrintStream priorOut=System.out,priorErr=System.err;KarateSequentialExecutor.Result result;try(var sink=new PrintStream(OutputStream.nullOutputStream())){System.setOut(sink);System.setErr(sink);result=new KarateSequentialExecutor().execute(root,strings(m.fields().get("features"),256,1024),strings(m.fields().get("includeTags"),32,64),strings(m.fields().get("excludeTags"),32,64),map(m.fields().get("variables"),64,1024),m.fields().get("gateway"),correlation);}finally{System.setOut(priorOut);System.setErr(priorErr);}WorkerProtocol.write(out,new WorkerProtocol.Message("EXECUTION_RESULT",correlation,Map.of("outcome",result.outcome(),"features",String.valueOf(result.features()),"scenarios",String.valueOf(result.scenarios()),"passed",String.valueOf(result.passed()),"failed",String.valueOf(result.failed()),"diagnostic",result.diagnostic())),WorkerProtocol.DEFAULT_MAX_FRAME);}
                    case "SHUTDOWN"->{if(!executed)throw new WorkerProtocol.ProtocolException("UNEXPECTED_MESSAGE");WorkerProtocol.write(out,WorkerProtocol.Message.of("BYE",correlation),WorkerProtocol.DEFAULT_MAX_FRAME);return;}
                    default->throw new WorkerProtocol.ProtocolException("UNEXPECTED_MESSAGE");
                }
            }
        }
    }
    private static List<String> strings(String encoded,int maximum,int maxLength)throws IOException{try{var in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));int count=in.readInt();if(count<0||count>maximum)throw new IOException();List<String> values=new ArrayList<>();for(int i=0;i<count;i++){int n=in.readInt();if(n<0||n>maxLength)throw new IOException();byte[] b=in.readNBytes(n);if(b.length!=n)throw new IOException();values.add(new String(b,java.nio.charset.StandardCharsets.UTF_8));}if(in.read()!=-1)throw new IOException();return List.copyOf(values);}catch(IllegalArgumentException e){throw new IOException();}}
    private static Map<String,String> map(String encoded,int maximum,int maxLength)throws IOException{List<String> pairs=strings(encoded,maximum*2,maxLength);if((pairs.size()&1)!=0)throw new IOException();Map<String,String> values=new TreeMap<>();for(int i=0;i<pairs.size();i+=2)if(values.put(pairs.get(i),pairs.get(i+1))!=null)throw new IOException();return Map.copyOf(values);}
    private static String category(Exception failure){if(failure instanceof SourceProjection.WorkerFailure)return failure.getMessage();if(failure instanceof WorkerProtocol.ProtocolException protocol)return protocol.code();return "WORKER_RUNTIME_ERROR";}
}
