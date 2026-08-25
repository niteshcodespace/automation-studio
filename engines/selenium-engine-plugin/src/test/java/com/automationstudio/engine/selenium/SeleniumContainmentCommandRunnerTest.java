package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SeleniumContainmentCommandRunnerTest {
    @Test void blockedOutputReaderIsTerminatedWithinBound() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command=System.getProperty("os.name").startsWith("Windows")?List.of("cmd.exe","/d","/s","/c","echo ready 1>&2 & set /p blocked="):List.of("/bin/sh","-c","printf ready >&2; read blocked");
        for(int run=1;run<=10;run++){
            var child = new AtomicReference<ProcessHandle>();var childObservedAt = new AtomicLong();var enteredAt=new AtomicLong();var reader = new AtomicReference<Thread>();
            var runner = new SeleniumContainmentCommandRunner.ProcessRunner(handle -> {assertTrue(handle.isAlive());child.set(handle);childObservedAt.set(System.nanoTime());}, reader::set,phase->{if(phase.equals("method-start"))enteredAt.set(System.nanoTime());});
            var result=runner.run(command,ContainmentDeadline.after(Duration.ofMillis(300)),1024);long returned=System.nanoTime();
            long elapsed=Duration.ofNanos(returned-enteredAt.get()).toMillis();
            assertEquals(-1,result.exitCode());assertNotNull(child.get());assertTrue(childObservedAt.get()>=enteredAt.get());child.get().onExit().get(500,TimeUnit.MILLISECONDS);assertFalse(child.get().isAlive());assertNotNull(reader.get());reader.get().join(Duration.ofMillis(500));assertFalse(reader.get().isAlive());
            System.out.println("L run="+run+" deadline=300ms elapsed="+elapsed+"ms");assertTrue(elapsed<400,"300 ms command exceeded narrow 100 ms scheduling margin");
        }
    }

    @Test void blockedAttachWriteIsTerminatedWithinOriginalDeadline() throws Exception {
        var output = new BlockingWriteStream();
        var process = new ControlledProcess(output, InputStream.nullInputStream());
        var lifecycle = new AttachLifecycle();
        var attach = new SeleniumContainmentCommandRunner.ProcessAttach(process, lifecycle);
        long started = System.nanoTime(); lifecycle.origin = started;
        try { attach.shutdown(UUID.randomUUID(), ContainmentDeadline.after(Duration.ofMillis(250))); fail(); }
        catch (SeleniumEngineException expected) { }
        long elapsed = Duration.ofNanos(lifecycle.returnedAt - lifecycle.enteredAt).toMillis();
        assertTrue(output.entered.getCount() == 0);
        assertFalse(process.isAlive());
        assertNotNull(lifecycle.writer); assertTrue(lifecycle.writer.isAlive(), "uninterruptible writer must be reported unresolved");
        assertNotNull(lifecycle.closer); assertFalse(lifecycle.closer.isAlive()); assertTrue(lifecycle.streamsClosed);
        assertFalse(attach.closed(), "an unresolved writer must keep cleanup unsafe");
        System.out.println("blocked-write deadline=250ms elapsed="+elapsed+"ms phases="+lifecycle.describe());
        assertTrue(elapsed < 350, "method-entry to return must remain near the 250 ms deadline");
        output.releaseWrite.countDown(); lifecycle.writer.join(Duration.ofSeconds(1)); assertFalse(lifecycle.writer.isAlive());
    }

    @Test void blockedAttachCloseIsInterruptedWithinOriginalDeadline() throws Exception {
        UUID id = UUID.randomUUID();
        var output = new BlockingCloseStream();
        var process = new ControlledProcess(output, new ByteArrayInputStream(frame("BYE", id)));
        var lifecycle = new AttachLifecycle();
        lifecycle.onProtocolCompleted = () -> output.blockClose.set(true);
        var attach = new SeleniumContainmentCommandRunner.ProcessAttach(process, lifecycle);
        long started = System.nanoTime(); lifecycle.origin = started;
        attach.shutdown(id, ContainmentDeadline.after(Duration.ofMillis(250)));
        long elapsed = Duration.ofNanos(lifecycle.returnedAt - lifecycle.enteredAt).toMillis();
        assertTrue(lifecycle.protocolCompleted);
        assertTrue(output.closeEntered.getCount() == 0);
        assertFalse(process.isAlive());
        assertNotNull(lifecycle.closer); assertTrue(lifecycle.closer.isAlive());
        assertFalse(attach.closed(), "blocked close must remain explicitly unsafe/unproved");
        System.out.println("blocked-close deadline=250ms elapsed="+elapsed+"ms phases="+lifecycle.describe());
        assertTrue(elapsed < 350, "blocked close must not hold the caller past the deadline");
        output.releaseClose.countDown(); lifecycle.closer.join(Duration.ofSeconds(1)); assertFalse(lifecycle.closer.isAlive());
    }

    private static byte[] frame(String type, UUID id) throws IOException {
        var bytes = (type + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var buffer = new ByteArrayOutputStream();
        var output = new DataOutputStream(buffer);
        output.writeInt(bytes.length);
        output.write(bytes);
        return buffer.toByteArray();
    }

    private static final class AttachLifecycle implements SeleniumContainmentCommandRunner.ProcessAttach.LifecycleObserver {
        volatile Thread writer, reader, closer;
        volatile boolean streamsClosed;
        volatile boolean protocolCompleted;
        volatile long origin, enteredAt, returnedAt;
        volatile Runnable onProtocolCompleted = () -> {};
        public void writer(Thread value) { writer = value; }
        public void reader(Thread value) { reader = value; }
        public void closer(Thread value) { closer = value; }
        public void streamsClosed() { streamsClosed = true; }
        public void protocolCompleted() { protocolCompleted = true; onProtocolCompleted.run(); }
        public void phase(String value) { if (value.equals("method-start")) enteredAt=System.nanoTime(); if(value.equals("method-return"))returnedAt=System.nanoTime(); }
        String describe() { return "writer="+state(writer)+", reader="+state(reader)+", closer="+state(closer)+", streamsClosed="+streamsClosed; }
        private static String state(Thread thread) { return thread == null ? "not-started" : thread.isAlive() ? "alive" : "stopped"; }
        void assertStopped() {
            assertNotNull(writer); assertFalse(writer.isAlive());
            if (reader != null) assertFalse(reader.isAlive());
            assertNotNull(closer); assertFalse(closer.isAlive()); assertTrue(streamsClosed);
        }
    }

    private static final class BlockingWriteStream extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1), releaseWrite = new CountDownLatch(1);
        @Override public void write(int value) throws IOException {
            entered.countDown();
            boolean interrupted=false;
            while(true)try{releaseWrite.await();break;}catch(InterruptedException failure){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();
        }
        @Override public void close() { }
    }

    private static final class BlockingCloseStream extends ByteArrayOutputStream {
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final AtomicBoolean blockClose = new AtomicBoolean();
        @Override public void close() throws IOException {
            closeEntered.countDown();
            if (!blockClose.get()) { super.close(); return; }
            boolean interrupted=false;
            while(true)try{releaseClose.await();break;}catch(InterruptedException failure){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }

    private static final class ControlledProcess extends Process {
        private final OutputStream output;
        private final InputStream input;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        ControlledProcess(OutputStream output, InputStream input) { this.output = output; this.input = input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { alive.set(false); return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive.get(); }
        @Override public int exitValue() { if (alive.get()) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { alive.set(false); }
        @Override public Process destroyForcibly() { alive.set(false); return this; }
        @Override public boolean isAlive() { return alive.get(); }
    }

    public static final class BlockingChild {
        public static void main(String[] args) throws Exception { System.err.write(1);System.err.flush();new CountDownLatch(1).await(); }
    }
    public static final class RejectingChild {
        public static void main(String[] args) throws Exception { System.err.write(1);System.err.flush();var in=new DataInputStream(System.in);int length=in.readInt();in.readNBytes(length);var out=new DataOutputStream(System.out);byte[] value="NOPE|00000000-0000-0000-0000-000000000000".getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);out.flush();System.in.read(); }
    }
}
