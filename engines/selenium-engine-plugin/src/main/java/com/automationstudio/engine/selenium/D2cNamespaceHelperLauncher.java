package com.automationstudio.engine.selenium;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.attribute.PosixFilePermission;

/** Closed launcher/protocol for the reviewed host namespace helper. */
final class D2cNamespaceHelperLauncher implements D2cRouteAuthority.Helper {
    static final String PATH="/opt/automation-studio/bin/as-netns-helper";
    interface Transport { Reply invoke(List<String> argv,List<String> environment,String request,ContainmentDeadline deadline); }
    record Reply(DockerControlPlane.Dispatch dispatch,boolean complete,String response,boolean processCleaned){
        Reply{Objects.requireNonNull(dispatch);if(!complete)response=null;}
    }
    private final String expectedDigest;private final Transport transport;
    D2cNamespaceHelperLauncher(String expectedDigest,Transport transport){
        if(expectedDigest==null||!expectedDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid helper digest");
        this.expectedDigest=expectedDigest;this.transport=Objects.requireNonNull(transport);}
    static D2cNamespaceHelperLauncher production(String expectedDigest){return new D2cNamespaceHelperLauncher(expectedDigest,new ProcessTransport(expectedDigest));}
    @Override public D2cRouteAuthority.Result replaceDefaultRoute(D2cRouteAuthority.Binding b,ContainmentDeadline deadline){
        verify(b);Reply reply=transport.invoke(List.of(PATH,"--digest",expectedDigest),environment(),request("REPLACE_WORKER_DEFAULT_ROUTE",b),deadline);
        boolean canonical=reply.complete()&&reply.processCleaned()&&"ROUTE_CANONICAL".equals(reply.response());
        return new D2cRouteAuthority.Result(reply.dispatch(),canonical);}
    @Override public boolean inspectCanonicalRoute(D2cRouteAuthority.Binding b,ContainmentDeadline deadline){
        verify(b);Reply reply=transport.invoke(List.of(PATH,"--digest",expectedDigest),environment(),request("INSPECT_WORKER_NETWORK_STATE",b),deadline);
        return reply.dispatch()==DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED&&reply.complete()&&reply.processCleaned()
                &&"ROUTE_CANONICAL".equals(reply.response());}
    private void verify(D2cRouteAuthority.Binding b){if(!expectedDigest.equals(b.helperDigest()))throw new SecurityException("Helper digest mismatch");}
    private static List<String> environment(){return List.of("PATH=/usr/sbin:/usr/bin:/sbin:/bin","LANG=C");}
    private static String request(String operation,D2cRouteAuthority.Binding b){return String.join(";",operation,b.workerId(),Long.toString(b.pid()),
            Long.toString(b.processStartIdentity()),Long.toString(b.namespaceDevice()),Long.toString(b.namespaceInode()),b.networkId(),
            b.workerIpv4(),b.gatewayIpv4(),b.interfaceName(),Long.toString(b.generation()),b.daemon().endpoint(),b.daemon().context(),b.daemon().engineId());}
    private static final class ProcessTransport implements Transport{
        private static final int MAX=4096;private static final String EXECUTION_USER="automation-studio-netns";private final String digest;
        ProcessTransport(String digest){this.digest=digest;}
        public Reply invoke(List<String> argv,List<String> environment,String request,ContainmentDeadline deadline){
            ExecutableIdentity executable=executableIdentity();if(executable==null)return new Reply(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,false,null,true);
            Process process=null;Thread reader=null;var bytes=new ByteArrayOutputStream();var overflow=new AtomicBoolean();var readFailure=new AtomicReference<Throwable>();try(executable){
                if(argv.size()!=3||!PATH.equals(argv.getFirst())||!"--digest".equals(argv.get(1))||!digest.equals(argv.get(2)))return new Reply(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,false,null,true);
                ProcessBuilder builder=new ProcessBuilder(List.of(executable.descriptor().toString(),argv.get(1),argv.get(2)));builder.environment().clear();for(String pair:environment){int split=pair.indexOf('=');builder.environment().put(pair.substring(0,split),pair.substring(split+1));}
                process=builder.redirectErrorStream(true).start();String user=process.info().user().orElse("");if(!executable.stable()||!EXECUTION_USER.equals(user)){clean(process,null);return new Reply(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,false,null,!process.isAlive());}
                Process owned=process;reader=Thread.ofPlatform().daemon().name("as-netns-helper-reader-"+process.pid()).start(()->{
                    try(var in=owned.getInputStream()){byte[] buffer=new byte[512];int count;while((count=in.read(buffer))>=0){synchronized(bytes){if(bytes.size()+count>MAX){overflow.set(true);return;}bytes.write(buffer,0,count);}}}catch(Exception failure){readFailure.set(failure);}});
                process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
                if(!process.waitFor(deadline.remainingNanos(),TimeUnit.NANOSECONDS)){clean(process,reader);return new Reply(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,false,null,!process.isAlive()&&!reader.isAlive());}
                reader.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos())));boolean cleaned=!process.isAlive()&&!reader.isAlive();String response;
                synchronized(bytes){response=bytes.toString(StandardCharsets.UTF_8).strip();}boolean complete=process.exitValue()==0&&cleaned&&!overflow.get()&&readFailure.get()==null;return new Reply(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,complete,response,cleaned);
            }catch(InterruptedException interrupted){if(process!=null)clean(process,reader);Thread.currentThread().interrupt();return new Reply(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,false,null,process==null||!process.isAlive());}
            catch(Exception failure){if(process!=null)clean(process,reader);return new Reply(process==null?DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED:DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,false,null,process==null||!process.isAlive());}}
        private ExecutableIdentity executableIdentity(){java.nio.channels.SeekableByteChannel channel=null;try{Path path=Path.of(PATH);var before=descriptorLinks();channel=Files.newByteChannel(path,java.util.Set.of(java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS));Path descriptor=descriptorLinks().entrySet().stream().filter(value->!Objects.equals(before.get(value.getKey()),value.getValue())).map(java.util.Map.Entry::getKey).filter(value->{try{return Files.isSameFile(path,value);}catch(Exception ignored){return false;}}).findFirst().orElseThrow();
            var bytes=new ByteArrayOutputStream();var buffer=java.nio.ByteBuffer.allocate(8192);while(channel.read(buffer)>=0){buffer.flip();bytes.write(buffer.array(),0,buffer.remaining());buffer.clear();if(bytes.size()>16*1024*1024)throw new IllegalArgumentException();}byte[] content=bytes.toByteArray();if(content.length==0)return null;
            var attrs=Files.readAttributes(descriptor,java.nio.file.attribute.BasicFileAttributes.class);var permissions=Files.getPosixFilePermissions(descriptor);String owner=Files.getOwner(descriptor).getName();
            if(!owner.equals("root")||permissions.contains(PosixFilePermission.GROUP_WRITE)||permissions.contains(PosixFilePermission.OTHERS_WRITE))return null;
            String observed="sha256:"+java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));if(!digest.equals(observed))throw new SecurityException();return new ExecutableIdentity(channel,descriptor,attrs.fileKey(),attrs.size(),attrs.lastModifiedTime().toMillis(),observed);}catch(Exception failure){if(channel!=null)try{channel.close();}catch(Exception ignored){}return null;}}
        private static java.util.Map<Path,Path> descriptorLinks()throws java.io.IOException{try(var values=Files.list(Path.of("/proc/self/fd"))){var result=new java.util.HashMap<Path,Path>();values.forEach(value->{try{result.put(value,Files.readSymbolicLink(value));}catch(Exception ignored){}});return Map.copyOf(result);}}
        private record ExecutableIdentity(java.nio.channels.SeekableByteChannel channel,Path descriptor,Object fileKey,long size,long modified,String digest)implements AutoCloseable{
            boolean stable(){try{var attrs=Files.readAttributes(descriptor,java.nio.file.attribute.BasicFileAttributes.class);return Objects.equals(fileKey,attrs.fileKey())&&size==attrs.size()&&modified==attrs.lastModifiedTime().toMillis();}catch(Exception failure){return false;}}
            public void close()throws java.io.IOException{channel.close();}}
        private static void clean(Process p,Thread reader){p.destroyForcibly();try{p.waitFor(1,TimeUnit.SECONDS);if(reader!=null)reader.join(1000);}catch(InterruptedException x){Thread.currentThread().interrupt();}}
    }
}
