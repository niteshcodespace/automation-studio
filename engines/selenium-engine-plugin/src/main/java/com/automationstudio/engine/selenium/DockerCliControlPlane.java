package com.automationstudio.engine.selenium;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** Production Docker CLI adapter. All argv construction is closed inside this class. */
final class DockerCliControlPlane implements DockerControlPlane {
    private static final int MAX_RESPONSE = 1_048_576;
    private final AtomicLong observations = new AtomicLong();
    private final SingleOwnerCleanup.ProofIssuer authority;

    private DockerCliControlPlane(SingleOwnerCleanup.ProofIssuer authority) {
        this.authority = java.util.Objects.requireNonNull(authority, "authority");
    }

    static DockerCliControlPlane trusted(SingleOwnerCleanup.ProofIssuer authority) {
        return new DockerCliControlPlane(authority);
    }

    @Override public DockerTransportOutcome resolveImage(String imageReference,
            ContainmentDeadline deadline) {
        if (imageReference == null || !imageReference.matches("(?:[a-zA-Z0-9._/-]+@)?sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Image reference is not immutable");
        return execute(List.of("docker", "image", "inspect", "--format", "{{.Id}}", imageReference),
                deadline, false, null);
    }

    @Override public DockerTransportOutcome create(DockerCreateRequest request,
            ContainmentDeadline deadline) {
        DockerResourceSpec spec = request.expected();
        SeleniumContainmentLimits limits = request.limits();
        requireApprovedWorker(request, spec);
        var command = new ArrayList<>(List.of("docker", "create",
                "--label", "automation-studio.execution=" + spec.executionId(),
                "--label", "automation-studio.role=" + spec.role(),
                "--label", "automation-studio.attempt=" + spec.attemptNonce(),
                "--network", spec.networkMode(), "--user", spec.user()));
        if (spec.readOnlyRootfs()) command.add("--read-only");
        command.addAll(List.of("--restart", restart(spec), "--pid", spec.pidMode(),
                "--ipc", spec.ipcMode(), "--pids-limit", String.valueOf(limits.pids()),
                "--memory", String.valueOf(limits.memoryBytes()), "--memory-swap",
                String.valueOf(limits.memoryBytes()), "--cpus", String.valueOf(limits.cpus()),
                "--tmpfs", "/work/runtime:rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size="
                        + limits.tmpfsBytes(),
                "--tmpfs", "/work/tmp:rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=8388608",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                "--security-opt", "seccomp=builtin", "--env", "LANG=C.UTF-8"));
        command.add(spec.imageReference()); command.addAll(spec.command());
        return execute(command, deadline, false, null);
    }

    @Override public DockerTransportOutcome inspectExactId(String immutableId,
            ContainmentDeadline deadline) {
        validId(immutableId);
        return execute(List.of("docker", "container", "inspect", immutableId), deadline, false, null);
    }

    @Override public DockerTransportOutcome lookupAttempt(DockerAttemptLookup lookup,
            ContainmentDeadline deadline) {
        return execute(List.of("docker", "container", "ls", "--all", "--no-trunc",
                "--filter", "label=automation-studio.execution=" + lookup.executionId(),
                "--filter", "label=automation-studio.role=" + lookup.role(),
                "--filter", "label=automation-studio.attempt=" + lookup.attemptNonce(),
                "--format", "{{.ID}}"), deadline, false, null);
    }

    @Override public DockerTransportOutcome removeExactId(String immutableId,
            ContainmentDeadline deadline) {
        validId(immutableId);
        return execute(List.of("docker", "rm", "--force", immutableId), deadline, false, null);
    }

    @Override public DockerTransportOutcome inspectExactIdAbsence(String immutableId,
            ContainmentDeadline deadline) {
        validId(immutableId);
        return execute(List.of("docker", "container", "inspect", immutableId), deadline, true, immutableId);
    }

    private DockerTransportOutcome execute(List<String> argv, ContainmentDeadline deadline,
            boolean classifyAbsence, String exactId) {
        long revision = observations.incrementAndGet();
        if (deadline.expired()) return outcome(Dispatch.DEFINITELY_NOT_DISPATCHED,
                Completion.DEADLINE_EXHAUSTED, null, null, true, revision, Semantic.NONE, null);
        DockerDaemonIdentity daemon = continuity(deadline);
        if (daemon == null) return outcome(Dispatch.DEFINITELY_NOT_DISPATCHED,
                Completion.PROTOCOL_FAILED, null, null, false, revision, Semantic.NONE, null);
        final Process process;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (Exception failure) {
            return outcome(Dispatch.DEFINITELY_NOT_DISPATCHED, Completion.SPAWN_FAILED,
                    null, null, true, revision, Semantic.NONE, daemon);
        }
        var output = new ByteArrayOutputStream(); var overflow = new AtomicBoolean();
        var readFailure = new AtomicReference<Throwable>();
        Thread reader = Thread.ofPlatform().daemon().start(
                () -> read(process.getInputStream(), output, overflow, readFailure));
        try {
            if (!process.waitFor(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                return outcome(Dispatch.MAY_HAVE_DISPATCHED, Completion.TIMED_OUT,
                        null, null, false, revision, Semantic.NONE, daemon);
            }
            long readerBudget = deadline.remainingNanos();
            if (readerBudget > 0)
                reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(readerBudget)));
            if (reader.isAlive()) return outcome(Dispatch.DEFINITELY_DISPATCHED,
                    Completion.TIMED_OUT, process.exitValue(), null, false, revision, Semantic.NONE, daemon);
            if (readFailure.get() != null) return outcome(Dispatch.DEFINITELY_DISPATCHED,
                    Completion.READ_FAILED, process.exitValue(), null, false, revision, Semantic.NONE, daemon);
            if (overflow.get()) return outcome(Dispatch.DEFINITELY_DISPATCHED,
                    Completion.OUTPUT_OVERFLOW, process.exitValue(), null, false, revision, Semantic.NONE, daemon);
            String response = output.toString(StandardCharsets.UTF_8).strip();
            Semantic semantic = Semantic.NONE;
            if (classifyAbsence && process.exitValue() != 0 && exactNotFound(response, exactId)) {
                semantic = Semantic.EXACT_ID_NOT_FOUND;
            }
            Completion completion = process.exitValue() == 0 || semantic == Semantic.EXACT_ID_NOT_FOUND
                    ? Completion.COMPLETED : classifyFailure(response);
            return outcome(Dispatch.DEFINITELY_DISPATCHED, completion,
                    process.exitValue(), response, true, revision, semantic, daemon);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); process.destroyForcibly();
            return outcome(Dispatch.MAY_HAVE_DISPATCHED, Completion.INTERRUPTED,
                    null, null, false, revision, Semantic.NONE, daemon);
        }
    }

    private static void read(InputStream source, ByteArrayOutputStream target, AtomicBoolean overflow,
            AtomicReference<Throwable> readFailure) {
        try (source) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = source.read(buffer)) >= 0) {
                if (target.size() + count > MAX_RESPONSE) { overflow.set(true); return; }
                target.write(buffer, 0, count);
            }
        } catch (Exception failure) { readFailure.set(failure); }
    }

    private static DockerTransportOutcome outcome(Dispatch dispatch, Completion completion,
            Integer exit, String response, boolean complete, long revision, Semantic semantic) {
        return outcome(dispatch, completion, exit, response, complete, revision, semantic, null);
    }
    private static DockerTransportOutcome outcome(Dispatch dispatch, Completion completion,
            Integer exit, String response, boolean complete, long revision, Semantic semantic,
            DockerDaemonIdentity daemon) {
        return new DockerTransportOutcome(dispatch, completion, exit, response, complete, revision,
                semantic, daemon);
    }
    private static String restart(DockerResourceSpec spec) {
        return spec.restartMaximumRetryCount() == 0 ? spec.restartPolicy()
                : spec.restartPolicy() + ":" + spec.restartMaximumRetryCount();
    }
    private static boolean exactNotFound(String response, String id) {
        return response.equals("Error: No such object: " + id)
                || response.equals("[]\nError: No such object: " + id)
                || response.equals("Error response from daemon: No such container: " + id);
    }
    private static Completion classifyFailure(String response) {
        String lower = response.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("error response from daemon") || lower.contains("cannot connect to the docker daemon")
                ? Completion.DAEMON_FAILED : Completion.PROTOCOL_FAILED;
    }

    private DockerDaemonIdentity continuity(ContainmentDeadline deadline) {
        try {
            String context = probe(List.of("docker", "context", "show"), deadline);
            String endpoint = probe(List.of("docker", "context", "inspect", "--format",
                    "{{.Endpoints.docker.Host}}", context), deadline);
            String engine = probe(List.of("docker", "info", "--format", "{{.ID}}"), deadline);
            return new DockerDaemonIdentity(endpoint, context, engine);
        } catch (RuntimeException failure) { return null; }
    }
    private static String probe(List<String> argv, ContainmentDeadline deadline) {
        if (deadline.expired()) throw new IllegalStateException("deadline");
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            if (!process.waitFor(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly(); throw new IllegalStateException("probe timeout");
            }
            byte[] bytes = process.getInputStream().readNBytes(4097);
            if (process.exitValue() != 0 || bytes.length > 4096) throw new IllegalStateException("probe failed");
            String value = new String(bytes, StandardCharsets.UTF_8).strip();
            if (value.isBlank()) throw new IllegalStateException("empty probe");
            return value;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void requireApprovedWorker(DockerCreateRequest request, DockerResourceSpec spec) {
        if (request.issuer() != authority || spec.role() != ContainmentResourceRole.WORKER
                || !spec.imageReference().matches("(?:[a-zA-Z0-9._/-]+@)?sha256:[a-f0-9]{64}")
                || !spec.imageIdentity().matches("sha256:[a-f0-9]{64}")
                || !"none".equals(spec.networkMode()) || spec.privileged()
                || !"private".equals(spec.pidMode()) || !"private".equals(spec.ipcMode())
                || !"10001:10001".equals(spec.user()) || !spec.readOnlyRootfs()
                || !spec.entrypoint().equals(List.of("/opt/java/openjdk/bin/java", "-Xms16m",
                        "-Xmx64m", "-Djava.io.tmpdir=/work/tmp", "-jar", "/opt/worker/worker.jar"))
                || !"no".equals(spec.restartPolicy()) || spec.restartMaximumRetryCount() != 0
                || !spec.isolationTuples().equals(List.of("cap-drop=ALL",
                        "security-opt=no-new-privileges:true", "security-opt=seccomp=builtin",
                        "tmpfs=/work/runtime=rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size="
                                + request.limits().tmpfsBytes(),
                        "tmpfs=/work/tmp=rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=8388608"))
                || !spec.environment().equals(List.of("LANG=C.UTF-8"))
                || spec.command().size() != 1 || !spec.command().get(0).equals(spec.executionId().toString()))
            throw new SecurityException("Docker create request is outside approved worker policy");
    }
    private static void validId(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid immutable Docker ID");
    }
}
