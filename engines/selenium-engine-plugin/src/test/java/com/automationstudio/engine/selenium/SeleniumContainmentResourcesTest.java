package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SeleniumContainmentResourcesTest {
    @Test void cleanupIsIdentityCheckedReverseOrderedAndIdempotent() throws Exception {
        UUID id = UUID.randomUUID(); String dockerId = "b".repeat(64);
        FakeRunner runner = new FakeRunner(dockerId+"|"+SeleniumWorkerCommand.label(id));
        var resources = new SeleniumContainmentResources(id, runner, SeleniumContainmentLimits.defaults());
        resources.acquiredWorker(identity(id, dockerId)); resources.acquiredAttach(runner.handle); resources.startupComplete();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(resources::cleanup); var second = pool.submit(resources::cleanup);
            assertTrue(first.get().absenceProved()); assertTrue(second.get().absenceProved());
        }
        assertEquals(List.of("shutdown", "inspect-id-identity", "remove", "inspect-id"), runner.events);
    }
    @Test void mismatchFailsClosedAndNeverRemoves() {
        UUID id=UUID.randomUUID(); FakeRunner runner=new FakeRunner("c".repeat(64)+"|wrong");
        var resources=new SeleniumContainmentResources(id,runner,SeleniumContainmentLimits.defaults());
        resources.acquiredWorker(identity(id,"b".repeat(64))); resources.startupComplete();
        var report=resources.cleanup();
        assertEquals(SeleniumAbsenceReport.Code.IDENTITY_MISMATCH,report.code());
        assertFalse(runner.events.contains("remove"));
    }
    @Test void teardownWaitsForRacingAcquisitionAndPublishesOneReport() throws Exception {
        UUID id=UUID.randomUUID(); String dockerId="e".repeat(64); FakeRunner runner=new FakeRunner(dockerId+"|"+SeleniumWorkerCommand.label(id));
        var resources=new SeleniumContainmentResources(id,runner,SeleniumContainmentLimits.defaults());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var cleanup=pool.submit(resources::cleanup);
            while(cleanup.state()!=Future.State.RUNNING) Thread.onSpinWait();
            resources.acquiredWorker(identity(id,dockerId)); resources.acquiredAttach(runner.handle); resources.startupComplete();
            var first=cleanup.get(); var second=resources.cleanup();
            assertNotNull(first); assertSame(first,second); assertTrue(first.absenceProved());
        }
        assertEquals(1,Collections.frequency(runner.events,"remove"));
    }
    @Test void immutableIdRemainsAuthoritativeWhenLogicalNameIsUnavailable() {
        UUID id=UUID.randomUUID(); String dockerId="f".repeat(64); FakeRunner runner=new FakeRunner(dockerId+"|"+SeleniumWorkerCommand.label(id));
        var resources=new SeleniumContainmentResources(id,runner,SeleniumContainmentLimits.defaults());
        resources.acquiredWorker(identity(id,dockerId)); resources.startupComplete();
        assertTrue(resources.cleanup().absenceProved());
        assertEquals("inspect-id-identity",runner.events.getFirst());
    }
    @Test void laterCallerUsesOriginalDeadlineAndReceivesSamePublishedReport() throws Exception {
        UUID id=UUID.randomUUID();var ticks=new AtomicLong();var runner=new BlockingRunner();var limits=new SeleniumContainmentLimits(Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofMillis(300),1024,64L*1024*1024,1,2,1024);var resources=new SeleniumContainmentResources(id,runner,limits,ticks::get);resources.acquiredWorker(identity(id,"9".repeat(64)));resources.startupComplete();
        try(var pool=Executors.newFixedThreadPool(2)){var owner=pool.submit(resources::cleanup);assertTrue(runner.entered.await(1,TimeUnit.SECONDS));long deadline=resources.terminationDeadlineNanos();ticks.set(deadline+1);var follower=pool.submit(resources::cleanup);var second=follower.get();runner.release.countDown();var first=owner.get();assertSame(first,second);assertFalse(first.absenceProved());assertEquals(1,runner.calls);}
    }
    @Test void expiredStartupDeadlinePublishesUnsafeReportAndLateHandoffStillCleans() {
        UUID id=UUID.randomUUID(); String dockerId="a".repeat(64); FakeRunner runner=new FakeRunner(dockerId+"|"+SeleniumWorkerCommand.label(id));
        var limits=new SeleniumContainmentLimits(Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofMillis(100),
                1024,64L*1024*1024,1,2,1024);
        var resources=new SeleniumContainmentResources(id,runner,limits); long started=System.nanoTime();
        var report=resources.cleanup();
        assertFalse(report.absenceProved()); assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<500);
        Long deadline=resources.terminationDeadlineNanos();
        resources.acquiredWorker(identity(id,dockerId)); resources.startupComplete();
        assertFalse(runner.events.contains("remove")); assertEquals(SeleniumContainmentResources.Ownership.OWNED_BUT_UNRESOLVED,resources.ownership());
        assertEquals(dockerId,resources.unresolvedWorker().containerId()); assertEquals(deadline,resources.terminationDeadlineNanos());
        assertSame(report,resources.cleanup());
    }
    private static SeleniumRuntimeIdentity identity(UUID id,String dockerId){return new SeleniumRuntimeIdentity(id,SeleniumWorkerCommand.workerName(id),SeleniumWorkerCommand.label(id),dockerId,SeleniumRuntimeIdentity.Stage.CREATED);}
    static final class FakeRunner implements SeleniumContainmentCommandRunner {
        final List<String> events=Collections.synchronizedList(new ArrayList<>()); final String inspect; final Handle handle=new Handle(events);
        FakeRunner(String inspect){this.inspect=inspect;}
        public CommandResult run(List<String> command,Duration timeout,long max){String action=command.contains("--format")?"inspect-id-identity":command.contains("rm")?"remove":"inspect-id";events.add(action);return switch(action){case "inspect-id-identity"->new CommandResult(0,inspect);case "remove"->new CommandResult(0,"");default->new CommandResult(1,"");};}
        public AttachHandle attach(List<String> command){events.add("attach");return handle;}
    }
    static final class Handle implements SeleniumContainmentCommandRunner.AttachHandle { final List<String> events; boolean closed; Handle(List<String> e){events=e;} public void awaitReady(UUID id,Duration t){} public void shutdown(UUID id,ContainmentDeadline t){events.add("shutdown");closed=true;} public boolean closed(){return closed;} public void close(ContainmentDeadline deadline){closed=true;} public void close(){closed=true;} }
    static final class BlockingRunner implements SeleniumContainmentCommandRunner {final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);volatile int calls;public CommandResult run(List<String> c,Duration t,long m){throw new AssertionError();}public CommandResult run(List<String> c,ContainmentDeadline d,long m){calls++;entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return new CommandResult(-1,"");}public AttachHandle attach(List<String> c){throw new AssertionError();}}
}
