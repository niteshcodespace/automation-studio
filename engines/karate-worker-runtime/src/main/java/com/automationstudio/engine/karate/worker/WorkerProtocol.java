package com.automationstudio.engine.karate.worker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned length-prefixed deterministic JSON protocol shared by the provider and worker. */
public final class WorkerProtocol {
    public static final int VERSION = 1;
    public static final int DEFAULT_MAX_FRAME = 1_048_576;
    private WorkerProtocol() {}

    public record Message(String type, UUID correlationId, Map<String, String> fields) {
        public Message {
            if (type == null || !type.matches("[A-Z_]{2,40}")) throw new IllegalArgumentException("Invalid message type");
            Objects.requireNonNull(correlationId, "Correlation ID");
            fields = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(fields, "fields")));
            if (fields.entrySet().stream().anyMatch(e -> e.getKey() == null || !e.getKey().matches("[a-zA-Z][a-zA-Z0-9]*") || e.getValue() == null))
                throw new IllegalArgumentException("Invalid message field");
        }
        public static Message of(String type, UUID id) { return new Message(type, id, Map.of()); }
    }

    public static void write(OutputStream output, Message message, int maxFrame) throws IOException {
        byte[] bytes = encode(message).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maxFrame) throw new ProtocolException("FRAME_TOO_LARGE");
        new DataOutputStream(output).writeInt(bytes.length);
        output.write(bytes); output.flush();
    }

    public static Message read(InputStream input, int maxFrame) throws IOException {
        DataInputStream data = new DataInputStream(input);
        final int length;
        try { length = data.readInt(); } catch (EOFException e) { throw new ProtocolException("PREMATURE_EOF"); }
        if (length <= 0 || length > maxFrame) throw new ProtocolException("INVALID_FRAME_LENGTH");
        byte[] bytes = new byte[length];
        try { data.readFully(bytes); } catch (EOFException e) { throw new ProtocolException("PREMATURE_EOF"); }
        return decode(new String(bytes, StandardCharsets.UTF_8));
    }

    static String encode(Message m) {
        StringBuilder b = new StringBuilder("{\"version\":").append(VERSION).append(",\"type\":\"").append(escape(m.type())).append("\",\"correlationId\":\"").append(m.correlationId()).append("\",\"fields\":{");
        boolean first = true;
        for (var e : m.fields().entrySet()) { if (!first) b.append(','); first=false; b.append('\"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('\"'); }
        return b.append("}}").toString();
    }

    static Message decode(String json) throws ProtocolException {
        try {
            Parser p = new Parser(json); Map<String,Object> root=p.object(); p.end();
            if (!root.keySet().equals(Set.of("version","type","correlationId","fields")) || !Integer.valueOf(VERSION).equals(root.get("version"))) throw new ProtocolException("PROTOCOL_MISMATCH");
            if (!(root.get("type") instanceof String type) || !(root.get("correlationId") instanceof String correlation) || !(root.get("fields") instanceof Map<?,?> raw)) throw new ProtocolException("MALFORMED_MESSAGE");
            Map<String,String> fields=new TreeMap<>(); for(var e:raw.entrySet()) { if(!(e.getKey() instanceof String k)||!(e.getValue() instanceof String v)) throw new ProtocolException("MALFORMED_MESSAGE"); fields.put(k,v); }
            return new Message(type, UUID.fromString(correlation), fields);
        } catch (ProtocolException e) { throw e; } catch (RuntimeException e) { throw new ProtocolException("MALFORMED_MESSAGE"); }
    }

    private static String escape(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r"); }
    public static final class ProtocolException extends IOException { private final String code; public ProtocolException(String code){super(code);this.code=code;} public String code(){return code;} }

    private static final class Parser {
        private final String s; private int i; Parser(String s){this.s=s;}
        Map<String,Object> object(){ ws(); expect('{'); Map<String,Object> m=new LinkedHashMap<>(); ws(); if(take('}'))return m; do {String k=string(); ws(); expect(':'); Object v=value(); if(m.put(k,v)!=null)throw new IllegalArgumentException(); ws();}while(take(',')); expect('}'); return m; }
        Object value(){ws(); if(peek()=='\"')return string(); if(peek()=='{')return object(); int start=i; while(i<s.length()&&Character.isDigit(s.charAt(i)))i++; if(start==i)throw new IllegalArgumentException(); return Integer.valueOf(s.substring(start,i));}
        String string(){ws();expect('\"');StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='\"')return b.toString();if(c=='\\'){if(i>=s.length())throw new IllegalArgumentException();char e=s.charAt(i++);b.append(switch(e){case '\"'->'\"';case '\\'->'\\';case 'n'->'\n';case 'r'->'\r';default->throw new IllegalArgumentException();});}else if(c<0x20)throw new IllegalArgumentException();else b.append(c);}throw new IllegalArgumentException();}
        void end(){ws();if(i!=s.length())throw new IllegalArgumentException();} void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;} char peek(){return i<s.length()?s.charAt(i):0;} boolean take(char c){ws();if(peek()==c){i++;return true;}return false;} void expect(char c){if(!take(c))throw new IllegalArgumentException();}
    }
}
