package com.automationstudio.engine.selenium;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

final class SeleniumContainmentResources {
    private enum State { OPEN, TERMINATING, TERMINATED }
    enum Ownership { OWNED_AND_CLEANED, OWNED_BUT_UNRESOLVED }
    private final UUID executionId; private final SeleniumContainmentCommandRunner runner;
    private final SeleniumContainmentLimits limits; private final LongSupplier ticker;
    private final Consumer<ContainmentDeadline> terminationObserver;
    private final CompletableFuture<SeleniumAbsenceReport> completion=new CompletableFuture<>();
    private State state=State.OPEN; private ContainmentDeadline terminationDeadline;
    private boolean cleanupSnapshotTaken,startupComplete; private volatile Ownership ownership=Ownership.OWNED_AND_CLEANED;
    private SeleniumRuntimeIdentity worker; private SeleniumContainmentCommandRunner.AttachHandle attach;

    SeleniumContainmentResources(UUID id,SeleniumContainmentCommandRunner runner,SeleniumContainmentLimits limits){this(id,runner,limits,System::nanoTime,ignored->{});}
    SeleniumContainmentResources(UUID id,SeleniumContainmentCommandRunner runner,SeleniumContainmentLimits limits,LongSupplier ticker){this(id,runner,limits,ticker,ignored->{});}
    SeleniumContainmentResources(UUID id,SeleniumContainmentCommandRunner runner,SeleniumContainmentLimits limits,LongSupplier ticker,Consumer<ContainmentDeadline> terminationObserver){this.executionId=id;this.runner=runner;this.limits=limits;this.ticker=ticker;this.terminationObserver=terminationObserver;}
    synchronized void acquiredWorker(SeleniumRuntimeIdentity identity){acquiredWorker(identity,()->{});}
    void acquiredWorker(SeleniumRuntimeIdentity identity,Runnable acceptedHook){synchronized(this){if(worker!=null)throw new IllegalStateException("Worker already acquired");worker=identity;if(state!=State.OPEN&&(cleanupSnapshotTaken||!deadlinePositive()))ownership=Ownership.OWNED_BUT_UNRESOLVED;notifyAll();}acceptedHook.run();}
    synchronized void acquiredAttach(SeleniumContainmentCommandRunner.AttachHandle handle){if(attach!=null)throw new IllegalStateException("Attach already acquired");attach=handle;if(state!=State.OPEN&&(cleanupSnapshotTaken||!deadlinePositive()))ownership=Ownership.OWNED_BUT_UNRESOLVED;notifyAll();}
    synchronized void stage(SeleniumRuntimeIdentity.Stage stage){if(worker==null)throw new IllegalStateException("Worker not acquired");worker=worker.at(stage);}
    synchronized void startupComplete(){startupComplete=true;notifyAll();}
    void teardown(){cleanup();}
    SeleniumAbsenceReport cleanup(){
        boolean owner; synchronized(this){owner=state==State.OPEN;if(owner){state=State.TERMINATING;terminationDeadline=ContainmentDeadline.after(limits.cleanupTimeout(),ticker);terminationObserver.accept(terminationDeadline);}}
        if(!owner)return awaitCompletion();
        SeleniumAbsenceReport result;try{result=performCleanup(terminationDeadline);}catch(RuntimeException failure){result=failureReport();}
        synchronized(this){state=State.TERMINATED;} completion.complete(result);return completion.join();
    }
    private SeleniumAbsenceReport performCleanup(ContainmentDeadline deadline){
        SeleniumRuntimeIdentity localWorker;SeleniumContainmentCommandRunner.AttachHandle localAttach;boolean handedOff;
        synchronized(this){long wait=Math.min(deadline.remainingNanos(),limits.cleanupTimeout().toNanos()/6);if(!startupComplete&&wait>0)try{TimeUnit.NANOSECONDS.timedWait(this,wait);}catch(InterruptedException failure){Thread.currentThread().interrupt();}cleanupSnapshotTaken=true;localWorker=worker;localAttach=attach;handedOff=startupComplete;}
        boolean attachClosed=localAttach==null;SeleniumAbsenceReport.Code code=SeleniumAbsenceReport.Code.ABSENT;
        if(localAttach!=null&&!deadline.expired()){try{localAttach.shutdown(executionId,deadline);}catch(RuntimeException failure){try{localAttach.close(deadline);}catch(RuntimeException ignored){}code=SeleniumAbsenceReport.Code.CLEANUP_FAILED;}attachClosed=localAttach.closed();}
        boolean removed=localWorker==null;
        if(localWorker!=null&&!deadline.expired()){
            var inspected=runner.run(SeleniumWorkerCommand.inspectIdentityId(localWorker.containerId()),deadline,limits.maxOutputBytes());String expected=localWorker.containerId()+"|"+localWorker.executionLabel();
            if(inspected.exitCode()!=0)removed=true;else if(!expected.equals(inspected.output()))code=SeleniumAbsenceReport.Code.IDENTITY_MISMATCH;else if(!deadline.expired()){
                var removal=runner.run(SeleniumWorkerCommand.remove(localWorker.containerId()),deadline,limits.maxOutputBytes());if(removal.exitCode()!=0)code=SeleniumAbsenceReport.Code.CLEANUP_FAILED;
                if(!deadline.expired()){var absent=runner.run(SeleniumWorkerCommand.inspectId(localWorker.containerId()),deadline,limits.maxOutputBytes());removed=absent.exitCode()!=0;}
                if(!removed&&code==SeleniumAbsenceReport.Code.ABSENT)code=SeleniumAbsenceReport.Code.CONTAINER_PRESENT;
            }
        }
        if(localWorker!=null&&!removed&&code==SeleniumAbsenceReport.Code.ABSENT)code=SeleniumAbsenceReport.Code.CLEANUP_FAILED;if(!handedOff&&code==SeleniumAbsenceReport.Code.ABSENT)code=SeleniumAbsenceReport.Code.CLEANUP_FAILED;
        boolean proved=handedOff&&removed&&attachClosed&&ownership==Ownership.OWNED_AND_CLEANED&&code==SeleniumAbsenceReport.Code.ABSENT;
        return new SeleniumAbsenceReport(executionId,proved,proved,attachClosed,proved,proved,attachClosed,proved?SeleniumAbsenceReport.Code.ABSENT:code);
    }
    private SeleniumAbsenceReport awaitCompletion(){ContainmentDeadline deadline;synchronized(this){deadline=terminationDeadline;}try{if(!deadline.expired())completion.get(deadline.remainingNanos(),TimeUnit.NANOSECONDS);}catch(Exception ignored){completion.complete(failureReport());}if(!completion.isDone())completion.complete(failureReport());return completion.join();}
    private SeleniumAbsenceReport failureReport(){return new SeleniumAbsenceReport(executionId,false,false,false,false,false,false,SeleniumAbsenceReport.Code.CLEANUP_FAILED);}
    private synchronized boolean deadlinePositive(){return terminationDeadline!=null&&!terminationDeadline.expired();}
    synchronized Ownership ownership(){return ownership;} synchronized SeleniumRuntimeIdentity unresolvedWorker(){return ownership==Ownership.OWNED_BUT_UNRESOLVED?worker:null;} synchronized SeleniumContainmentCommandRunner.AttachHandle unresolvedAttach(){return ownership==Ownership.OWNED_BUT_UNRESOLVED?attach:null;}
    synchronized Long terminationDeadlineNanos(){return terminationDeadline==null?null:terminationDeadline.tick();} synchronized ContainmentDeadline terminationDeadline(){return terminationDeadline;}
}
