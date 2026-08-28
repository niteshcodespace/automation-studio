package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DockerAcquisitionAuthorityTest {
    private static final String ID = "a".repeat(64);
    private static final String IMAGE = "sha256:" + "b".repeat(64);

    @Test void a01A41A42VerifiedHandoffIsRequiredForOwnedPresent() {
        var guardedDocker = new FakeDocker(); var guarded = dockerCleanup(
                ContainmentDeadline.after(Duration.ofSeconds(1)), guardedDocker);
        var guardedOwner = guarded.claim(); var guardedWorker = guarded.resource(
                guardedOwner, ContainmentResourceRole.WORKER);
        assertThrows(IllegalStateException.class, () -> guardedWorker.beginAcquisition(guardedOwner));

        Fixture fixture = fixture();
        fixture.docker.create.add(ok(ID, 1)); fixture.docker.inspect.add(ok(fixture.inspect(ID), 2));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.ACQUIRED,
                fixture.worker.acquireDocker(fixture.owner, fixture.attempt));
        var state = fixture.owner.execute(() -> new SingleOwnerCleanup.TerminalOutcome(
                ExecutionOutcome.FAILED, ContainmentCode.UNSAFE, AttachmentState.NOT_STARTED,
                DependencyEvidence.SATISFIED));
        assertInstanceOf(ResourceDisposition.OwnedPresent.class, state.report().worker());

        var untrusted = new SingleOwnerCleanup(ContainmentDeadline.after(Duration.ofSeconds(1)));
        var owner = untrusted.claim(); var worker = untrusted.resource(owner, ContainmentResourceRole.WORKER);
        var attempt = worker.beginAcquisition(owner);
        worker.recordAcquisition(owner, attempt, new AcquisitionResult.CreatedAndOwned(
                new ContainmentIdentity(ID), new ContainmentEvidence("caller")));
        assertFalse(owner.execute(DockerAcquisitionAuthorityTest::safeOutcome).safe());
    }

    @Test void a04A16A48A58A59OnlyTrustedPreDispatchProofClosesSafely() {
        Fixture before = fixture(); before.docker.create.add(outcome(
                DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.SPAWN_FAILED, null, null, true, 1));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.DEFINITE_NO_SIDE_EFFECT,
                before.worker.acquireDocker(before.owner, before.attempt));
        assertTrue(before.owner.execute(DockerAcquisitionAuthorityTest::safeOutcome).safe());

        Fixture after = fixture(); after.docker.create.add(outcome(
                DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                DockerControlPlane.Completion.TIMED_OUT, null, null, false, 1));
        after.docker.lookup.add(ok("", 2));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.AMBIGUOUS,
                after.worker.acquireDocker(after.owner, after.attempt));
        assertFalse(after.owner.execute(DockerAcquisitionAuthorityTest::safeOutcome).safe());
    }

    @Test void a17A47A61A64NonceAndEveryCanonicalFieldMustMatchOneAtomicInspect() {
        Fixture correct = fixture(); correct.docker.create.add(ok(ID, 1));
        correct.docker.inspect.add(ok(correct.inspect(ID), 2));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.ACQUIRED,
                correct.worker.acquireDocker(correct.owner, correct.attempt));

        Fixture wrongNonce = fixture(); wrongNonce.docker.create.add(ok(ID, 1));
        wrongNonce.docker.inspect.add(ok(wrongNonce.inspect(ID).replace(
                wrongNonce.attempt.attemptNonce(), "c".repeat(64)), 2));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.AMBIGUOUS,
                wrongNonce.worker.acquireDocker(wrongNonce.owner, wrongNonce.attempt));

        Fixture malformed = fixture(); malformed.docker.create.add(ok(ID, 1));
        malformed.docker.inspect.add(ok("[{}]", 2));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.AMBIGUOUS,
                malformed.worker.acquireDocker(malformed.owner, malformed.attempt));
    }

    @Test void a56DuplicateIsIdempotentOnlyForCanonicalEqualProvenance() {
        Fixture fixture = fixture(); fixture.docker.create.add(ok(ID, 1));
        fixture.docker.inspect.add(ok(fixture.inspect(ID), 2));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.ACQUIRED,
                fixture.worker.acquireDocker(fixture.owner, fixture.attempt));
        fixture.docker.inspect.add(ok(fixture.inspect(ID), 3));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.IDEMPOTENT,
                fixture.worker.recordLateDockerAcquisition(fixture.owner, fixture.attempt, ID));
        fixture.docker.inspect.add(ok(fixture.inspect(ID).replace("10001:10001", "0:0"), 4));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.CONFLICT,
                fixture.worker.recordLateDockerAcquisition(fixture.owner, fixture.attempt, ID));
    }

    @Test void a21A22LateVerifiedOwnershipAfterTerminalOnlyDegradesSharedAuthority() {
        Fixture fixture = fixture();
        fixture.docker.create.add(outcome(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.SPAWN_FAILED, null, null, true, 1));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.DEFINITE_NO_SIDE_EFFECT,
                fixture.worker.acquireDocker(fixture.owner, fixture.attempt));
        var state = fixture.owner.execute(DockerAcquisitionAuthorityTest::safeOutcome);
        assertTrue(state.safe());
        fixture.docker.inspect.add(ok(fixture.inspect(ID), 2));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.LATE_RETAINED,
                fixture.worker.recordLateDockerAcquisition(fixture.owner, fixture.attempt, ID));
        assertFalse(state.safe()); assertTrue(state.compromised());
        assertSame(state, assertDoesNotThrow(fixture.cleanup.claim().completion()::await));
    }

    @Test void a43A44CrossCleanupAndCrossRoleAttemptReplayAreRejected() {
        Fixture first = fixture(); Fixture second = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> second.worker.acquireDocker(second.owner, first.attempt));
        var network = first.cleanup.resource(first.owner, ContainmentResourceRole.NETWORK);
        assertThrows(IllegalArgumentException.class,
                () -> network.recordLateDockerAcquisition(first.owner, first.attempt, ID));
    }

    @Test void a61ToA64CanonicalFingerprintLocksEveryFieldAndOrdering() {
        Fixture fixture = fixture(); DockerResourceFingerprint expected = DockerResourceTemplate
                .worker(IMAGE, SeleniumContainmentLimits.defaults())
                .bind(fixture.execution, ContainmentResourceRole.WORKER,
                        fixture.attempt.attemptNonce()).fingerprint(ID);
        assertNotEquals(expected, expected.withContainerId("f".repeat(64)));
        assertNotEquals(expected, new DockerResourceFingerprint(ID, UUID.randomUUID(), expected.role(),
                expected.attemptNonce(), expected.imageIdentity(), expected.entrypoint(), expected.command(),
                expected.user(), expected.readOnlyRootfs(), expected.restartPolicy(),
                expected.restartMaximumRetryCount(), expected.networkMode(), expected.privileged(),
                expected.pidMode(), expected.ipcMode(), expected.isolationTuples(), expected.environment()));
        assertNotEquals(expected, new DockerResourceFingerprint(ID, expected.executionId(), expected.role(),
                expected.attemptNonce(), "sha256:" + "e".repeat(64), expected.entrypoint(), expected.command(),
                expected.user(), expected.readOnlyRootfs(), expected.restartPolicy(),
                expected.restartMaximumRetryCount(), expected.networkMode(), expected.privileged(),
                expected.pidMode(), expected.ipcMode(), expected.isolationTuples(), expected.environment()));
        var reversed = new java.util.ArrayList<>(expected.isolationTuples());
        java.util.Collections.reverse(reversed);
        var canonical = new DockerResourceFingerprint(ID, expected.executionId(), expected.role(),
                expected.attemptNonce(), expected.imageIdentity(), expected.entrypoint(), expected.command(),
                expected.user(), expected.readOnlyRootfs(), expected.restartPolicy(),
                expected.restartMaximumRetryCount(), expected.networkMode(), expected.privileged(),
                expected.pidMode(), expected.ipcMode(), reversed, expected.environment());
        assertEquals(expected, canonical);
        assertThrows(IllegalArgumentException.class, () -> DockerInspectParser.parse(
                fixture.inspect(ID).replace("\"Cmd\":[\"" + fixture.execution + "\"]", "\"Cmd\":null")));
        assertThrows(IllegalArgumentException.class, () -> DockerInspectParser.parse(
                fixture.inspect(ID).replaceFirst("\"Id\":\"[^\"]+\"", "\"Id\":\"" + ID + "\",\"Id\":\"" + ID + "\"")));
    }

    @Test void a34A54CleanupOwnedExactIdAbsenceIsNonInjectableAndDeadlineBound() {
        Fixture fixture = fixture(); fixture.docker.create.add(ok(ID, 1));
        fixture.docker.inspect.add(ok(fixture.inspect(ID), 2));
        fixture.worker.acquireDocker(fixture.owner, fixture.attempt);
        fixture.docker.absence.add(new DockerControlPlane.DockerTransportOutcome(
                DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, 1, "", true, 3,
                DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND,
                DockerControlPlane.DockerDaemonIdentity.testDefault()));
        var state = fixture.owner.execute(() -> {
            var field = SingleOwnerCleanup.ResourceTransition.class.getDeclaredField("owned");
            field.setAccessible(true);
            var owned = (ResourceDisposition.OwnedPresent) field.get(fixture.worker);
            fixture.worker.verifyAbsent(fixture.owner, owned);
            return safeOutcome();
        });
        assertTrue(state.safe());
        assertTrue(java.util.Arrays.stream(SingleOwnerCleanup.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().contains("Absence"))
                .allMatch(type -> Modifier.isPrivate(type.getModifiers())));
        assertTrue(fixture.docker.deadlines.stream().allMatch(value -> value == fixture.deadline));
    }

    @Test void s06S07S08S10ProductionSurfaceStaysClosed() {
        assertTrue(java.util.Arrays.stream(DockerControlPlane.class.getMethods())
                .noneMatch(method -> java.util.Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> type == String[].class || type == java.time.Duration.class)));
        assertTrue(java.util.Arrays.stream(ForeignExclusionProof.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(java.util.Arrays.stream(DockerResourceTemplate.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(java.util.Arrays.stream(SingleOwnerCleanup.class.getDeclaredConstructors())
                .filter(constructor -> java.util.Arrays.asList(constructor.getParameterTypes())
                        .contains(DockerControlPlane.class))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(SeleniumWorkerCommand.create(UUID.randomUUID(),
                "example/image@sha256:" + "d".repeat(64), SeleniumContainmentLimits.defaults())
                .containsAll(List.of("--network", "none")));
    }

    private static Fixture fixture() {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(5)); var docker = new FakeDocker();
        var cleanup = dockerCleanup(deadline, docker);
        var owner = cleanup.claim();
        var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER); UUID execution = UUID.randomUUID();
        var template = DockerResourceTemplate.worker(IMAGE, SeleniumContainmentLimits.defaults());
        var attempt = worker.beginDockerAcquisition(owner, execution, template);
        return new Fixture(deadline, docker, cleanup, owner, worker, attempt, execution);
    }

    private static SingleOwnerCleanup dockerCleanup(ContainmentDeadline deadline,
            DockerControlPlane docker) {
        try {
            var constructor = SingleOwnerCleanup.class.getDeclaredConstructor(ContainmentDeadline.class,
                    SingleOwnerCleanup.AwaitBoundary.class, boolean.class, DockerControlPlane.class);
            constructor.setAccessible(true);
            return constructor.newInstance(deadline, SingleOwnerCleanup.AwaitBoundary.NONE, false, docker);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private record Fixture(ContainmentDeadline deadline, FakeDocker docker, SingleOwnerCleanup cleanup,
            SingleOwnerCleanup.Claim owner, SingleOwnerCleanup.ResourceTransition worker,
            SingleOwnerCleanup.AcquisitionAttempt attempt, UUID execution) {
        String inspect(String id) {
            return """
                    [{"Id":"%s","Image":"%s","Config":{"Labels":{"automation-studio.execution":"%s","automation-studio.role":"WORKER","automation-studio.attempt":"%s"},"Entrypoint":["/opt/java/openjdk/bin/java","-Xms16m","-Xmx64m","-Djava.io.tmpdir=/work/tmp","-jar","/opt/worker/worker.jar"],"Cmd":["%s"],"User":"10001:10001","Env":["PATH=/usr/bin","LANG=C.UTF-8"]},"HostConfig":{"ReadonlyRootfs":true,"RestartPolicy":{"Name":"no","MaximumRetryCount":0},"NetworkMode":"none","Privileged":false,"PidMode":"private","IpcMode":"private","CapAdd":[],"CapDrop":["ALL"],"Devices":[],"Binds":[],"Mounts":[],"Tmpfs":{"/work/runtime":"rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=33554432","/work/tmp":"rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=8388608"},"SecurityOpt":["no-new-privileges:true","seccomp=builtin"]}}]
                    """.formatted(id, IMAGE, execution, attempt.attemptNonce(), execution);
        }
    }

    private static DockerControlPlane.DockerTransportOutcome ok(String response, long revision) {
        return outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, 0, response, true, revision);
    }
    private static DockerControlPlane.DockerTransportOutcome outcome(
            DockerControlPlane.Dispatch dispatch, DockerControlPlane.Completion completion,
            Integer exit, String response, boolean complete, long revision) {
        return new DockerControlPlane.DockerTransportOutcome(dispatch, completion, exit,
                response, complete, revision);
    }
    private static SingleOwnerCleanup.TerminalOutcome safeOutcome() {
        return new SingleOwnerCleanup.TerminalOutcome(ExecutionOutcome.FAILED,
                ContainmentCode.ABSENT, AttachmentState.NOT_STARTED, DependencyEvidence.SATISFIED);
    }

    private static final class FakeDocker implements DockerControlPlane {
        final ArrayDeque<DockerTransportOutcome> create = new ArrayDeque<>(), inspect = new ArrayDeque<>(),
                lookup = new ArrayDeque<>(), remove = new ArrayDeque<>(), absence = new ArrayDeque<>();
        final java.util.ArrayList<ContainmentDeadline> deadlines = new java.util.ArrayList<>();
        private DockerTransportOutcome next(ArrayDeque<DockerTransportOutcome> values,
                ContainmentDeadline deadline) { deadlines.add(deadline); return values.remove(); }
        public DockerTransportOutcome resolveImage(String reference, ContainmentDeadline deadline) { return next(inspect, deadline); }
        public DockerTransportOutcome create(DockerCreateRequest request, ContainmentDeadline deadline) { return next(create, deadline); }
        public DockerTransportOutcome inspectExactId(String id, ContainmentDeadline deadline) { return next(inspect, deadline); }
        public DockerTransportOutcome lookupAttempt(DockerAttemptLookup lookup, ContainmentDeadline deadline) { return next(this.lookup, deadline); }
        public DockerTransportOutcome removeExactId(String id, ContainmentDeadline deadline) { return next(remove, deadline); }
        public DockerTransportOutcome inspectExactIdAbsence(String id, ContainmentDeadline deadline) { return next(absence, deadline); }
    }
}
