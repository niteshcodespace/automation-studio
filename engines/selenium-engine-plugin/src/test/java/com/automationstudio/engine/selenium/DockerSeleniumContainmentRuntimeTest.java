package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;
import com.automationstudio.engine.sdk.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DockerSeleniumContainmentRuntimeTest {
    @Test void registersTeardownBeforeCreateAndCleansPartialLifecycle() {
        UUID id=UUID.randomUUID(); String dockerId="d".repeat(64); var events=new ArrayList<String>();
        var runner=new Runner(events,id,dockerId); var control=new Control(events);
        var runtime=new DockerSeleniumContainmentRuntime("registry/worker@sha256:"+"a".repeat(64),SeleniumContainmentLimits.defaults(),runner);
        assertTrue(runtime.qualify(id,control).absenceProved());
        assertEquals("register",events.getFirst()); assertEquals(1,control.registrations);
        assertTrue(events.indexOf("register")<events.indexOf("create"));
    }
    static void proveCreateIdentityIsOwnedBeforeFailure() {
        UUID id=UUID.randomUUID();String dockerId="e".repeat(64);var events=new CopyOnWriteArrayList<String>();var runner=new Runner(events,id,dockerId);var control=new Control(events);
        var runtime=new DockerSeleniumContainmentRuntime("registry/worker@sha256:"+"a".repeat(64),SeleniumContainmentLimits.defaults(),runner,value->{assertEquals(dockerId,value);throw new SeleniumEngineException("INJECTED","injected");});
        assertThrows(SeleniumEngineException.class,()->runtime.qualify(id,control));assertTrue(events.contains("remove"));assertTrue(events.indexOf("create")<events.indexOf("remove"));
    }
    static void proveDeterministicCreateTeardownRace() throws Exception {
        UUID id=UUID.randomUUID();String dockerId="f".repeat(64);var events=new CopyOnWriteArrayList<String>();var runner=new Runner(events,id,dockerId);var control=new Control(events);var owned=new CountDownLatch(1);var release=new CountDownLatch(1);var terminating=new CountDownLatch(1);var captured=new AtomicReference<ContainmentDeadline>();
        var runtime=new DockerSeleniumContainmentRuntime("registry/worker@sha256:"+"a".repeat(64),SeleniumContainmentLimits.defaults(),runner,value->{owned.countDown();try{release.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();}},deadline->{captured.set(deadline);terminating.countDown();});
        try(var pool=Executors.newFixedThreadPool(2)){var startup=pool.submit(()->runtime.qualify(id,control));assertTrue(owned.await(1,TimeUnit.SECONDS));var teardown=pool.submit(()->control.teardown.teardown());assertTrue(terminating.await(1,TimeUnit.SECONDS));release.countDown();assertNotNull(startup.get());teardown.get();assertNotNull(captured.get());assertFalse(runner.deadlines.isEmpty());assertTrue(runner.deadlines.stream().allMatch(value->value==captured.get()));assertEquals(1,Collections.frequency(events,"remove"));}
    }
    static final class Control implements ExecutionControl {final List<String> events;int registrations;volatile ExecutionTeardown teardown;Control(List<String> e){events=e;} public Instant deadline(){return Instant.now().plusSeconds(30);}public Duration remainingTime(){return Duration.ofSeconds(30);}public boolean isExpired(){return false;}public boolean cancellationRequested(){return false;}public void registerTeardown(ExecutionTeardown t){registrations++;teardown=t;events.add("register");}public boolean isBounded(){return true;}}
    static final class Runner implements SeleniumContainmentCommandRunner {final List<String> events;final UUID id;final String dockerId;final SeleniumContainmentResourcesTest.Handle handle;final List<ContainmentDeadline> deadlines=new CopyOnWriteArrayList<>();Runner(List<String> e,UUID id,String dockerId){events=e;this.id=id;this.dockerId=dockerId;handle=new SeleniumContainmentResourcesTest.Handle(e);}public CommandResult run(List<String> c,Duration t,long m){return result(c);}public CommandResult run(List<String> c,ContainmentDeadline d,long m){deadlines.add(d);return result(c);}private CommandResult result(List<String> c){if(c.contains("create")){events.add("create");return new CommandResult(0,dockerId);}if(c.contains("--format")){events.add("inspect-name");return new CommandResult(0,dockerId+"|"+SeleniumWorkerCommand.label(id));}if(c.contains("rm")){events.add("remove");return new CommandResult(0,"");}events.add("inspect-id");return new CommandResult(1,"");}public AttachHandle attach(List<String> c){events.add("attach");return handle;}}
}
