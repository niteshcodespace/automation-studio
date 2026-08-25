package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SeleniumLifecycleFailureMatrixTest {
    @Test void aFailureBeforeDockerCreateHasSafeEmptyCleanup() { var f=new Fixture(); f.resources.startupComplete(); assertTrue(f.resources.cleanup().absenceProved()); assertTrue(f.runner.events.isEmpty()); }
    @Test void bCreateIdentityIsOwnedBeforeSubsequentFailure() { DockerSeleniumContainmentRuntimeTest.proveCreateIdentityIsOwnedBeforeFailure(); }
    @Test void cFailureAfterIdentityBeforeStartRemovesExactId() { var f=new Fixture(); f.worker(); f.resources.startupComplete(); assertTrue(f.resources.cleanup().absenceProved()); f.assertRemoved(); }
    @Test void dFailureAfterStartBeforeAttachKeepsExactOwnership() { var f=new Fixture(); f.worker(); f.resources.stage(SeleniumRuntimeIdentity.Stage.STARTED); f.resources.startupComplete(); assertTrue(f.resources.cleanup().absenceProved()); f.assertRemoved(); }
    @Test void eAttachBeforeReadyIsClosedBeforeContainerRemoval() throws Exception { var f=new Fixture(Duration.ofMillis(1500));f.worker();String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();Process process=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),SeleniumContainmentCommandRunnerTest.RejectingChild.class.getName()).start();assertEquals(1,process.getErrorStream().read());assertTrue(process.isAlive());var lifecycle=new AttachLifecycle();f.resources.acquiredAttach(new SeleniumContainmentCommandRunner.ProcessAttach(process,lifecycle));f.runner.beforeRemove=()->{assertFalse(process.isAlive());assertTrue(lifecycle.streamsClosed);assertTasksStopped(lifecycle);};f.resources.startupComplete();assertFalse(f.resources.cleanup().absenceProved());process.onExit().get(500,TimeUnit.MILLISECONDS);assertFalse(process.isAlive());f.assertRemoved(); }
    @Test void fReadyBeforeHandoffUsesSameAggregate() { var f=new Fixture(); f.worker(); f.resources.acquiredAttach(f.handle); f.resources.stage(SeleniumRuntimeIdentity.Stage.READY); f.resources.startupComplete(); assertTrue(f.resources.cleanup().absenceProved()); f.assertRemoved(); }
    @Test void gTeardownRacingWorkerAcquisitionUsesOriginalDeadline() throws Exception { DockerSeleniumContainmentRuntimeTest.proveDeterministicCreateTeardownRace(); }
    @Test void hLateAttachAfterDeadlineIsRetainedUnresolvedWithoutNewDeadline() throws Exception { var ticks=new AtomicLong();var f=new Fixture(Duration.ofMillis(300),ticks::get);try(var pool=Executors.newSingleThreadExecutor()){var cleanup=pool.submit(f.resources::cleanup);Long deadline;while((deadline=f.resources.terminationDeadlineNanos())==null)Thread.onSpinWait();ticks.set(deadline+1);f.resources.acquiredAttach(f.handle);var report=cleanup.get();f.resources.startupComplete();assertEquals(0,f.resources.terminationDeadline().remainingNanos());assertFalse(report.absenceProved());assertSame(report,f.resources.cleanup());assertEquals(SeleniumContainmentResources.Ownership.OWNED_BUT_UNRESOLVED,f.resources.ownership());assertSame(f.handle,f.resources.unresolvedAttach());assertEquals(deadline,f.resources.terminationDeadlineNanos());assertFalse(f.handle.closed());} }
    @Test void iRemoveFailureStillAttemptsPostRemoveInspectionAndIsUnsafe() { var f=new Fixture();f.runner.removeExit=1;f.runner.absentExit=1;f.worker();f.resources.startupComplete();assertFalse(f.resources.cleanup().absenceProved());assertEquals(List.of("inspect","remove","absent"),f.runner.events); }
    @Test void jForcedRemoveFailureWithPresentIdIsUnsafe() { var f=new Fixture();f.runner.removeExit=1;f.runner.absentExit=0;f.worker();f.resources.startupComplete();var report=f.resources.cleanup();assertFalse(report.containerAbsent());assertEquals(SeleniumAbsenceReport.Code.CLEANUP_FAILED,report.code()); }
    @Test void kPostRemoveIdStillPresentDoesNotInferJvmOrStorageAbsence() { var f=new Fixture();f.runner.absentExit=0;f.worker();f.resources.startupComplete();var report=f.resources.cleanup();assertFalse(report.containerAbsent());assertFalse(report.workerJvmAbsent());assertFalse(report.ephemeralStorageAbsent()); }
    @Test void sharedDeadlinePassesStrictlyDecreasingRemainingTimeouts() { var f=new Fixture();f.worker();f.resources.startupComplete();f.resources.cleanup();assertEquals(3,f.runner.timeouts.size());assertTrue(f.runner.timeouts.get(0).compareTo(f.runner.timeouts.get(1))>0);assertTrue(f.runner.timeouts.get(1).compareTo(f.runner.timeouts.get(2))>0);assertTrue(f.runner.deadlines.stream().allMatch(d->d==f.resources.terminationDeadline())); }
    @Test void mBlockingAttachOutputTerminatesExactProcessWithinDeadline() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        for(int run=1;run<=10;run++){
            Process process=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),SeleniumContainmentCommandRunnerTest.BlockingChild.class.getName()).start();
            assertEquals(1,process.getErrorStream().read());
            var lifecycle=new AttachLifecycle();var attach=new SeleniumContainmentCommandRunner.ProcessAttach(process,lifecycle);
            try{attach.shutdown(UUID.randomUUID(),ContainmentDeadline.after(Duration.ofMillis(250)));fail();}catch(SeleniumEngineException expected){}
            long returned=System.nanoTime();long elapsed=Duration.ofNanos(returned-lifecycle.enteredAt).toMillis();
            process.onExit().get(500,TimeUnit.MILLISECONDS);assertFalse(process.isAlive());assertTrue(lifecycle.streamsClosed);assertTasksStopped(lifecycle);assertTrue(attach.closed());
            System.out.println("M run="+run+" deadline=250ms elapsed="+elapsed+"ms phases="+lifecycle.describe());
            assertTrue(elapsed<350,"250 ms attach teardown exceeded narrow 100 ms scheduling margin");
        }
    }
    @Test void hostileAttachWithPlatformCleanupBudgetReturnsBelowTwoSeconds() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Process process=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),SeleniumContainmentCommandRunnerTest.BlockingChild.class.getName()).start();assertEquals(1,process.getErrorStream().read());
        var lifecycle=new AttachLifecycle();var attach=new SeleniumContainmentCommandRunner.ProcessAttach(process,lifecycle);
        try{attach.shutdown(UUID.randomUUID(),ContainmentDeadline.after(Duration.ofMillis(1500)));fail();}catch(SeleniumEngineException expected){}
        long elapsed=Duration.ofNanos(System.nanoTime()-lifecycle.enteredAt).toMillis();
        process.onExit().get(500,TimeUnit.MILLISECONDS);assertFalse(process.isAlive());assertTrue(lifecycle.streamsClosed);assertTasksStopped(lifecycle);assertTrue(attach.closed());
        System.out.println("platform deadline=1500ms elapsed="+elapsed+"ms phases="+lifecycle.describe());assertTrue(elapsed<2000,"D1 hostile attach cleanup must remain below AS-031B's two-second bound");
    }

    private static void assertTasksStopped(AttachLifecycle lifecycle){assertNotNull(lifecycle.writer);assertFalse(lifecycle.writer.isAlive());if(lifecycle.reader!=null)assertFalse(lifecycle.reader.isAlive());assertNotNull(lifecycle.closer);assertFalse(lifecycle.closer.isAlive());}
    private static final class AttachLifecycle implements SeleniumContainmentCommandRunner.ProcessAttach.LifecycleObserver {volatile Thread writer,reader,closer;volatile boolean streamsClosed;volatile long enteredAt;final List<Phase> phases=new CopyOnWriteArrayList<>();public void writer(Thread value){writer=value;}public void reader(Thread value){reader=value;}public void closer(Thread value){closer=value;}public void streamsClosed(){streamsClosed=true;}public void phase(String value){long now=System.nanoTime();if(value.equals("method-start"))enteredAt=now;phases.add(new Phase(value,now));}String describe(){return phases.stream().map(p->p.name()+"="+Duration.ofNanos(p.at()-enteredAt).toMillis()+"ms").toList().toString();}}
    private record Phase(String name,long at){}

    private static final class Fixture {
        final UUID id=UUID.randomUUID();final String dockerId="b".repeat(64);final Runner runner=new Runner();final Handle handle=new Handle(runner.events);final SeleniumContainmentResources resources;
        Fixture(){this(Duration.ofMillis(1500));} Fixture(Duration cleanup){this(cleanup,System::nanoTime);}Fixture(Duration cleanup,java.util.function.LongSupplier ticker){resources=new SeleniumContainmentResources(id,runner,new SeleniumContainmentLimits(Duration.ofSeconds(1),Duration.ofSeconds(1),cleanup,1024,64L*1024*1024,1,2,1024),ticker);runner.identity=dockerId+"|"+SeleniumWorkerCommand.label(id);}
        void worker(){resources.acquiredWorker(new SeleniumRuntimeIdentity(id,SeleniumWorkerCommand.workerName(id),SeleniumWorkerCommand.label(id),dockerId,SeleniumRuntimeIdentity.Stage.CREATED));}
        void assertRemoved(){assertTrue(runner.events.contains("remove"));assertTrue(runner.commands.stream().filter(c->c.contains("rm")).allMatch(c->c.contains(dockerId)));}
    }
    private static final class Runner implements SeleniumContainmentCommandRunner {final List<String> events=new CopyOnWriteArrayList<>();final List<List<String>> commands=new CopyOnWriteArrayList<>();final List<Duration> timeouts=new CopyOnWriteArrayList<>();final List<ContainmentDeadline> deadlines=new CopyOnWriteArrayList<>();String identity;int removeExit;int absentExit=1;Runnable beforeRemove=()->{};public CommandResult run(List<String> c,Duration t,long m){return result(c,t);}public CommandResult run(List<String> c,ContainmentDeadline d,long m){deadlines.add(d);return result(c,d.remaining());}private CommandResult result(List<String> c,Duration t){commands.add(c);timeouts.add(t);if(c.contains("--format")){events.add("inspect");return new CommandResult(0,identity);}if(c.contains("rm")){beforeRemove.run();events.add("remove");return new CommandResult(removeExit,"");}events.add("absent");return new CommandResult(absentExit,"");}public AttachHandle attach(List<String> c){throw new UnsupportedOperationException();}}
    private static final class Handle implements SeleniumContainmentCommandRunner.AttachHandle {final List<String> events;volatile boolean closed;Handle(List<String> events){this.events=events;}public void awaitReady(UUID id,Duration timeout){}public void shutdown(UUID id,ContainmentDeadline timeout){events.add("shutdown");closed=true;}public boolean closed(){return closed;}public void close(ContainmentDeadline deadline){closed=true;}public void close(){closed=true;}}
}
