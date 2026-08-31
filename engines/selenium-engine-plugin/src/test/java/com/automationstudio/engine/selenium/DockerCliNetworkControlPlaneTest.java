package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DockerCliNetworkControlPlaneTest {
    private static final String ID = "a".repeat(64);

    @Test void closedSurfaceBuildsOnlyFrozenCreateAndRecoveryArgv() throws Exception {
        Harness harness = harness(); DockerNetworkSpec spec = DockerNetworkSpec.create(
                UUID.randomUUID(), 7, "b".repeat(64), "c".repeat(64));
        harness.runner.operation(ok(ID));
        assertTrue(harness.adapter.create(new DockerNetworkControlPlane.NetworkCreateRequest(
                harness.issuer, spec), harness.deadline).successful());
        List<String> create = harness.runner.operations.get(0);
        assertEquals(List.of("docker", "network", "create"), create.subList(0, 3));
        assertTrue(create.containsAll(List.of("--driver", "bridge", "--scope", "local",
                "--internal=false", "--attachable=false", "--ingress=false", "--config-only=false",
                "--ipv4=true", "--ipv6=false", "--ipam-driver", "default")));
        assertEquals(5, create.stream().filter("--label"::equals).count());
        assertFalse(String.join(" ", create).matches(".*(--subnet|--gateway|--ip-range|--aux-address|--opt|--config-from|--external).*"));

        harness.runner.operation(ok(ID));
        harness.adapter.lookupAttempt(new DockerNetworkControlPlane.NetworkAttemptLookup(
                spec.executionId().toString(), "7", spec.attemptNonce(), spec.deadlineCorrelation()),
                harness.deadline);
        List<String> lookup = harness.runner.operations.get(1);
        assertEquals(List.of("docker", "network", "ls", "--no-trunc"), lookup.subList(0, 4));
        assertEquals(5, lookup.stream().filter("--filter"::equals).count());
        assertEquals(List.of("--format", "{{.ID}}"), lookup.subList(lookup.size() - 2, lookup.size()));
        assertTrue(java.util.Arrays.stream(DockerCliNetworkControlPlane.class.getDeclaredConstructors())
                .allMatch(value -> Modifier.isPrivate(value.getModifiers())));
    }

    @Test void exactIdOperationsAndRealNotFoundClassificationAreStrict() throws Exception {
        Harness harness = harness();
        harness.runner.operation(ok("[]")); harness.adapter.inspectExactId(ID, harness.deadline);
        harness.runner.operation(ok("")); harness.adapter.removeExactId(ID, harness.deadline);
        harness.runner.operation(new DockerCliNetworkControlPlane.CommandResult(
                DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, 1,
                "Error response from daemon: network " + ID + " not found", true));
        var absent = harness.adapter.inspectExactIdAbsence(ID, harness.deadline);
        assertEquals(DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND, absent.semantic());
        assertEquals(List.of("docker", "network", "inspect", ID), harness.runner.operations.get(0));
        assertEquals(List.of("docker", "network", "rm", ID), harness.runner.operations.get(1));
        assertEquals(List.of("docker", "network", "inspect", ID), harness.runner.operations.get(2));

        for (String response : List.of(
                "Error response from daemon: network " + "b".repeat(64) + " not found",
                "Error response from daemon: network " + ID.substring(0, 12) + " not found",
                "Error response from daemon: network " + ID + " not found; permission denied",
                "generic daemon failure mentioning " + ID)) {
            harness.runner.operation(new DockerCliNetworkControlPlane.CommandResult(
                    DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                    DockerControlPlane.Completion.COMPLETED, 1, response, true));
            assertEquals(DockerControlPlane.Semantic.NONE,
                    harness.adapter.inspectExactIdAbsence(ID, harness.deadline).semantic());
        }
        assertThrows(IllegalArgumentException.class,
                () -> harness.adapter.removeExactId(ID.substring(1), harness.deadline));
    }

    @Test void transportDistinctionsBoundedOutputAndContinuityArePreserved() throws Exception {
        for (var completion : List.of(DockerControlPlane.Completion.SPAWN_FAILED,
                DockerControlPlane.Completion.TIMED_OUT, DockerControlPlane.Completion.INTERRUPTED,
                DockerControlPlane.Completion.READ_FAILED, DockerControlPlane.Completion.OUTPUT_OVERFLOW)) {
            Harness harness = harness();
            DockerControlPlane.Dispatch dispatch = completion == DockerControlPlane.Completion.SPAWN_FAILED
                    ? DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED
                    : completion == DockerControlPlane.Completion.TIMED_OUT
                            || completion == DockerControlPlane.Completion.INTERRUPTED
                    ? DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED
                    : DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED;
            harness.runner.operation(new DockerCliNetworkControlPlane.CommandResult(dispatch,
                    completion, dispatch == DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED ? 1 : null,
                    null, completion == DockerControlPlane.Completion.SPAWN_FAILED));
            var result = harness.adapter.inspectExactId(ID, harness.deadline);
            assertEquals(dispatch, result.dispatch()); assertEquals(completion, result.completion());
            assertEquals(completion == DockerControlPlane.Completion.SPAWN_FAILED,
                    result.responseComplete(), completion.toString());
        }
        Harness daemonFailure = harness(); daemonFailure.runner.operation(okExit(1,
                "Error response from daemon: unavailable"));
        assertEquals(DockerControlPlane.Completion.DAEMON_FAILED,
                daemonFailure.adapter.inspectExactId(ID, daemonFailure.deadline).completion());
        Harness noContinuity = harness(); noContinuity.runner.continuityEnabled = false;
        assertEquals(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                noContinuity.adapter.inspectExactId(ID, noContinuity.deadline).dispatch());
    }

    @Test void representativeDockerInspectIntegratesWithCanonicalParser() throws Exception {
        Harness harness = harness(); DockerNetworkSpec spec = DockerNetworkSpec.create(
                UUID.randomUUID(), 9, "d".repeat(64), "e".repeat(64));
        String labels = spec.labels().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String raw = "[{\"Id\":\"" + ID + "\",\"Driver\":\"bridge\",\"Scope\":\"local\"," +
                "\"Internal\":false,\"Attachable\":false,\"Ingress\":false,\"ConfigOnly\":false," +
                "\"ConfigFrom\":{\"Network\":\"\"},\"EnableIPv4\":true,\"EnableIPv6\":false," +
                "\"IPAM\":{\"Driver\":\"default\",\"Options\":{},\"Config\":[{" +
                "\"Subnet\":\"172.30.0.0/16\",\"Gateway\":\"172.30.0.1\"}]}," +
                "\"Options\":{},\"Labels\":{" + labels + "}}]";
        harness.runner.operation(ok(raw));
        var outcome = harness.adapter.inspectExactId(ID, harness.deadline);
        assertEquals(ID, DockerNetworkInspectParser.parse(outcome.response(), spec, ID).networkId());
        assertThrows(IllegalArgumentException.class, () -> DockerNetworkInspectParser.parse(
                raw.replace("{\"Network\":\"\"}", "{\"Network\":\"source\"}"), spec, ID));
        assertThrows(IllegalArgumentException.class, () -> DockerNetworkInspectParser.parse(
                raw.replace("{\"Network\":\"\"}", "{\"Network\":\"\",\"Extra\":true}"), spec, ID));
    }

    @Test void realProcessRunnerBoundsOutputAndCleansUpTimeout() throws Exception {
        var overflow = runProcess("overflow", ContainmentDeadline.after(Duration.ofSeconds(10)));
        assertEquals(DockerControlPlane.Completion.OUTPUT_OVERFLOW, overflow.completion());
        assertFalse(overflow.responseComplete());

        var timeoutBoundary = new TrackingBoundary();
        var timeout = runProcess("sleep", ContainmentDeadline.after(Duration.ofMillis(100)),
                timeoutBoundary);
        assertEquals(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED, timeout.dispatch());
        assertEquals(DockerControlPlane.Completion.TIMED_OUT, timeout.completion());
        assertTrue(timeout.cleanupComplete());
        assertFalse(timeoutBoundary.process.get().isAlive());
        assertFalse(timeoutBoundary.reader.get().isAlive());
    }

    @Test void realProcessRunnerInterruptsAfterDispatchAndCleansProcessAndReader() throws Exception {
        var boundary = new TrackingBoundary();
        var result = new AtomicReference<DockerCliNetworkControlPlane.CommandResult>();
        var failure = new AtomicReference<Throwable>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try { result.set(runProcess("sleep", ContainmentDeadline.after(Duration.ofSeconds(10)), boundary)); }
            catch (Throwable problem) { failure.set(problem); }
        });
        assertTrue(boundary.started.await(5, TimeUnit.SECONDS));
        assertTrue(boundary.process.get().isAlive()); caller.interrupt(); caller.join(5000);
        assertFalse(caller.isAlive()); assertNull(failure.get());
        assertEquals(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED, result.get().dispatch());
        assertEquals(DockerControlPlane.Completion.INTERRUPTED, result.get().completion());
        assertTrue(result.get().cleanupComplete());
        assertFalse(boundary.process.get().isAlive()); assertFalse(boundary.reader.get().isAlive());
    }

    @Test void productionRunnerClassifiesDeterministicReadFailure() throws Exception {
        Method method = DockerCliNetworkControlPlane.class.getDeclaredMethod("runProcess",
                List.class, ContainmentDeadline.class,
                DockerCliNetworkControlPlane.ProcessStarter.class,
                DockerCliNetworkControlPlane.ProcessBoundary.class);
        method.setAccessible(true);
        var result = (DockerCliNetworkControlPlane.CommandResult) method.invoke(null,
                List.of("closed-test-command"), ContainmentDeadline.after(Duration.ofSeconds(5)),
                (DockerCliNetworkControlPlane.ProcessStarter) argv -> new ReadFailureProcess(),
                DockerCliNetworkControlPlane.ProcessBoundary.NONE);
        assertEquals(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED, result.dispatch());
        assertEquals(DockerControlPlane.Completion.READ_FAILED, result.completion());
        assertFalse(result.responseComplete());
    }

    private static DockerCliNetworkControlPlane.CommandResult runProcess(String mode,
            ContainmentDeadline deadline) throws Exception {
        return runProcess(mode, deadline, DockerCliNetworkControlPlane.ProcessBoundary.NONE);
    }
    private static DockerCliNetworkControlPlane.CommandResult runProcess(String mode,
            ContainmentDeadline deadline, DockerCliNetworkControlPlane.ProcessBoundary boundary) throws Exception {
        Method method = DockerCliNetworkControlPlane.class.getDeclaredMethod("runProcess",
                List.class, ContainmentDeadline.class,
                DockerCliNetworkControlPlane.ProcessStarter.class,
                DockerCliNetworkControlPlane.ProcessBoundary.class);
        method.setAccessible(true);
        DockerCliNetworkControlPlane.ProcessStarter starter = argv ->
                new ProcessBuilder(argv).redirectErrorStream(true).start();
        String javaCommand = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        return (DockerCliNetworkControlPlane.CommandResult) method.invoke(null,
                List.of(javaCommand, "-cp", classpath, TransportProbe.class.getName(), mode),
                deadline, starter, boundary);
    }
    public static final class TransportProbe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("overflow")) System.out.print("x".repeat(1_048_577));
            else TimeUnit.SECONDS.sleep(30);
        }
    }
    private static final class TrackingBoundary implements DockerCliNetworkControlPlane.ProcessBoundary {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicReference<Process> process = new AtomicReference<>();
        final AtomicReference<Thread> reader = new AtomicReference<>();
        public void afterReaderStarted(Process value, Thread thread) {
            process.set(value); reader.set(thread); started.countDown();
        }
    }
    private static final class ReadFailureProcess extends Process {
        private final InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("deterministic read failure"); }
        };
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 1; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 1; }
        @Override public void destroy() {}
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
        @Override public long pid() { return 4242; }
    }

    private static Harness harness() throws Exception {
        var cleanup = new SingleOwnerCleanup(ContainmentDeadline.after(Duration.ofSeconds(5)));
        Field field = SingleOwnerCleanup.class.getDeclaredField("proofIssuer"); field.setAccessible(true);
        var issuer = (SingleOwnerCleanup.ProofIssuer) field.get(cleanup);
        var runner = new RecordingRunner();
        return new Harness(issuer, runner, DockerCliNetworkControlPlane.testing(issuer, runner),
                ContainmentDeadline.after(Duration.ofSeconds(5)));
    }
    private record Harness(SingleOwnerCleanup.ProofIssuer issuer, RecordingRunner runner,
            DockerCliNetworkControlPlane adapter, ContainmentDeadline deadline) {}
    private static DockerCliNetworkControlPlane.CommandResult ok(String response) { return okExit(0, response); }
    private static DockerCliNetworkControlPlane.CommandResult okExit(int exit, String response) {
        return new DockerCliNetworkControlPlane.CommandResult(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, exit, response, true);
    }
    private static final class RecordingRunner implements DockerCliNetworkControlPlane.CommandRunner {
        final ArrayDeque<DockerCliNetworkControlPlane.CommandResult> outcomes = new ArrayDeque<>();
        final List<List<String>> operations = new ArrayList<>();
        boolean continuityEnabled = true;
        void operation(DockerCliNetworkControlPlane.CommandResult result) { outcomes.add(result); }
        public DockerCliNetworkControlPlane.CommandResult run(List<String> argv, ContainmentDeadline deadline) {
            if (argv.size() > 1 && (argv.get(1).equals("context") || argv.get(1).equals("info"))) {
                if (!continuityEnabled) return new DockerCliNetworkControlPlane.CommandResult(
                        DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                        DockerControlPlane.Completion.SPAWN_FAILED, null, null, true);
                return ok(argv.get(1).equals("info") ? "engine-a"
                        : argv.contains("show") ? "default" : "npipe://engine");
            }
            operations.add(List.copyOf(argv)); return outcomes.remove();
        }
    }
}
