package com.automationstudio.engine.selenium;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

interface SeleniumContainmentCommandRunner {
    CommandResult run(List<String> command, Duration timeout, long maxOutputBytes);
    default CommandResult run(List<String> command, ContainmentDeadline deadline, long maxOutputBytes) {
        return run(command, deadline.remaining(), maxOutputBytes);
    }
    AttachHandle attach(List<String> command);
    record CommandResult(int exitCode, String output) {}
    interface AttachHandle extends AutoCloseable {
        void awaitReady(UUID executionId, Duration timeout);
        void shutdown(UUID executionId, ContainmentDeadline deadline);
        void close(ContainmentDeadline deadline);
        boolean closed();
        @Override void close();
    }

    final class ProcessRunner implements SeleniumContainmentCommandRunner {
        private static final Duration TERMINATION_RESERVE=Duration.ofMillis(100);
        private final Consumer<ProcessHandle> processObserver; private final Consumer<Thread> readerObserver; private final Consumer<String> phaseObserver;
        ProcessRunner(){this(ignored->{},ignored->{},ignored->{});} ProcessRunner(Consumer<ProcessHandle> processObserver,Consumer<Thread> readerObserver){this(processObserver,readerObserver,ignored->{});} ProcessRunner(Consumer<ProcessHandle> processObserver,Consumer<Thread> readerObserver,Consumer<String> phaseObserver){this.processObserver=processObserver;this.readerObserver=readerObserver;this.phaseObserver=phaseObserver;}
        @Override public CommandResult run(List<String> command, Duration timeout, long maximum) {
            return run(command, ContainmentDeadline.after(timeout), maximum);
        }
        @Override public CommandResult run(List<String> command, ContainmentDeadline deadline, long maximum) {
            phaseObserver.accept("method-start");
            Process process = null;
            Thread reader = null;
            CompletableFuture<byte[]> output = new CompletableFuture<>();
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
                processObserver.accept(process.toHandle());
                Process active = process;
                reader = Thread.ofPlatform().daemon(true).name("selenium-docker-output").start(() -> {
                    try { output.complete(readBounded(active.getInputStream(), maximum)); }
                    catch (IOException failure) { output.completeExceptionally(failure); }
                });
                readerObserver.accept(reader);
                if (!process.waitFor(deadline.remainingNanos(TERMINATION_RESERVE), TimeUnit.NANOSECONDS)) return terminate(process, reader, deadline);
                byte[] bytes = output.get(deadline.remainingNanos(TERMINATION_RESERVE), TimeUnit.NANOSECONDS);
                return new CommandResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8).trim());
            } catch (Exception failure) {
                if (process != null) terminate(process, reader, deadline);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return new CommandResult(-1, "");
            }
        }
        @Override public AttachHandle attach(List<String> command) {
            try { return new ProcessAttach(new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()); }
            catch (IOException failure) { throw new SeleniumEngineException("WORKER_ATTACH_FAILED", "Selenium containment failed"); }
        }
        private static byte[] readBounded(InputStream input, long maximum) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int read; long total = 0;
            while ((read = input.read(buffer)) >= 0) { total += read; if (total > maximum) throw new IOException(); output.write(buffer, 0, read); }
            return output.toByteArray();
        }
        private static CommandResult terminate(Process process, Thread reader, ContainmentDeadline deadline) {
            process.destroy();
            try { if (!deadline.expired()) process.waitFor(deadline.remainingNanos(), TimeUnit.NANOSECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            if (process.isAlive()) process.destroyForcibly();
            try { if(process.isAlive()&&!deadline.expired())process.waitFor(deadline.remainingNanos(),TimeUnit.NANOSECONDS); } catch(InterruptedException failure){Thread.currentThread().interrupt();}
            Thread closer=Thread.ofPlatform().daemon(true).name("selenium-docker-stream-close").start(()->{close(process.getOutputStream());close(process.getInputStream());close(process.getErrorStream());});
            if(!deadline.expired())try{closer.join(Duration.ofNanos(deadline.remainingNanos()));}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            if (reader != null && !deadline.expired()) try { reader.join(Duration.ofNanos(deadline.remainingNanos())); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            return new CommandResult(-1, "");
        }
        private static void close(Closeable stream) { try { stream.close(); } catch (IOException ignored) {} }
    }

    final class ProcessAttach implements AttachHandle {
        private static final int MAX_FRAME = 256;
        // Graceful IPC gets only an early slice of the caller's original cleanup window.
        private static final Duration GRACEFUL_IPC_BUDGET=Duration.ofMillis(100);
        private static final Duration TERMINATION_RESERVE=Duration.ofMillis(100);
        private static final Duration CLOSE_JOIN_RESERVE=Duration.ofMillis(25);
        interface LifecycleObserver {
            LifecycleObserver NONE = new LifecycleObserver() {};
            default void writer(Thread thread) {}
            default void reader(Thread thread) {}
            default void closer(Thread thread) {}
            default void streamsClosed() {}
            default void protocolCompleted() {}
            default void phase(String phase) {}
        }
        private final Process process; private final AtomicBoolean terminationStarted = new AtomicBoolean();
        private final AtomicBoolean streamsClosed = new AtomicBoolean();
        private volatile Thread writerTask,readerTask,closerTask;
        private final LifecycleObserver observer;
        ProcessAttach(Process process) { this(process, LifecycleObserver.NONE); }
        ProcessAttach(Process process, LifecycleObserver observer) { this.process = process; this.observer = observer; }
        @Override public void awaitReady(UUID id, Duration timeout) {
            exchange(id, timeout, "HELLO", "READY", false);
        }
        @Override public void shutdown(UUID id, ContainmentDeadline deadline) {
            exchange(id, deadline, "SHUTDOWN", "BYE", true);
        }
        private void exchange(UUID id, Duration timeout, String sent, String expected, boolean closeAfter) {
            exchange(id,ContainmentDeadline.after(timeout),sent,expected,closeAfter);
        }
        private void exchange(UUID id, ContainmentDeadline deadline, String sent, String expected, boolean closeAfter) {
            observer.phase("method-start");
            Thread writer = null;
            Thread reader = null;
            ContainmentDeadline gracefulDeadline=deadline.cappedAfter(GRACEFUL_IPC_BUDGET);
            try {
                CompletableFuture<Void> written = new CompletableFuture<>();
                observer.phase("writer-submit");
                writer = Thread.ofPlatform().daemon(true).name("selenium-worker-ipc-write").start(() -> {
                    observer.phase("writer-start");
                    try { write(new DataOutputStream(process.getOutputStream()), sent, id); written.complete(null); }
                    catch (IOException failure) { written.completeExceptionally(failure); }
                    finally { observer.phase("writer-complete"); }
                });
                writerTask=writer;
                observer.writer(writer);
                written.get(gracefulDeadline.remainingNanos(), TimeUnit.NANOSECONDS);
                CompletableFuture<Frame> response = new CompletableFuture<>();
                reader = Thread.ofPlatform().daemon(true).name("selenium-worker-ipc").start(() -> {
                    observer.phase("reader-start");
                    try { response.complete(read(new DataInputStream(process.getInputStream()))); }
                    catch (IOException failure) { response.completeExceptionally(failure); }
                    finally { observer.phase("reader-complete"); }
                });
                readerTask=reader;
                observer.reader(reader);
                require(response.get(gracefulDeadline.remainingNanos(), TimeUnit.NANOSECONDS), expected, id);
                observer.protocolCompleted();
                observer.phase("protocol-complete");
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                terminate(deadline,writer,reader);
                observer.phase("method-return");
                throw new SeleniumEngineException("WORKER_PROTOCOL_FAILED", "Selenium containment failed");
            }
            if (closeAfter) close(deadline,writer,reader);
            observer.phase("method-return");
        }
        private static void write(DataOutputStream out, String type, UUID id) throws IOException { byte[] value=(type+"|"+id).getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);out.flush(); }
        private static Frame read(DataInputStream in) throws IOException { int length=in.readInt();if(length<1||length>MAX_FRAME)throw new IOException();byte[] value=in.readNBytes(length);if(value.length!=length)throw new IOException();String[] parts=new String(value,StandardCharsets.UTF_8).split("\\|",-1);if(parts.length!=2)throw new IOException();try{return new Frame(parts[0],UUID.fromString(parts[1]));}catch(IllegalArgumentException e){throw new IOException();} }
        private static void require(Frame value,String type,UUID id)throws IOException{if(!type.equals(value.type())||!id.equals(value.id()))throw new IOException();}
        private void terminate(ContainmentDeadline deadline,Thread writer,Thread reader){
            if(terminationStarted.compareAndSet(false,true)){
                observer.phase("destroy-request");
                if(process.isAlive())process.destroy();
                try{if(process.isAlive()&&!deadline.expired())process.waitFor(deadline.remainingNanos(TERMINATION_RESERVE),TimeUnit.NANOSECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
                if(process.isAlive())process.destroyForcibly();
                try{if(process.isAlive()&&!deadline.expired())process.waitFor(deadline.remainingNanos(),TimeUnit.NANOSECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
                observer.phase("process-exit="+!process.isAlive());
                observer.phase("closer-submit");
                Thread closer=Thread.ofPlatform().daemon(true).name("selenium-attach-stream-close").start(()->{observer.phase("stream-close-start");try{process.getOutputStream().close();}catch(IOException ignored){}try{process.getInputStream().close();}catch(IOException ignored){}try{process.getErrorStream().close();}catch(IOException ignored){}streamsClosed.set(true);observer.streamsClosed();observer.phase("stream-close-end");});
                closerTask=closer;
                observer.closer(closer);
                if(writer!=null)writer.interrupt();
                if(reader!=null)reader.interrupt();
                if(!deadline.expired())try{closer.join(Duration.ofNanos(deadline.remainingNanos(CLOSE_JOIN_RESERVE)));}catch(InterruptedException failure){Thread.currentThread().interrupt();}
                if(closer.isAlive())closer.interrupt();
                if(!deadline.expired())try{closer.join(Duration.ofNanos(deadline.remainingNanos()));}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            }
            if(writer!=null&&!deadline.expired())try{writer.join(Duration.ofNanos(deadline.remainingNanos()));}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            if(reader!=null&&!deadline.expired())try{reader.join(Duration.ofNanos(deadline.remainingNanos()));}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            observer.phase("terminate-return");
        }
        private void close(ContainmentDeadline deadline,Thread writer,Thread reader) { terminate(deadline,writer,reader); }
        @Override public void close(ContainmentDeadline deadline) { close(deadline,null,null); }
        @Override public void close() { if (!terminationStarted.compareAndSet(false, true)) return; process.destroyForcibly(); }
        @Override public boolean closed() { return terminationStarted.get()&&!process.isAlive()&&streamsClosed.get()
                &&stopped(writerTask)&&stopped(readerTask)&&stopped(closerTask); }
        private static boolean stopped(Thread task){return task==null||!task.isAlive();}
        private record Frame(String type, UUID id) {}
    }
}
