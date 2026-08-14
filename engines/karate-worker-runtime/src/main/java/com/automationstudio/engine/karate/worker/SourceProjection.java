package com.automationstudio.engine.karate.worker;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;

/** Worker-side independent validation and reconstruction of admitted logical source. */
final class SourceProjection implements AutoCloseable {
    private final Path root; private final long maxAggregate;private final boolean requirePosixSeal; private long aggregate; private final Set<String> paths=new HashSet<>();
    SourceProjection(Path root,long maxAggregate) throws IOException {this(root,maxAggregate,true);}
    SourceProjection(Path root,long maxAggregate,boolean requirePosixSeal) throws IOException { this.root=root.toAbsolutePath().normalize();this.maxAggregate=maxAggregate;this.requirePosixSeal=requirePosixSeal;Files.createDirectories(this.root); }
    void accept(String logicalPath,long declaredSize,String digest,byte[] content,int maxFile) throws IOException {
        if(!validPath(logicalPath)||declaredSize<0||declaredSize>maxFile||content.length!=declaredSize||!paths.add(logicalPath)) throw new WorkerFailure("INVALID_SOURCE");
        aggregate=Math.addExact(aggregate,declaredSize); if(aggregate>maxAggregate)throw new WorkerFailure("SOURCE_LIMIT_EXCEEDED");
        if(!hex(content).equals(digest))throw new WorkerFailure("DIGEST_MISMATCH");
        Path target=root.resolve(logicalPath).normalize(); if(!target.startsWith(root))throw new WorkerFailure("INVALID_SOURCE");
        Files.createDirectories(target.getParent()); Files.write(target,content,StandardOpenOption.CREATE_NEW); if(Files.isSymbolicLink(target))throw new WorkerFailure("INVALID_SOURCE");
    }
    void seal() throws IOException {
        if(Files.getFileAttributeView(root,java.nio.file.attribute.PosixFileAttributeView.class)==null){if(requirePosixSeal)throw new WorkerFailure("SOURCE_SEAL_UNSUPPORTED");return;}
        try(var walk=Files.walk(root)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.setPosixFilePermissions(p,Files.isDirectory(p)?Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE):Set.of(PosixFilePermission.OWNER_READ));}
        try(var walk=Files.walk(root)){for(Path p:walk.toList()){Set<PosixFilePermission> actual=Files.getPosixFilePermissions(p);Set<PosixFilePermission> expected=Files.isDirectory(p)?Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE):Set.of(PosixFilePermission.OWNER_READ);if(!actual.equals(expected))throw new WorkerFailure("SOURCE_SEAL_FAILED");}}
    }
    private static boolean validPath(String p){if(p==null||p.isBlank()||p.length()>1024||p.indexOf('\\')>=0||p.indexOf('\0')>=0||p.startsWith("/")||p.matches("^[A-Za-z]:.*"))return false;for(String part:p.split("/",-1))if(part.isBlank()||part.equals(".")||part.equals(".."))return false;return true;}
    private static String hex(byte[] bytes){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);return HexFormat.of().formatHex(hash);}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void close() throws IOException { if(!Files.exists(root))return;boolean mountedTmpfs="tmpfs".equalsIgnoreCase(Files.getFileStore(root).type());if(Files.getFileAttributeView(root,java.nio.file.attribute.PosixFileAttributeView.class)!=null)try(var walk=Files.walk(root)){for(Path p:walk.filter(Files::isDirectory).toList())Files.setPosixFilePermissions(p,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));}try(var walk=Files.walk(root)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())if(!mountedTmpfs||!p.equals(root))Files.deleteIfExists(p);}}
    static final class WorkerFailure extends IOException { WorkerFailure(String code){super(code);} }
}
