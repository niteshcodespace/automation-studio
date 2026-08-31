package com.automationstudio.engine.selenium;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Production D2b Docker CLI adapter with a fixed network-only argv surface. */
final class DockerCliNetworkControlPlane implements DockerNetworkControlPlane {
    private static final int MAX_RESPONSE = 1_048_576;
    private final SingleOwnerCleanup.ProofIssuer authority;
    private final CommandRunner runner;
    private final AtomicLong observations = new AtomicLong();

    private DockerCliNetworkControlPlane(SingleOwnerCleanup.ProofIssuer authority) {
        this(authority, DockerCliNetworkControlPlane::runProcess);
    }
    private DockerCliNetworkControlPlane(SingleOwnerCleanup.ProofIssuer authority, CommandRunner runner) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.runner = Objects.requireNonNull(runner, "runner");
    }
    static DockerCliNetworkControlPlane trusted(SingleOwnerCleanup.ProofIssuer authority) {
        return new DockerCliNetworkControlPlane(authority);
    }
    static DockerCliNetworkControlPlane testing(SingleOwnerCleanup.ProofIssuer authority,
            CommandRunner runner) {
        return new DockerCliNetworkControlPlane(authority, runner);
    }

    @FunctionalInterface interface CommandRunner {
        CommandResult run(List<String> argv, ContainmentDeadline deadline);
    }
    @FunctionalInterface interface ProcessStarter {
        Process start(List<String> argv) throws IOException;
    }
    interface ProcessBoundary {
        ProcessBoundary NONE = new ProcessBoundary() {};
        default void afterReaderStarted(Process process, Thread reader) {}
    }
    record CommandResult(DockerControlPlane.Dispatch dispatch,
            DockerControlPlane.Completion completion, Integer exitStatus, String response,
            boolean responseComplete, boolean cleanupComplete) {
        CommandResult {
            Objects.requireNonNull(dispatch); Objects.requireNonNull(completion);
            if (!cleanupComplete) responseComplete = false;
            if (response != null && !responseComplete) response = null;
        }
        CommandResult(DockerControlPlane.Dispatch dispatch, DockerControlPlane.Completion completion,
                Integer exitStatus, String response, boolean responseComplete) {
            this(dispatch, completion, exitStatus, response, responseComplete, true);
        }
    }

    @Override public DockerControlPlane.DockerTransportOutcome create(NetworkCreateRequest request,
            ContainmentDeadline deadline) {
        if (request.issuer() != authority) throw new SecurityException("Foreign network authority");
        DockerNetworkSpec spec = request.expected();
        var argv = new ArrayList<>(List.of("docker", "network", "create", "--driver", "bridge",
                "--scope", "local", "--internal=false", "--attachable=false", "--ingress=false",
                "--config-only=false", "--ipv4=true", "--ipv6=false", "--ipam-driver", "default"));
        spec.labels().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> argv.addAll(List.of("--label", entry.getKey() + "=" + entry.getValue())));
        argv.add(spec.diagnosticName()); return execute(argv, deadline, false, null);
    }

    @Override public DockerControlPlane.DockerTransportOutcome lookupAttempt(NetworkAttemptLookup lookup,
            ContainmentDeadline deadline) {
        var argv = new ArrayList<>(List.of("docker", "network", "ls", "--no-trunc"));
        filters(lookup).forEach(filter -> argv.addAll(List.of("--filter", "label=" + filter)));
        argv.addAll(List.of("--format", "{{.ID}}")); return execute(argv, deadline, false, null);
    }

    @Override public DockerControlPlane.DockerTransportOutcome inspectExactId(String id,
            ContainmentDeadline deadline) {
        validId(id); return execute(List.of("docker", "network", "inspect", id), deadline, false, null);
    }
    @Override public DockerControlPlane.DockerTransportOutcome removeExactId(String id,
            ContainmentDeadline deadline) {
        validId(id); return execute(List.of("docker", "network", "rm", id), deadline, false, null);
    }
    @Override public DockerControlPlane.DockerTransportOutcome inspectExactIdAbsence(String id,
            ContainmentDeadline deadline) {
        validId(id); return execute(List.of("docker", "network", "inspect", id), deadline, true, id);
    }

    private static List<String> filters(NetworkAttemptLookup lookup) {
        return List.of(DockerNetworkSpec.EXECUTION + "=" + lookup.executionId(),
                DockerNetworkSpec.ROLE + "=" + DockerNetworkSpec.ROLE_VALUE,
                DockerNetworkSpec.REVISION + "=" + lookup.revision(),
                DockerNetworkSpec.ATTEMPT + "=" + lookup.attemptNonce(),
                DockerNetworkSpec.DEADLINE + "=" + lookup.deadlineCorrelation());
    }

    private DockerControlPlane.DockerTransportOutcome execute(List<String> argv,
            ContainmentDeadline deadline, boolean classifyAbsence, String exactId) {
        Objects.requireNonNull(deadline, "deadline"); long revision = observations.incrementAndGet();
        if (deadline.expired()) return outcome(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.DEADLINE_EXHAUSTED, null, null, true, revision,
                DockerControlPlane.Semantic.NONE, null);
        DockerControlPlane.DockerDaemonIdentity daemon = continuity(deadline);
        if (daemon == null) return outcome(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.PROTOCOL_FAILED, null, null, false, revision,
                DockerControlPlane.Semantic.NONE, null);
        CommandResult raw = runner.run(List.copyOf(argv), deadline);
        if (raw.dispatch() == DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED)
            return outcome(raw.dispatch(), raw.completion(), raw.exitStatus(), raw.response(),
                    raw.responseComplete(), revision, DockerControlPlane.Semantic.NONE, null);
        DockerControlPlane.Semantic semantic = DockerControlPlane.Semantic.NONE;
        if (classifyAbsence && raw.completion() == DockerControlPlane.Completion.COMPLETED
                && raw.exitStatus() != null && raw.exitStatus() != 0
                && exactNotFound(raw.response(), exactId))
            semantic = DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND;
        DockerControlPlane.Completion completion = raw.completion();
        if (completion == DockerControlPlane.Completion.COMPLETED && raw.exitStatus() != null
                && raw.exitStatus() != 0 && semantic == DockerControlPlane.Semantic.NONE)
            completion = classifyFailure(raw.response());
        return outcome(raw.dispatch(), completion, raw.exitStatus(), raw.response(),
                raw.responseComplete(), revision, semantic, daemon);
    }

    private DockerControlPlane.DockerDaemonIdentity continuity(ContainmentDeadline deadline) {
        String endpoint = probe(List.of("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"), deadline);
        String context = probe(List.of("docker", "context", "show"), deadline);
        String engine = probe(List.of("docker", "info", "--format", "{{.ID}}"), deadline);
        try { return new DockerControlPlane.DockerDaemonIdentity(endpoint, context, engine); }
        catch (RuntimeException failure) { return null; }
    }
    private String probe(List<String> argv, ContainmentDeadline deadline) {
        if (deadline.expired()) return null;
        CommandResult result = runner.run(argv, deadline);
        String value = result.response() == null ? null : result.response().strip();
        return result.dispatch() == DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED
                && result.completion() == DockerControlPlane.Completion.COMPLETED
                && Integer.valueOf(0).equals(result.exitStatus()) && result.responseComplete()
                && value != null && !value.isBlank() && value.length() <= 4096 ? value : null;
    }
    private static CommandResult runProcess(List<String> argv, ContainmentDeadline deadline) {
        return runProcess(argv, deadline,
                value -> new ProcessBuilder(value).redirectErrorStream(true).start(), ProcessBoundary.NONE);
    }
    private static CommandResult runProcess(List<String> argv, ContainmentDeadline deadline,
            ProcessStarter starter, ProcessBoundary boundary) {
        final Process process;
        try { process = starter.start(argv); }
        catch (Exception failure) { return command(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.SPAWN_FAILED, null, null, true); }
        var bytes = new ByteArrayOutputStream(); var overflow = new AtomicBoolean();
        var readFailure = new AtomicReference<Throwable>();
        Thread reader = Thread.ofPlatform().daemon().name("as-d2b-docker-reader-" + process.pid())
                .start(() -> read(process.getInputStream(), bytes, overflow, readFailure));
        boundary.afterReaderStarted(process, reader);
        try {
            if (!process.waitFor(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                boolean cleaned = terminate(process, reader);
                return command(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                        DockerControlPlane.Completion.TIMED_OUT, null, null, false, cleaned);
            }
            join(reader, deadline);
            if (reader.isAlive()) { boolean cleaned = terminate(process, reader);
                return command(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                        DockerControlPlane.Completion.TIMED_OUT, process.exitValue(), null, false, cleaned); }
            if (readFailure.get() != null) return command(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                    DockerControlPlane.Completion.READ_FAILED, process.exitValue(), null, false);
            if (overflow.get()) return command(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                    DockerControlPlane.Completion.OUTPUT_OVERFLOW, process.exitValue(), null, false);
            return command(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                    DockerControlPlane.Completion.COMPLETED, process.exitValue(),
                    bytes.toString(StandardCharsets.UTF_8).strip(), true);
        } catch (InterruptedException failure) {
            boolean cleaned = terminate(process, reader); Thread.currentThread().interrupt();
            return command(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                    DockerControlPlane.Completion.INTERRUPTED, null, null, false, cleaned);
        }
    }
    private static void join(Thread reader, ContainmentDeadline deadline) throws InterruptedException {
        long remaining = deadline.remainingNanos();
        if (remaining > 0) reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
    private static boolean terminate(Process process, Thread reader) {
        boolean interrupted = Thread.interrupted();
        process.destroyForcibly(); close(process);
        try {
            process.waitFor(1, TimeUnit.SECONDS); reader.join(1000);
        } catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
        return !process.isAlive() && !reader.isAlive();
    }
    private static void close(Process process) {
        try { process.getInputStream().close(); } catch (Exception ignored) { }
    }
    private static CommandResult command(DockerControlPlane.Dispatch dispatch,
            DockerControlPlane.Completion completion, Integer exit, String response, boolean complete) {
        return new CommandResult(dispatch, completion, exit, response, complete);
    }
    private static CommandResult command(DockerControlPlane.Dispatch dispatch,
            DockerControlPlane.Completion completion, Integer exit, String response, boolean complete,
            boolean cleanupComplete) {
        return new CommandResult(dispatch, completion, exit, response, complete, cleanupComplete);
    }
    private static void read(InputStream source, ByteArrayOutputStream target, AtomicBoolean overflow,
            AtomicReference<Throwable> failure) {
        try (source) { byte[] buffer = new byte[8192]; int count;
            while ((count = source.read(buffer)) >= 0) {
                if (target.size() + count > MAX_RESPONSE) { overflow.set(true); return; }
                target.write(buffer, 0, count);
            }
        } catch (Exception problem) { failure.set(problem); }
    }
    private static DockerControlPlane.Completion classifyFailure(String response) {
        String lower = response == null ? "" : response.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("error response from daemon") || lower.contains("cannot connect to the docker daemon")
                ? DockerControlPlane.Completion.DAEMON_FAILED
                : DockerControlPlane.Completion.PROTOCOL_FAILED;
    }
    private static boolean exactNotFound(String response, String id) {
        if (response == null || id == null) return false;
        String quoted = java.util.regex.Pattern.quote(id);
        return java.util.regex.Pattern.compile("(?is)^\\s*(?:error response from daemon:\\s*)?(?:no such network:\\s*"
                + quoted + "|network\\s+" + quoted + "\\s+not found)\\s*$").matcher(response).matches();
    }
    private static DockerControlPlane.DockerTransportOutcome outcome(
            DockerControlPlane.Dispatch dispatch, DockerControlPlane.Completion completion,
            Integer exit, String response, boolean complete, long revision,
            DockerControlPlane.Semantic semantic, DockerControlPlane.DockerDaemonIdentity daemon) {
        return new DockerControlPlane.DockerTransportOutcome(dispatch, completion, exit, response,
                complete, revision, semantic, daemon);
    }
    private static void validId(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid immutable network ID");
    }
}
