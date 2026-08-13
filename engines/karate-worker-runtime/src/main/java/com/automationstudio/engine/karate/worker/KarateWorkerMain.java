package com.automationstudio.engine.karate.worker;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** AS-030C2 bootstrap only. It validates source framing and never loads or executes Karate. */
public final class KarateWorkerMain {
    private static final Set<String> INPUT_TYPES=Set.of("HELLO","SOURCE","COMPLETE","SHUTDOWN");
    private KarateWorkerMain(){}
    public static void main(String[] args){try{run(System.in,System.out,Path.of("/work/source"));}catch(Exception ignored){System.exit(70);}}
    static void run(InputStream in,OutputStream out,Path root)throws IOException{
        UUID correlation=null; long aggregateInput=0; boolean complete=false;
        try(SourceProjection projection=new SourceProjection(root,32L*1024*1024)){
            while(true){
                WorkerProtocol.Message m=WorkerProtocol.read(in,WorkerProtocol.DEFAULT_MAX_FRAME); aggregateInput+=WorkerProtocol.encode(m).getBytes(java.nio.charset.StandardCharsets.UTF_8).length; if(aggregateInput>40L*1024*1024)throw new WorkerProtocol.ProtocolException("AGGREGATE_INPUT_EXCEEDED");
                if(!INPUT_TYPES.contains(m.type()))throw new WorkerProtocol.ProtocolException("UNKNOWN_MESSAGE");
                if(correlation==null){if(!m.type().equals("HELLO"))throw new WorkerProtocol.ProtocolException("UNEXPECTED_MESSAGE");correlation=m.correlationId();WorkerProtocol.write(out,WorkerProtocol.Message.of("READY",correlation),WorkerProtocol.DEFAULT_MAX_FRAME);continue;}
                if(!correlation.equals(m.correlationId()))throw new WorkerProtocol.ProtocolException("CORRELATION_MISMATCH");
                switch(m.type()){
                    case "SOURCE"->{if(complete||!m.fields().keySet().equals(Set.of("path","size","digest","content")))throw new WorkerProtocol.ProtocolException("MALFORMED_MESSAGE");byte[] bytes;try{bytes=Base64.getDecoder().decode(m.fields().get("content"));projection.accept(m.fields().get("path"),Long.parseLong(m.fields().get("size")),m.fields().get("digest"),bytes,768*1024);}catch(IllegalArgumentException e){throw new WorkerProtocol.ProtocolException("MALFORMED_MESSAGE");}WorkerProtocol.write(out,new WorkerProtocol.Message("ACCEPTED_SOURCE",correlation,Map.of("path",m.fields().get("path"))),WorkerProtocol.DEFAULT_MAX_FRAME);}
                    case "COMPLETE"->{if(complete)throw new WorkerProtocol.ProtocolException("UNEXPECTED_MESSAGE");complete=true;WorkerProtocol.write(out,WorkerProtocol.Message.of("COMPLETED_FOUNDATION_PROOF",correlation),WorkerProtocol.DEFAULT_MAX_FRAME);}
                    case "SHUTDOWN"->{if(!complete)throw new WorkerProtocol.ProtocolException("UNEXPECTED_MESSAGE");WorkerProtocol.write(out,WorkerProtocol.Message.of("BYE",correlation),WorkerProtocol.DEFAULT_MAX_FRAME);return;}
                    default->throw new WorkerProtocol.ProtocolException("UNEXPECTED_MESSAGE");
                }
            }
        }
    }
}
