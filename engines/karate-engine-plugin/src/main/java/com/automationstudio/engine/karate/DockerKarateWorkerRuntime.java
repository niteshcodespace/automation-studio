package com.automationstudio.engine.karate;

import com.automationstudio.engine.karate.worker.WorkerProtocol;
import com.automationstudio.engine.sdk.PreparedSourceAccess;
import java.io.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

final class DockerKarateWorkerRuntime implements KarateWorkerRuntime {
    private final String image; private final WorkerLimits limits; private final CommandRunner commands;
    DockerKarateWorkerRuntime(){this(requiredImage(),WorkerLimits.defaults(),new ProcessCommandRunner());}
    DockerKarateWorkerRuntime(String image,WorkerLimits limits,CommandRunner commands){this.image=Objects.requireNonNull(image);this.limits=Objects.requireNonNull(limits);this.commands=Objects.requireNonNull(commands);}
    @Override public void prove(UUID id,PreparedSourceAccess source,List<String> paths){String name="as-karate-"+id.toString().replace("-","");boolean created=false;Process process=null;try{
        CommandResult creation=commands.run(DockerWorkerCommand.create(name,image,limits),Duration.ofSeconds(30),limits.maxStderrBytes());if(creation.exit()!=0)throw failure("WORKER_START_FAILED");created=true;
        process=commands.start(DockerWorkerCommand.attach(name));Process active=process;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){Future<Void> exchange=executor.submit(()->{exchange(id,source,paths,active);return null;});try{exchange.get(limits.wallTime().toMillis(),TimeUnit.MILLISECONDS);}catch(TimeoutException e){exchange.cancel(true);throw failure("WORKER_TIMEOUT");}catch(ExecutionException e){throw failure("WORKER_PROTOCOL_FAILED");}}
        if(!process.waitFor(limits.stopGrace().toMillis(),TimeUnit.MILLISECONDS)||process.exitValue()!=0)throw failure("WORKER_EXIT_FAILED");
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw failure("WORKER_INTERRUPTED");}catch(IOException e){throw failure("WORKER_RUNTIME_FAILED");}finally{if(process!=null){try{process.getOutputStream().close();}catch(IOException ignored){}if(process.isAlive())process.destroyForcibly();}if(created)cleanup(name);}}
    private void exchange(UUID id,PreparedSourceAccess source,List<String> paths,Process process)throws IOException{
        OutputStream out=process.getOutputStream();InputStream in=new BoundedInputStream(process.getInputStream(),limits.maxStdoutBytes());
        send(out,WorkerProtocol.Message.of("HELLO",id));expect(in,"READY",id);long aggregate=0;
        for(String path:paths){byte[] bytes=readBounded(source.open(path),limits.maxFrameBytes());aggregate+=bytes.length;if(aggregate>32L*1024*1024)throw new IOException("Aggregate source exceeded");String digest=HexFormat.of().formatHex(hash(bytes));send(out,new WorkerProtocol.Message("SOURCE",id,Map.of("path",path,"size",String.valueOf(bytes.length),"digest",digest,"content",Base64.getEncoder().encodeToString(bytes))));expect(in,"ACCEPTED_SOURCE",id);}
        send(out,WorkerProtocol.Message.of("COMPLETE",id));expect(in,"COMPLETED_FOUNDATION_PROOF",id);send(out,WorkerProtocol.Message.of("SHUTDOWN",id));expect(in,"BYE",id);out.close();
    }
    private void send(OutputStream out,WorkerProtocol.Message m)throws IOException{WorkerProtocol.write(out,m,limits.maxFrameBytes());}
    private WorkerProtocol.Message expect(InputStream in,String type,UUID id)throws IOException{WorkerProtocol.Message m=WorkerProtocol.read(in,limits.maxFrameBytes());if(!m.type().equals(type)||!m.correlationId().equals(id))throw new IOException("Unexpected worker response");return m;}
    private static byte[] readBounded(InputStream input,int max)throws IOException{try(input){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int total=0,n;while((n=input.read(buffer))>=0){total+=n;if(total>max*3/4)throw new IOException("Source frame exceeded");out.write(buffer,0,n);}return out.toByteArray();}}
    private static byte[] hash(byte[] b){try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception e){throw new IllegalStateException(e);}}
    private void cleanup(String name){int grace=(int)Math.max(0,limits.stopGrace().toSeconds());commands.bestEffort(DockerWorkerCommand.stop(name,grace),grace,limits.maxStderrBytes());commands.bestEffort(DockerWorkerCommand.kill(name),1,limits.maxStderrBytes());commands.bestEffort(DockerWorkerCommand.remove(name),5,limits.maxStderrBytes());CommandResult remaining=commands.bestEffort(DockerWorkerCommand.inspect(name),5,limits.maxStderrBytes());if(remaining.exit()==0)throw failure("WORKER_CLEANUP_FAILED");}
    private static KarateEngineException failure(String code){return new KarateEngineException(code,"Karate isolated worker foundation failed");}
    interface CommandRunner {CommandResult run(List<String> c,Duration timeout,long stderr)throws IOException,InterruptedException;Process start(List<String> c)throws IOException;default CommandResult bestEffort(List<String> c,int seconds,long stderr){try{return run(c,Duration.ofSeconds(Math.max(1,seconds)),stderr);}catch(Exception e){return new CommandResult(-1,"");}}}
    record CommandResult(int exit,String output){}
    private static String requiredImage(){String value=System.getProperty("automation.karate.worker.image");if(value==null||value.isBlank())throw failure("WORKER_IMAGE_NOT_CONFIGURED");return value;}
    static final class ProcessCommandRunner implements CommandRunner {public CommandResult run(List<String> c,Duration timeout,long stderr)throws IOException,InterruptedException{Process p=new ProcessBuilder(c).redirectErrorStream(true).start();byte[] output;try(var in=new BoundedInputStream(p.getInputStream(),stderr)){output=in.readAllBytes();}if(!p.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){p.destroyForcibly();return new CommandResult(-1,"");}return new CommandResult(p.exitValue(),new String(output,java.nio.charset.StandardCharsets.UTF_8));}public Process start(List<String> c)throws IOException{return new ProcessBuilder(c).redirectError(ProcessBuilder.Redirect.DISCARD).start();}}
}
