package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DockerAcquisitionRemediationTest {
    private static final String ID = "a".repeat(64);
    private static final String SECOND_ID = "d".repeat(64);
    private static final String IMAGE = "sha256:" + "b".repeat(64);
    private static final DockerControlPlane.DockerDaemonIdentity DAEMON =
            new DockerControlPlane.DockerDaemonIdentity("npipe://engine", "default", "engine-a");

    @Test void c1ToC8AttemptIsAtomicallyOneShotAndAllSuccessfulBoundariesAreReached() throws Exception {
        for (int boundary = 1; boundary <= 8; boundary++) {
            var hooks = new Hooks(boundary);
            Fixture fixture = fixture(ContainmentResourceRole.WORKER, hooks);
            fixture.docker.create.add(ok(ID, 1, DAEMON));
            fixture.docker.inspect.add(ok(inspect(fixture, ID), 2, DAEMON));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(
                        () -> fixture.transition.acquireDocker(fixture.owner, fixture.attempt));
                assertTrue(hooks.entered.await(2, TimeUnit.SECONDS), "C" + boundary);
                var second = executor.submit(
                        () -> fixture.transition.acquireDocker(fixture.owner, fixture.attempt));
                if (boundary < 7) assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> second.get(2, TimeUnit.SECONDS), "C" + boundary);
                else assertFalse(second.isDone(), "second caller must wait for the owner lock at C" + boundary);
                hooks.release.countDown();
                assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.ACQUIRED, first.get());
                if (boundary >= 7) assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> second.get(2, TimeUnit.SECONDS), "C" + boundary);
            }
            assertEquals(1, fixture.docker.createCalls.get(), "C" + boundary);
            assertEquals(1, hooks.count(boundary), "C" + boundary);
        }
    }

    @Test void c9RecoveryAndC10AbsenceUseOriginalDeadlineAndExactId() throws Exception {
        var hooks = new Hooks(false); Fixture fixture = fixture(ContainmentResourceRole.WORKER, hooks);
        fixture.docker.create.add(outcome(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                DockerControlPlane.Completion.TIMED_OUT, null, null, false, 1, DAEMON));
        fixture.docker.lookup.add(ok(ID, 2, DAEMON)); fixture.docker.inspect.add(ok(inspect(fixture, ID), 3, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.ACQUIRED,
                fixture.transition.acquireDocker(fixture.owner, fixture.attempt));
        fixture.docker.remove.add(ok("", 4, DAEMON)); fixture.docker.absence.add(absent(5, DAEMON));
        fixture.owner.execute(() -> {
            fixture.transition.removeAndVerifyDockerAbsent(fixture.owner, owned(fixture.transition));
            return safeOutcome();
        });
        assertEquals(1, hooks.c9.get()); assertEquals(1, hooks.c10.get());
        assertEquals(List.of(ID), fixture.docker.removedIds);
        assertTrue(fixture.docker.deadlines.stream().allMatch(value -> value == fixture.deadline));
    }

    @Test void lateOwnershipBeforeAndAfterPublicationRemainsCleanupCapableAndUnsafe() throws Exception {
        Fixture before = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        before.docker.create.add(notDispatched(1));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.DEFINITE_NO_SIDE_EFFECT,
                before.transition.acquireDocker(before.owner, before.attempt));
        before.docker.inspect.add(ok(inspect(before, ID), 2, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.LATE_RETAINED,
                before.transition.recordLateDockerAcquisition(before.owner, before.attempt, ID));
        before.docker.remove.add(ok("", 3, DAEMON)); before.docker.absence.add(absent(4, DAEMON));
        var beforeState = before.owner.execute(() -> {
            before.transition.removeAndVerifyRetainedDockerAbsent(before.owner, before.attempt);
            return safeOutcome();
        });
        assertFalse(beforeState.safe()); assertEquals(List.of(ID), before.docker.removedIds);

        Fixture after = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        after.docker.create.add(notDispatched(1));
        after.transition.acquireDocker(after.owner, after.attempt);
        var published = after.owner.execute(DockerAcquisitionRemediationTest::safeOutcome);
        assertTrue(published.safe());
        after.docker.inspect.add(ok(inspect(after, ID), 2, DAEMON));
        after.transition.recordLateDockerAcquisition(after.owner, after.attempt, ID);
        after.docker.remove.add(ok("", 3, DAEMON)); after.docker.absence.add(absent(4, DAEMON));
        after.transition.removeAndVerifyRetainedDockerAbsent(after.owner, after.attempt);
        assertFalse(published.safe()); assertTrue(published.compromised());

        Fixture expired = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        expired.docker.create.add(notDispatched(1)); expired.transition.acquireDocker(expired.owner, expired.attempt);
        expired.owner.execute(DockerAcquisitionRemediationTest::safeOutcome);
        expired.docker.inspect.add(ok(inspect(expired, ID), 2, DAEMON));
        expired.transition.recordLateDockerAcquisition(expired.owner, expired.attempt, ID);
        expired.docker.remove.add(outcome(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.DEADLINE_EXHAUSTED, null, null, true, 3, null));
        assertThrows(IllegalStateException.class,
                () -> expired.transition.removeAndVerifyRetainedDockerAbsent(expired.owner, expired.attempt));
    }

    @Test void differentLateIdsBeforePublicationRetainFirstAndReconcileEveryVerifiedIdentity()
            throws Exception {
        Fixture fixture = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        fixture.docker.create.add(notDispatched(1));
        fixture.transition.acquireDocker(fixture.owner, fixture.attempt);
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 2, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.LATE_RETAINED,
                fixture.transition.recordLateDockerAcquisition(fixture.owner, fixture.attempt, ID));
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 3, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.IDEMPOTENT,
                fixture.transition.recordLateDockerAcquisition(fixture.owner, fixture.attempt, ID));
        fixture.docker.inspect.add(ok(inspect(fixture, SECOND_ID), 4, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.CONFLICT,
                fixture.transition.recordLateDockerAcquisition(
                        fixture.owner, fixture.attempt, SECOND_ID));
        assertEquals(List.of(ID, SECOND_ID), retainedIds(fixture.transition));

        fixture.docker.remove.add(ok("", 5, DAEMON)); fixture.docker.absence.add(absent(6, DAEMON));
        fixture.docker.remove.add(ok("", 7, DAEMON)); fixture.docker.absence.add(absent(8, DAEMON));
        var published = fixture.owner.execute(() -> {
            fixture.transition.removeAndVerifyRetainedDockerAbsent(fixture.owner, fixture.attempt);
            return safeOutcome();
        });
        assertEquals(List.of(ID, SECOND_ID), fixture.docker.removedIds);
        assertFalse(published.safe()); assertTrue(published.compromised());
    }

    @Test void differentLateIdsAfterPublicationRetainArrivalOrderAndCannotRewriteUnsafe() throws Exception {
        assertDifferentIdsAfterPublication(ID, SECOND_ID);
        assertDifferentIdsAfterPublication(SECOND_ID, ID);
    }

    @Test void differentLateIdDeadlineExhaustionKeepsEveryIdentityReachableAndFailsClosed()
            throws Exception {
        Fixture fixture = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        fixture.docker.create.add(notDispatched(1));
        fixture.transition.acquireDocker(fixture.owner, fixture.attempt);
        var published = fixture.owner.execute(DockerAcquisitionRemediationTest::safeOutcome);
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 2, DAEMON));
        fixture.transition.recordLateDockerAcquisition(fixture.owner, fixture.attempt, ID);
        fixture.docker.inspect.add(ok(inspect(fixture, SECOND_ID), 3, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.CONFLICT,
                fixture.transition.recordLateDockerAcquisition(
                        fixture.owner, fixture.attempt, SECOND_ID));
        fixture.docker.remove.add(outcome(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.DEADLINE_EXHAUSTED, null, null, true, 4, null));
        assertThrows(IllegalStateException.class,
                () -> fixture.transition.removeAndVerifyRetainedDockerAbsent(
                        fixture.owner, fixture.attempt));
        assertEquals(List.of(ID, SECOND_ID), retainedIds(fixture.transition));
        assertFalse(published.safe()); assertTrue(published.compromised());
        assertTrue(fixture.docker.deadlines.stream().allMatch(value -> value == fixture.deadline));
    }

    @Test void sameIdAcrossIndependentRolesIsAlwaysGlobalConflictInBothOrders() throws Exception {
        collisionOrder(ContainmentResourceRole.WORKER, ContainmentResourceRole.NETWORK);
        collisionOrder(ContainmentResourceRole.NETWORK, ContainmentResourceRole.WORKER);
        collisionAfterPublication();
    }

    @Test void ambiguousRecoveryNeverUpgradesMultipleIncompleteDisappearingOrChangedDaemonEvidence() {
        Fixture multiple = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        multiple.docker.create.add(outcome(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                DockerControlPlane.Completion.TIMED_OUT, null, null, false, 1, DAEMON));
        multiple.docker.lookup.add(ok(ID + "\n" + "f".repeat(64), 2, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.AMBIGUOUS,
                multiple.transition.acquireDocker(multiple.owner, multiple.attempt));

        Fixture incomplete = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        incomplete.docker.create.add(outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.PROTOCOL_FAILED, 1, "bad", true, 1, DAEMON));
        incomplete.docker.lookup.add(outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.OUTPUT_OVERFLOW, 0, null, false, 2, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.AMBIGUOUS,
                incomplete.transition.acquireDocker(incomplete.owner, incomplete.attempt));

        Fixture disappeared = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        disappeared.docker.create.add(outcome(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                DockerControlPlane.Completion.TIMED_OUT, null, null, false, 1, DAEMON));
        disappeared.docker.lookup.add(ok(ID, 2, DAEMON));
        disappeared.docker.inspect.add(outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.PROTOCOL_FAILED, 1, "not found", true, 3, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.AMBIGUOUS,
                disappeared.transition.acquireDocker(disappeared.owner, disappeared.attempt));

        Fixture restarted = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        restarted.docker.create.add(outcome(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                DockerControlPlane.Completion.TIMED_OUT, null, null, false, 1, DAEMON));
        var other = new DockerControlPlane.DockerDaemonIdentity("npipe://other", "other", "engine-b");
        restarted.docker.lookup.add(ok(ID, 2, other));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.AMBIGUOUS,
                restarted.transition.acquireDocker(restarted.owner, restarted.attempt));
    }

    @Test void productionAdapterRequiresIssuerAndRejectsEveryHostAuthorityOption() throws Exception {
        var production = SingleOwnerCleanup.docker(ContainmentDeadline.after(Duration.ofSeconds(5)));
        Field productionDocker = SingleOwnerCleanup.class.getDeclaredField("docker");
        productionDocker.setAccessible(true);
        assertInstanceOf(DockerCliControlPlane.class, productionDocker.get(production));
        Field productionAbsence = SingleOwnerCleanup.class.getDeclaredField("absenceSource");
        productionAbsence.setAccessible(true);
        assertEquals("DockerAbsenceSource", productionAbsence.get(production).getClass().getSimpleName());

        Fixture fixture = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        Field issuerField = SingleOwnerCleanup.class.getDeclaredField("proofIssuer"); issuerField.setAccessible(true);
        var issuer = (SingleOwnerCleanup.ProofIssuer) issuerField.get(fixture.cleanup);
        var adapter = DockerCliControlPlane.trusted(issuer);
        assertTrue(java.util.Arrays.stream(DockerCliControlPlane.class.getDeclaredConstructors())
                .allMatch(value -> Modifier.isPrivate(value.getModifiers())));
        DockerResourceSpec approved = DockerResourceTemplate.worker(IMAGE, SeleniumContainmentLimits.defaults())
                .bind(fixture.execution, ContainmentResourceRole.WORKER, fixture.attempt.attemptNonce());
        List<DockerResourceSpec> hostile = List.of(
                copy(approved, "host", false, "private", "private", approved.isolationTuples(), approved.environment()),
                copy(approved, "none", true, "private", "private", approved.isolationTuples(), approved.environment()),
                copy(approved, "none", false, "host", "private", approved.isolationTuples(), approved.environment()),
                copy(approved, "none", false, "private", "host", approved.isolationTuples(), approved.environment()),
                copy(approved, "none", false, "private", "private", List.of("bind=type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock"), approved.environment()),
                copy(approved, "none", false, "private", "private", List.of("cap-add=SYS_ADMIN"), approved.environment()),
                copy(approved, "none", false, "private", "private", List.of("device=/dev/sda|/dev/sda|rwm"), approved.environment()),
                copy(approved, "none", false, "private", "private", approved.isolationTuples(), List.of("EVIL=1")));
        for (DockerResourceSpec spec : hostile) assertThrows(SecurityException.class,
                () -> adapter.create(new DockerControlPlane.DockerCreateRequest(issuer, spec,
                        SeleniumContainmentLimits.defaults()), fixture.deadline));
        Fixture foreign = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        var foreignIssuer = (SingleOwnerCleanup.ProofIssuer) issuerField.get(foreign.cleanup);
        assertThrows(SecurityException.class, () -> adapter.create(new DockerControlPlane.DockerCreateRequest(
                foreignIssuer, approved, SeleniumContainmentLimits.defaults()), fixture.deadline));
    }

    @Test void representativeInspectLocksEveryCanonicalFieldAndRealCollectionShape() {
        Fixture fixture = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        String raw = inspect(fixture, ID);
        DockerResourceFingerprint expected = fixture.spec().fingerprint(ID);
        assertEquals(expected, DockerInspectParser.parse(raw));
        List<String[]> mismatches = List.of(
                pair("\"Id\":\"" + ID, "\"Id\":\"" + "f".repeat(64)),
                pair(fixture.execution.toString(), UUID.randomUUID().toString()),
                pair("\"WORKER\"", "\"NETWORK\""), pair(fixture.attempt.attemptNonce(), "c".repeat(64)),
                pair(IMAGE, "sha256:" + "e".repeat(64)), pair("/opt/java/openjdk/bin/java", "/bin/sh"),
                pair("\"Cmd\":[\"" + fixture.execution, "\"Cmd\":[\"changed"),
                pair("10001:10001", "0:0"), pair("\"ReadonlyRootfs\":true", "\"ReadonlyRootfs\":false"),
                pair("\"Name\":\"no\"", "\"Name\":\"always\""), pair("\"MaximumRetryCount\":0", "\"MaximumRetryCount\":1"),
                pair("\"NetworkMode\":\"none\"", "\"NetworkMode\":\"host\""), pair("\"Privileged\":false", "\"Privileged\":true"),
                pair("\"PidMode\":\"private\"", "\"PidMode\":\"host\""), pair("\"IpcMode\":\"private\"", "\"IpcMode\":\"host\""),
                pair("\"CapDrop\":[\"ALL\"]", "\"CapDrop\":[]"), pair("size=33554432", "size=1"),
                pair("LANG=C.UTF-8", "LANG=C"));
        for (String[] mismatch : mismatches)
            assertNotEquals(expected, DockerInspectParser.parse(raw.replace(mismatch[0], mismatch[1])), mismatch[0]);
        assertThrows(IllegalArgumentException.class,
                () -> DockerInspectParser.parse(raw.replace("\"Devices\":[]", "\"Devices\":null")));
        assertThrows(IllegalArgumentException.class,
                () -> DockerInspectParser.parse(raw.replace("\"Mounts\":[]", "\"Mounts\":[\"text\"]")));
        assertEquals(expected, DockerInspectParser.parse(raw.replace("PATH=/usr/bin", "HOME=/home/worker")));
    }

    @Test void imageResolutionAndDaemonContinuityAreBoundAcrossOwnershipAndAbsence() throws Exception {
        String reference = "registry.example/worker@sha256:" + "d".repeat(64);
        Fixture resolved = fixture(ContainmentResourceRole.WORKER, new Hooks(false), reference);
        resolved.docker.inspect.add(ok(IMAGE, 1, DAEMON));
        resolved.docker.create.add(ok(ID, 2, DAEMON));
        resolved.docker.inspect.add(ok(inspect(resolved, ID).replace("sha256:" + "0".repeat(64), IMAGE), 3, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.ACQUIRED,
                resolved.transition.acquireDocker(resolved.owner, resolved.attempt));
        assertEquals(reference, resolved.docker.created.imageReference());
        assertEquals(IMAGE, resolved.docker.created.imageIdentity());

        Fixture changed = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        changed.docker.create.add(ok(ID, 1, DAEMON));
        changed.docker.inspect.add(ok(inspect(changed, ID), 2,
                new DockerControlPlane.DockerDaemonIdentity("npipe://other", "other", "engine-b")));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.AMBIGUOUS,
                changed.transition.acquireDocker(changed.owner, changed.attempt));

        Fixture absenceChanged = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        absenceChanged.docker.create.add(ok(ID, 1, DAEMON)); absenceChanged.docker.inspect.add(ok(inspect(absenceChanged, ID), 2, DAEMON));
        absenceChanged.transition.acquireDocker(absenceChanged.owner, absenceChanged.attempt);
        absenceChanged.docker.absence.add(absent(3,
                new DockerControlPlane.DockerDaemonIdentity("npipe://other", "default", "engine-b")));
        assertFalse(absenceChanged.owner.execute(() -> {
            assertThrows(IllegalStateException.class,
                    () -> absenceChanged.transition.verifyAbsent(absenceChanged.owner, owned(absenceChanged.transition)));
            return safeOutcome();
        }).safe());
    }

    @Test void typedTransportDistinguishesDaemonProtocolReadOverflowTimeoutAndInterruption() throws Exception {
        Method classify = DockerCliControlPlane.class.getDeclaredMethod("classifyFailure", String.class);
        classify.setAccessible(true);
        assertEquals(DockerControlPlane.Completion.DAEMON_FAILED,
                classify.invoke(null, "Error response from daemon: unavailable"));
        assertEquals(DockerControlPlane.Completion.PROTOCOL_FAILED,
                classify.invoke(null, "malformed client response"));
        assertNotEquals(DockerControlPlane.Completion.READ_FAILED, DockerControlPlane.Completion.OUTPUT_OVERFLOW);
        assertDoesNotThrow(() -> outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.READ_FAILED, 1, null, false, 1, DAEMON));
        assertDoesNotThrow(() -> outcome(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                DockerControlPlane.Completion.TIMED_OUT, null, null, false, 2, DAEMON));
        assertDoesNotThrow(() -> outcome(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                DockerControlPlane.Completion.INTERRUPTED, null, null, false, 3, DAEMON));
        Method read = DockerCliControlPlane.class.getDeclaredMethod("read", java.io.InputStream.class,
                java.io.ByteArrayOutputStream.class, java.util.concurrent.atomic.AtomicBoolean.class,
                java.util.concurrent.atomic.AtomicReference.class);
        read.setAccessible(true);
        var overflow = new java.util.concurrent.atomic.AtomicBoolean();
        var readFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        read.invoke(null, new java.io.ByteArrayInputStream(new byte[1_048_577]),
                new java.io.ByteArrayOutputStream(), overflow, readFailure);
        assertTrue(overflow.get()); assertNull(readFailure.get());
        overflow.set(false);
        read.invoke(null, new java.io.InputStream() { public int read() throws java.io.IOException {
            throw new java.io.IOException("read failure"); } }, new java.io.ByteArrayOutputStream(),
                overflow, readFailure);
        assertFalse(overflow.get()); assertInstanceOf(java.io.IOException.class, readFailure.get());
    }

    private static void collisionOrder(ContainmentResourceRole firstRole,
            ContainmentResourceRole secondRole) throws Exception {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(5)); var docker = new FakeDocker();
        var cleanup = cleanup(deadline, docker, new Hooks(false)); var owner = cleanup.claim();
        var first = transition(cleanup, owner, firstRole, docker, deadline);
        var second = transition(cleanup, owner, secondRole, docker, deadline);
        docker.create.add(ok(ID, 1, DAEMON)); docker.inspect.add(ok(inspect(first, ID), 2, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.ACQUIRED,
                first.transition.acquireDocker(owner, first.attempt));
        docker.create.add(ok(ID, 3, DAEMON)); docker.inspect.add(ok(inspect(second, ID), 4, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.CONFLICT,
                second.transition.acquireDocker(owner, second.attempt));
        var state = owner.execute(DockerAcquisitionRemediationTest::safeOutcome);
        assertFalse(state.safe()); assertEquals(ID, owned(first.transition).identity().value());
    }

    private static void collisionAfterPublication() throws Exception {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(5)); var docker = new FakeDocker();
        var cleanup = cleanup(deadline, docker, new Hooks(false)); var owner = cleanup.claim();
        var worker = transition(cleanup, owner, ContainmentResourceRole.WORKER, docker, deadline);
        var network = transition(cleanup, owner, ContainmentResourceRole.NETWORK, docker, deadline);
        docker.create.add(ok(ID, 1, DAEMON)); docker.inspect.add(ok(inspect(worker, ID), 2, DAEMON));
        worker.transition.acquireDocker(owner, worker.attempt);
        docker.create.add(ok(ID, 3, DAEMON)); docker.inspect.add(ok(inspect(network, ID), 4, DAEMON));
        docker.blockInspect = true;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var collision = executor.submit(() -> network.transition.acquireDocker(owner, network.attempt));
            assertTrue(docker.inspectEntered.await(2, TimeUnit.SECONDS));
            var state = owner.execute(DockerAcquisitionRemediationTest::safeOutcome);
            docker.inspectRelease.countDown();
            assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.CONFLICT, collision.get());
            assertFalse(state.safe()); assertTrue(state.compromised());
            assertEquals(ID, owned(worker.transition).identity().value());
        }
    }

    private static void assertDifferentIdsAfterPublication(String firstId, String secondId)
            throws Exception {
        Fixture fixture = fixture(ContainmentResourceRole.WORKER, new Hooks(false));
        fixture.docker.create.add(notDispatched(1));
        fixture.transition.acquireDocker(fixture.owner, fixture.attempt);
        var published = fixture.owner.execute(DockerAcquisitionRemediationTest::safeOutcome);
        assertTrue(published.safe());
        fixture.docker.inspect.add(ok(inspect(fixture, firstId), 2, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.LATE_RETAINED,
                fixture.transition.recordLateDockerAcquisition(
                        fixture.owner, fixture.attempt, firstId));
        fixture.docker.inspect.add(ok(inspect(fixture, secondId), 3, DAEMON));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.CONFLICT,
                fixture.transition.recordLateDockerAcquisition(
                        fixture.owner, fixture.attempt, secondId));
        assertEquals(List.of(firstId, secondId), retainedIds(fixture.transition));
        fixture.docker.remove.add(ok("", 4, DAEMON)); fixture.docker.absence.add(absent(5, DAEMON));
        fixture.docker.remove.add(ok("", 6, DAEMON)); fixture.docker.absence.add(absent(7, DAEMON));
        fixture.transition.removeAndVerifyRetainedDockerAbsent(fixture.owner, fixture.attempt);
        assertEquals(List.of(firstId, secondId), fixture.docker.removedIds);
        assertFalse(published.safe()); assertTrue(published.compromised());
    }

    private static Fixture fixture(ContainmentResourceRole role, Hooks hooks) {
        return fixture(role, hooks, IMAGE);
    }
    private static Fixture fixture(ContainmentResourceRole role, Hooks hooks, String imageReference) {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(5)); var docker = new FakeDocker();
        var cleanup = cleanup(deadline, docker, hooks); var owner = cleanup.claim();
        return transition(cleanup, owner, role, docker, deadline, imageReference);
    }
    private static Fixture transition(SingleOwnerCleanup cleanup, SingleOwnerCleanup.Claim owner,
            ContainmentResourceRole role, FakeDocker docker, ContainmentDeadline deadline) {
        return transition(cleanup, owner, role, docker, deadline, IMAGE);
    }
    private static Fixture transition(SingleOwnerCleanup cleanup, SingleOwnerCleanup.Claim owner,
            ContainmentResourceRole role, FakeDocker docker, ContainmentDeadline deadline,
            String imageReference) {
        try {
            DockerResourceTemplate template;
            if (role == ContainmentResourceRole.WORKER) template = DockerResourceTemplate.worker(
                    imageReference, SeleniumContainmentLimits.defaults());
            else {
                Constructor<DockerResourceTemplate> constructor = DockerResourceTemplate.class
                        .getDeclaredConstructor(String.class, SeleniumContainmentLimits.class,
                                ContainmentResourceRole.class);
                constructor.setAccessible(true);
                template = constructor.newInstance(imageReference, SeleniumContainmentLimits.defaults(), role);
            }
            var transition = cleanup.resource(owner, role); UUID execution = UUID.randomUUID();
            var attempt = transition.beginDockerAcquisition(owner, execution, template);
            return new Fixture(deadline, docker, cleanup, owner, transition, attempt, execution);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static SingleOwnerCleanup cleanup(ContainmentDeadline deadline, DockerControlPlane docker,
            SingleOwnerCleanup.AwaitBoundary hooks) {
        try {
            var constructor = SingleOwnerCleanup.class.getDeclaredConstructor(ContainmentDeadline.class,
                    SingleOwnerCleanup.AwaitBoundary.class, boolean.class, DockerControlPlane.class);
            constructor.setAccessible(true); return constructor.newInstance(deadline, hooks, false, docker);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static ResourceDisposition.OwnedPresent owned(
            SingleOwnerCleanup.ResourceTransition transition) throws Exception {
        Field field = SingleOwnerCleanup.ResourceTransition.class.getDeclaredField("owned");
        field.setAccessible(true); return (ResourceDisposition.OwnedPresent) field.get(transition);
    }
    private static List<String> retainedIds(SingleOwnerCleanup.ResourceTransition transition)
            throws Exception {
        Field field = SingleOwnerCleanup.ResourceTransition.class
                .getDeclaredField("retainedLateOwnership");
        field.setAccessible(true);
        return ((java.util.Map<?, ?>) field.get(transition)).keySet().stream()
                .map(String.class::cast).toList();
    }
    private static String inspect(Fixture fixture, String id) { return inspect(fixture.role(), fixture.execution,
            fixture.attempt.attemptNonce(), fixture.spec().imageIdentity(), id); }
    private static String inspect(ContainmentResourceRole role, UUID execution, String nonce,
            String image, String id) {
        return """
                [{"Id":"%s","Image":"%s","Config":{"Labels":{"automation-studio.execution":"%s","automation-studio.role":"%s","automation-studio.attempt":"%s"},"Entrypoint":["/opt/java/openjdk/bin/java","-Xms16m","-Xmx64m","-Djava.io.tmpdir=/work/tmp","-jar","/opt/worker/worker.jar"],"Cmd":["%s"],"User":"10001:10001","Env":["PATH=/usr/bin","LANG=C.UTF-8"]},"HostConfig":{"ReadonlyRootfs":true,"RestartPolicy":{"Name":"no","MaximumRetryCount":0},"NetworkMode":"none","Privileged":false,"PidMode":"private","IpcMode":"private","CapAdd":[],"CapDrop":["ALL"],"Devices":[],"Binds":[],"Mounts":[],"Tmpfs":{"/work/runtime":"rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=33554432","/work/tmp":"rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=8388608"},"SecurityOpt":["no-new-privileges:true","seccomp=builtin"]}}]
                """.formatted(id, image, execution, role, nonce, execution);
    }
    private static String inspect(Fixture fixture, String id, String image) {
        return inspect(fixture.role(), fixture.execution, fixture.attempt.attemptNonce(), image, id);
    }
    private static String[] pair(String first, String second) { return new String[] { first, second }; }
    private static DockerResourceSpec copy(DockerResourceSpec value, String network, boolean privileged,
            String pid, String ipc, List<String> isolation, List<String> environment) {
        return new DockerResourceSpec(value.executionId(), value.role(), value.attemptNonce(),
                value.imageReference(), value.imageIdentity(), value.entrypoint(), value.command(), value.user(),
                value.readOnlyRootfs(), value.restartPolicy(), value.restartMaximumRetryCount(), network,
                privileged, pid, ipc, isolation, environment);
    }
    private static DockerControlPlane.DockerTransportOutcome ok(String response, long revision,
            DockerControlPlane.DockerDaemonIdentity daemon) {
        return outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, 0, response, true, revision, daemon);
    }
    private static DockerControlPlane.DockerTransportOutcome absent(long revision,
            DockerControlPlane.DockerDaemonIdentity daemon) {
        return new DockerControlPlane.DockerTransportOutcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, 1, "", true, revision,
                DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND, daemon);
    }
    private static DockerControlPlane.DockerTransportOutcome notDispatched(long revision) {
        return outcome(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.SPAWN_FAILED, null, null, true, revision, null);
    }
    private static DockerControlPlane.DockerTransportOutcome outcome(DockerControlPlane.Dispatch dispatch,
            DockerControlPlane.Completion completion, Integer exit, String response, boolean complete,
            long revision, DockerControlPlane.DockerDaemonIdentity daemon) {
        return new DockerControlPlane.DockerTransportOutcome(dispatch, completion, exit, response,
                complete, revision, DockerControlPlane.Semantic.NONE, daemon);
    }
    private static SingleOwnerCleanup.TerminalOutcome safeOutcome() {
        return new SingleOwnerCleanup.TerminalOutcome(ExecutionOutcome.FAILED, ContainmentCode.ABSENT,
                AttachmentState.NOT_STARTED, DependencyEvidence.SATISFIED);
    }

    private record Fixture(ContainmentDeadline deadline, FakeDocker docker, SingleOwnerCleanup cleanup,
            SingleOwnerCleanup.Claim owner, SingleOwnerCleanup.ResourceTransition transition,
            SingleOwnerCleanup.AcquisitionAttempt attempt, UUID execution) {
        DockerResourceSpec spec() {
            try { Field field = attempt.getClass().getDeclaredField("spec"); field.setAccessible(true);
                return (DockerResourceSpec) field.get(attempt);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        ContainmentResourceRole role() { return spec().role(); }
    }
    private static final class FakeDocker implements DockerControlPlane {
        final ArrayDeque<DockerTransportOutcome> create = new ArrayDeque<>(), inspect = new ArrayDeque<>(),
                lookup = new ArrayDeque<>(), remove = new ArrayDeque<>(), absence = new ArrayDeque<>();
        final java.util.ArrayList<ContainmentDeadline> deadlines = new java.util.ArrayList<>();
        final java.util.ArrayList<String> removedIds = new java.util.ArrayList<>();
        final AtomicInteger createCalls = new AtomicInteger(); DockerResourceSpec created;
        final AtomicInteger inspectCalls = new AtomicInteger(); boolean blockInspect;
        final CountDownLatch inspectEntered = new CountDownLatch(1), inspectRelease = new CountDownLatch(1);
        private DockerTransportOutcome next(ArrayDeque<DockerTransportOutcome> values,
                ContainmentDeadline deadline) { deadlines.add(deadline); return values.remove(); }
        public DockerTransportOutcome resolveImage(String reference, ContainmentDeadline deadline) { return next(inspect, deadline); }
        public DockerTransportOutcome create(DockerCreateRequest request, ContainmentDeadline deadline) {
            createCalls.incrementAndGet(); created = request.expected(); return next(create, deadline); }
        public DockerTransportOutcome inspectExactId(String id, ContainmentDeadline deadline) {
            inspectCalls.incrementAndGet();
            if (blockInspect) { inspectEntered.countDown(); try {
                if (!inspectRelease.await(2, TimeUnit.SECONDS)) throw new AssertionError("inspect timeout");
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } }
            return next(inspect, deadline); }
        public DockerTransportOutcome lookupAttempt(DockerAttemptLookup value, ContainmentDeadline deadline) { return next(lookup, deadline); }
        public DockerTransportOutcome removeExactId(String id, ContainmentDeadline deadline) { removedIds.add(id); return next(remove, deadline); }
        public DockerTransportOutcome inspectExactIdAbsence(String id, ContainmentDeadline deadline) { return next(absence, deadline); }
    }
    private static final class Hooks implements SingleOwnerCleanup.AwaitBoundary {
        final AtomicInteger c1 = new AtomicInteger(), c2 = new AtomicInteger(), c3 = new AtomicInteger(),
                c4 = new AtomicInteger(), c5 = new AtomicInteger(), c6 = new AtomicInteger(),
                c7 = new AtomicInteger(), c8 = new AtomicInteger(), c9 = new AtomicInteger(),
                c10 = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final int blockedBoundary;
        Hooks(boolean blockC1) { this(blockC1 ? 1 : 0); }
        Hooks(int blockedBoundary) { this.blockedBoundary = blockedBoundary; }
        private void hit(int boundary, AtomicInteger count) {
            count.incrementAndGet();
            if (blockedBoundary == boundary) {
                entered.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("C" + boundary + " timeout"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        }
        int count(int boundary) { return switch (boundary) {
            case 1 -> c1.get(); case 2 -> c2.get(); case 3 -> c3.get(); case 4 -> c4.get();
            case 5 -> c5.get(); case 6 -> c6.get(); case 7 -> c7.get(); case 8 -> c8.get();
            case 9 -> c9.get(); case 10 -> c10.get(); default -> throw new IllegalArgumentException();
        }; }
        public void c1BeforeCreateDispatch() { hit(1, c1); }
        public void c2AfterCreateDispatch() { hit(2, c2); } public void c3AfterPossibleSideEffect() { hit(3, c3); }
        public void c4AfterIdBeforeInspect() { hit(4, c4); } public void c5AfterInspect() { hit(5, c5); }
        public void c6AfterVerification() { hit(6, c6); } public void c7DuringHandoff() { hit(7, c7); }
        public void c8AfterOwnershipRegistration() { hit(8, c8); } public void c9DuringRecovery() { hit(9, c9); }
        public void c10DuringAbsenceInspection() { c10.incrementAndGet(); }
    }
}
