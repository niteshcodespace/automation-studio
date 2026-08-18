package com.automationstudio.engine.karate.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;import java.nio.ByteBuffer;import java.nio.charset.StandardCharsets;import java.util.*;import org.junit.jupiter.api.Test;

class WorkerProtocolTest {
 @Test void roundTripsDeterministicFrame()throws Exception{UUID id=UUID.randomUUID();var m=new WorkerProtocol.Message("HELLO",id,Map.of("z","2","a","1"));var out=new ByteArrayOutputStream();WorkerProtocol.write(out,m,1024);assertEquals(m,WorkerProtocol.read(new ByteArrayInputStream(out.toByteArray()),1024));assertTrue(new String(out.toByteArray(),4,out.size()-4,StandardCharsets.UTF_8).indexOf("\"a\"")<new String(out.toByteArray(),4,out.size()-4,StandardCharsets.UTF_8).indexOf("\"z\""));}
 @Test void rejectsMalformedLengthOversizeAndEof(){assertEquals("INVALID_FRAME_LENGTH",failure(ByteBuffer.allocate(4).putInt(-1).array(),32));assertEquals("INVALID_FRAME_LENGTH",failure(ByteBuffer.allocate(4).putInt(33).array(),32));assertEquals("PREMATURE_EOF",failure(ByteBuffer.allocate(5).putInt(2).put((byte)'{').array(),32));}
 @Test void rejectsProtocolMismatchUnknownShapeAndMalformedJson(){UUID id=UUID.randomUUID();assertEquals("PROTOCOL_MISMATCH",decode("{\"version\":2,\"type\":\"HELLO\",\"correlationId\":\""+id+"\",\"fields\":{}}"));assertEquals("PROTOCOL_MISMATCH",decode("{\"version\":1,\"type\":\"HELLO\",\"correlationId\":\""+id+"\",\"fields\":{},\"extra\":\"x\"}"));assertEquals("MALFORMED_MESSAGE",decode("not-json"));}
 private static String failure(byte[] b,int max){try{WorkerProtocol.read(new ByteArrayInputStream(b),max);return "";}catch(WorkerProtocol.ProtocolException e){return e.code();}catch(Exception e){return e.getClass().getSimpleName();}}
 private static String decode(String s){try{WorkerProtocol.decode(s);return "";}catch(WorkerProtocol.ProtocolException e){return e.code();}}
}
