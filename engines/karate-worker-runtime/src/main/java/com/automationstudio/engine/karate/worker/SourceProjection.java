package com.automationstudio.engine.karate.worker;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Worker-side independent validation and reconstruction of admitted logical source. */
final class SourceProjection implements AutoCloseable {
    private final Path root; private final long maxAggregate; private long aggregate; private final Set<String> paths=new HashSet<>();
    SourceProjection(Path root,long maxAggregate) throws IOException { this.root=root.toAbsolutePath().normalize();this.maxAggregate=maxAggregate;Files.createDirectories(this.root); }
    void accept(String logicalPath,long declaredSize,String digest,byte[] content,int maxFile) throws IOException {
        if(!validPath(logicalPath)||declaredSize<0||declaredSize>maxFile||content.length!=declaredSize||!paths.add(logicalPath)) throw new WorkerFailure("INVALID_SOURCE");
        aggregate=Math.addExact(aggregate,declaredSize); if(aggregate>maxAggregate)throw new WorkerFailure("SOURCE_LIMIT_EXCEEDED");
        if(!hex(content).equals(digest))throw new WorkerFailure("DIGEST_MISMATCH");
        Path target=root.resolve(logicalPath).normalize(); if(!target.startsWith(root))throw new WorkerFailure("INVALID_SOURCE");
        Files.createDirectories(target.getParent()); Files.write(target,content,StandardOpenOption.CREATE_NEW); if(Files.isSymbolicLink(target))throw new WorkerFailure("INVALID_SOURCE");
    }
    private static boolean validPath(String p){if(p==null||p.isBlank()||p.length()>1024||p.indexOf('\\')>=0||p.indexOf('\0')>=0||p.startsWith("/")||p.matches("^[A-Za-z]:.*"))return false;for(String part:p.split("/",-1))if(part.isBlank()||part.equals(".")||part.equals(".."))return false;return true;}
    private static String hex(byte[] bytes){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);return HexFormat.of().formatHex(hash);}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void close() throws IOException { if(!Files.exists(root))return;try(var walk=Files.walk(root)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    static final class WorkerFailure extends IOException { WorkerFailure(String code){super(code);} }
}
