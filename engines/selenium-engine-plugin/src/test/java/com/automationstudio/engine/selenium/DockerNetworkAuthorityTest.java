package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DockerNetworkAuthorityTest {
    private static final String ID = "a".repeat(64), ID2 = "b".repeat(64);
    private static final DockerControlPlane.DockerDaemonIdentity DAEMON =
            new DockerControlPlane.DockerDaemonIdentity("npipe://engine", "default", "engine-a");

    @Test void n01SuccessfulCreateInspectHandoffAndN14ExactIdRemovalAbsence() {
        Fixture fixture = fixture(); fixture.docker.create.add(ok(ID, 1));
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.ACQUIRED, fixture.acquire());
        fixture.docker.remove.add(ok("", 3)); fixture.docker.absence.add(absent(4));
        var state = fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome);
        assertTrue(state.safe()); assertInstanceOf(ResourceDisposition.OwnedAbsent.class, state.report().network());
        assertEquals(List.of(ID), fixture.docker.removed);
        assertEquals(DockerNetworkAuthority.EntryState.OWNED_ABSENT,
                fixture.cleanup.networkLedger(fixture.owner).get(0).state());
    }

    @Test void n02PreDispatchDefiniteFailureHasNoSideEffect() {
        Fixture fixture = fixture(); fixture.docker.create.add(notDispatched(1));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.DEFINITE_NO_SIDE_EFFECT, fixture.acquire());
        assertTrue(fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        assertTrue(fixture.docker.removed.isEmpty());
    }

    @Test void n03PossibleDispatchLostResponseAndN04MalformedIdRemainUnsafe() {
        Fixture ambiguous = fixture(); ambiguous.docker.create.add(timeout(1));
        ambiguous.docker.lookup.add(ok("", 2));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.AMBIGUOUS, ambiguous.acquire());
        assertFalse(ambiguous.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        Fixture malformed = fixture(); malformed.docker.create.add(ok("not-an-id", 1));
        malformed.docker.lookup.add(ok("", 2));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.AMBIGUOUS, malformed.acquire());
    }

    @Test void n05LookalikeWrongNonceAndN06PreExistingNameAreNeverAdopted() {
        Fixture wrong = fixture(); wrong.docker.create.add(timeout(1)); wrong.docker.lookup.add(ok(ID, 2));
        wrong.docker.inspect.add(ok(inspect(wrong, ID).replace(wrong.attempt.spec().attemptNonce(),
                "f".repeat(64)), 3));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.AMBIGUOUS, wrong.acquire());
        Fixture nameOnly = fixture(); nameOnly.docker.create.add(timeout(1)); nameOnly.docker.lookup.add(ok("", 2));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.AMBIGUOUS, nameOnly.acquire());
        assertTrue(nameOnly.docker.inspected.isEmpty());
    }

    @Test void n07EveryAuthoritativeFingerprintFieldRejectsMismatch() {
        Fixture fixture = fixture(); String valid = inspect(fixture, ID);
        List<String> invalid = List.of(
                valid.replace("\"Driver\":\"bridge\"", "\"Driver\":\"host\""),
                valid.replace("\"Scope\":\"local\"", "\"Scope\":\"swarm\""),
                valid.replace("\"Internal\":false", "\"Internal\":true"),
                valid.replace("\"Attachable\":false", "\"Attachable\":true"),
                valid.replace("\"Ingress\":false", "\"Ingress\":true"),
                valid.replace("\"ConfigOnly\":false", "\"ConfigOnly\":true"),
                valid.replace("\"ConfigFrom\":{\"Network\":\"\"}",
                        "\"ConfigFrom\":{\"Network\":\"x\"}"),
                valid.replace("\"EnableIPv4\":true", "\"EnableIPv4\":false"),
                valid.replace("\"EnableIPv6\":false", "\"EnableIPv6\":true"),
                valid.replace("\"Driver\":\"default\"", "\"Driver\":\"plugin\""),
                valid.replace("\"Options\":{},\"Config\"", "\"Options\":{\"x\":\"y\"},\"Config\""),
                valid.replace("\"Gateway\":\"172.30.0.1\"", "\"Gateway\":\"10.0.0.1\""),
                valid.replace("\"IPRange\":\"\"", "\"IPRange\":\"172.30.0.0/24\""),
                valid.replace("\"AuxiliaryAddresses\":{}", "\"AuxiliaryAddresses\":{\"x\":\"172.30.0.2\"}"),
                valid.replace("\"Options\":{},\"Labels\"", "\"Options\":{\"x\":\"y\"},\"Labels\""),
                valid.replace(fixture.execution.toString(), UUID.randomUUID().toString()),
                valid.replace("\"NETWORK\"", "\"WORKER\""),
                valid.replace("\"" + fixture.attempt.spec().acquisitionRevision() + "\"", "\"999\""),
                valid.replace(fixture.attempt.spec().attemptNonce(), "e".repeat(64)),
                valid.replace(fixture.attempt.spec().deadlineCorrelation(), "d".repeat(64)),
                valid.replaceFirst(ID, ID2));
        invalid.forEach(payload -> assertThrows(IllegalArgumentException.class,
                () -> DockerNetworkInspectParser.parse(payload, fixture.attempt.spec(), ID)));
    }

    @Test void n08ReplayAndN09DuplicateConsumedAttemptAreRejected() {
        Fixture first = fixture(), second = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> second.cleanup.acquireNetwork(second.owner, first.attempt));
        first.docker.create.add(ok(ID, 1)); first.docker.inspect.add(ok(inspect(first, ID), 2));
        first.acquire(); assertThrows(IllegalStateException.class, first::acquire);
    }

    @Test void n10SameIdConflictAndN11DifferentIdsRetainAllIdentities() {
        Fixture fixture = fixture(); fixture.docker.create.add(ok(ID, 1));
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 2)); fixture.acquire();
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 3));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.IDEMPOTENT,
                fixture.cleanup.recordLateNetworkAcquisition(fixture.owner, fixture.attempt, ID));
        fixture.docker.inspect.add(ok(inspect(fixture, ID2), 4));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.CONFLICT,
                fixture.cleanup.recordLateNetworkAcquisition(fixture.owner, fixture.attempt, ID2));
        assertEquals(List.of(ID, ID2), fixture.cleanup.networkLedger(fixture.owner).stream()
                .map(DockerNetworkAuthority.LedgerEntry::immutableId).toList());
        assertTrue(fixture.cleanup.networkLedger(fixture.owner).stream().allMatch(
                DockerNetworkAuthority.LedgerEntry::conflict));
    }

    @Test void n10CrossRoleSameImmutableIdRetainsBothTypedIdentitiesWorkerThenNetwork() throws Exception {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(5));
        var container = new FakeContainer(); var network = new FakeNetwork();
        var constructor = SingleOwnerCleanup.class.getDeclaredConstructor(ContainmentDeadline.class,
                SingleOwnerCleanup.AwaitBoundary.class, DockerControlPlane.class,
                DockerNetworkControlPlane.class, DockerNetworkAuthority.Boundaries.class);
        constructor.setAccessible(true); var cleanup = constructor.newInstance(deadline,
                SingleOwnerCleanup.AwaitBoundary.NONE, container, network,
                DockerNetworkAuthority.Boundaries.NONE);
        var owner = cleanup.claim(); UUID execution = UUID.randomUUID();
        var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        var workerAttempt = worker.beginDockerAcquisition(owner, execution,
                DockerResourceTemplate.worker("sha256:" + "c".repeat(64), SeleniumContainmentLimits.defaults()));
        container.create.add(ok(ID, 1)); container.inspect.add(ok(workerInspect(execution,
                workerAttempt.attemptNonce(), ID), 2));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.ACQUIRED,
                worker.acquireDocker(owner, workerAttempt));
        var networkAttempt = cleanup.beginNetworkAcquisition(owner, execution);
        network.create.add(ok(ID, 3));
        var fixture = new Fixture(deadline, network, cleanup, owner, networkAttempt, execution);
        network.inspect.add(ok(inspect(fixture, ID), 4));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.CONFLICT,
                cleanup.acquireNetwork(owner, networkAttempt));
        assertEquals(ID, ownedIdentity(worker));
        assertEquals(List.of(ID), cleanup.networkLedger(owner).stream()
                .map(DockerNetworkAuthority.LedgerEntry::immutableId).toList());
        container.remove.add(ok("", 5)); container.absence.add(absent(6));
        network.remove.add(ok("", 7)); network.absence.add(absent(8));
        assertFalse(owner.execute(() -> {
            worker.removeAndVerifyDockerAbsent(owner, owned(worker));
            return safeOutcome();
        }).safe());
        assertEquals(List.of(ID), container.removed);
        assertEquals(List.of(ID), network.removed);
    }

    @Test void n10CrossRoleSameImmutableIdRetainsBothTypedIdentitiesNetworkThenWorker() throws Exception {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(5));
        var container = new FakeContainer(); var network = new FakeNetwork();
        var constructor = SingleOwnerCleanup.class.getDeclaredConstructor(ContainmentDeadline.class,
                SingleOwnerCleanup.AwaitBoundary.class, DockerControlPlane.class,
                DockerNetworkControlPlane.class, DockerNetworkAuthority.Boundaries.class);
        constructor.setAccessible(true); var cleanup = constructor.newInstance(deadline,
                SingleOwnerCleanup.AwaitBoundary.NONE, container, network,
                DockerNetworkAuthority.Boundaries.NONE);
        var owner = cleanup.claim(); UUID execution = UUID.randomUUID();
        var networkAttempt = cleanup.beginNetworkAcquisition(owner, execution);
        network.create.add(ok(ID, 1));
        var fixture = new Fixture(deadline, network, cleanup, owner, networkAttempt, execution);
        network.inspect.add(ok(inspect(fixture, ID), 2));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.ACQUIRED,
                cleanup.acquireNetwork(owner, networkAttempt));
        var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        var workerAttempt = worker.beginDockerAcquisition(owner, execution,
                DockerResourceTemplate.worker("sha256:" + "c".repeat(64), SeleniumContainmentLimits.defaults()));
        container.create.add(ok(ID, 3)); container.inspect.add(ok(workerInspect(execution,
                workerAttempt.attemptNonce(), ID), 4));
        assertEquals(SingleOwnerCleanup.DockerAcquisitionStatus.CONFLICT,
                worker.acquireDocker(owner, workerAttempt));
        assertEquals(List.of(ID), cleanup.networkLedger(owner).stream()
                .map(DockerNetworkAuthority.LedgerEntry::immutableId).toList());
        container.remove.add(ok("", 5)); container.absence.add(absent(6));
        network.remove.add(ok("", 7)); network.absence.add(absent(8));
        assertFalse(owner.execute(() -> {
            worker.removeAndVerifyRetainedDockerAbsent(owner, workerAttempt);
            return safeOutcome();
        }).safe());
        assertEquals(List.of(ID), container.removed);
        assertEquals(List.of(ID), network.removed);
    }

    @Test void n12DisappearingCandidateAndN13DaemonChangeRemainUnsafe() {
        Fixture vanished = fixture(); vanished.docker.create.add(ok(ID, 1)); vanished.docker.inspect.add(protocol(2));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.AMBIGUOUS, vanished.acquire());
        Fixture changed = fixture(); changed.docker.create.add(ok(ID, 1));
        changed.docker.inspect.add(ok(inspect(changed, ID), 2,
                new DockerControlPlane.DockerDaemonIdentity("npipe://other", "other", "engine-b")));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.AMBIGUOUS, changed.acquire());
    }

    @Test void daemonContinuityChangesAtRecoveryRemoveAndAbsenceRemainUnsafe() {
        var other = new DockerControlPlane.DockerDaemonIdentity("npipe://other", "other", "engine-b");
        Fixture recovery = fixture(); recovery.docker.create.add(timeout(1));
        recovery.docker.lookup.add(ok(ID, 2, other));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.AMBIGUOUS, recovery.acquire());
        Fixture removal = acquired(); removal.docker.remove.add(ok("", 3, other));
        assertFalse(removal.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        Fixture absence = acquired(); absence.docker.remove.add(ok("", 3));
        absence.docker.absence.add(outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, 1, "No such network " + ID, true, 4,
                DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND, other));
        assertFalse(absence.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
    }

    @Test void n15AmbiguousRemoveOrAbsenceCannotManufactureAbsence() {
        Fixture fixture = acquired(); fixture.docker.remove.add(ok("", 3)); fixture.docker.absence.add(timeout(4));
        assertFalse(fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        assertEquals(DockerNetworkAuthority.EntryState.UNRESOLVED,
                fixture.cleanup.networkLedger(fixture.owner).get(0).state());
    }

    @Test void n16WorkerDependencyPrecedesNetworkAndN17PartialStartupUsesReverseIdOrder() {
        Fixture fixture = acquired(); fixture.docker.inspect.add(ok(inspect(fixture, ID2), 3));
        fixture.cleanup.recordLateNetworkAcquisition(fixture.owner, fixture.attempt, ID2);
        fixture.docker.remove.add(ok("", 4)); fixture.docker.absence.add(absent(5));
        fixture.docker.remove.add(ok("", 6)); fixture.docker.absence.add(absent(7));
        fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome);
        assertEquals(List.of(ID2, ID), fixture.docker.removed);

        Fixture blocked = acquired();
        var worker = blocked.cleanup.resource(blocked.owner, ContainmentResourceRole.WORKER);
        var workerAttempt = worker.beginAcquisition(blocked.owner);
        worker.recordAcquisition(blocked.owner, workerAttempt,
                new AcquisitionResult.AmbiguousCompletion(new ContainmentEvidence("possible-worker")));
        assertFalse(blocked.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        assertTrue(blocked.docker.removed.isEmpty());
    }

    @Test void n18DeadlineExhaustionRetainsIdentityAndN23OneOriginalDeadlineEverywhere() {
        var ticks = new AtomicLong(); Fixture fixture = fixture(
                ContainmentDeadline.after(Duration.ofNanos(10), ticks::get), DockerNetworkAuthority.Boundaries.NONE);
        fixture.docker.create.add(ok(ID, 1)); fixture.docker.inspect.add(ok(inspect(fixture, ID), 2)); fixture.acquire();
        ticks.set(10); assertFalse(fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        assertEquals(ID, fixture.cleanup.networkLedger(fixture.owner).get(0).immutableId());
        assertTrue(fixture.docker.deadlines.stream().allMatch(value -> value == fixture.deadline));
    }

    @Test void n19TerminalPublicationRacingLateHandoffCannotBecomeSafe() throws Exception {
        BlockingHooks hooks = new BlockingHooks(6); Fixture fixture = fixture(
                ContainmentDeadline.after(Duration.ofSeconds(5)), hooks);
        fixture.docker.create.add(notDispatched(1)); fixture.acquire();
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 2)); fixture.docker.remove.add(ok("", 3));
        fixture.docker.absence.add(absent(4));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var late = executor.submit(() -> fixture.cleanup.recordLateNetworkAcquisition(
                    fixture.owner, fixture.attempt, ID));
            assertTrue(hooks.entered.await(2, TimeUnit.SECONDS));
            var published = fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome);
            assertTrue(published.safe()); hooks.release.countDown();
            assertEquals(DockerNetworkAuthority.AcquisitionStatus.LATE_RETAINED, late.get());
            assertFalse(published.safe()); assertTrue(published.compromised());
        }
    }

    @Test void concurrentSameIdHandoffsAreIdempotentAndRetainOneReachableIdentity() throws Exception {
        BarrierHooks hooks = new BarrierHooks(); Fixture fixture = fixture(
                ContainmentDeadline.after(Duration.ofSeconds(5)), hooks);
        fixture.docker.create.add(notDispatched(1)); fixture.acquire();
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 3));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> fixture.cleanup.recordLateNetworkAcquisition(
                    fixture.owner, fixture.attempt, ID));
            var second = executor.submit(() -> fixture.cleanup.recordLateNetworkAcquisition(
                    fixture.owner, fixture.attempt, ID));
            assertTrue(List.of(first.get(), second.get()).contains(
                    DockerNetworkAuthority.AcquisitionStatus.LATE_RETAINED));
        }
        assertEquals(List.of(ID), fixture.cleanup.networkLedger(fixture.owner).stream()
                .map(DockerNetworkAuthority.LedgerEntry::immutableId).toList());
    }

    @Test void concurrentDifferentIdHandoffsRetainEveryReachableIdentity() throws Exception {
        BarrierHooks hooks = new BarrierHooks(); Fixture fixture = fixture(
                ContainmentDeadline.after(Duration.ofSeconds(5)), hooks);
        fixture.docker.create.add(notDispatched(1)); fixture.acquire();
        fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
        fixture.docker.inspect.add(ok(inspect(fixture, ID2), 3));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> fixture.cleanup.recordLateNetworkAcquisition(
                    fixture.owner, fixture.attempt, ID));
            var second = executor.submit(() -> fixture.cleanup.recordLateNetworkAcquisition(
                    fixture.owner, fixture.attempt, ID2));
            first.get(); second.get();
        }
        assertEquals(java.util.Set.of(ID, ID2), fixture.cleanup.networkLedger(fixture.owner).stream()
                .map(DockerNetworkAuthority.LedgerEntry::immutableId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(fixture.cleanup.networkLedger(fixture.owner).stream()
                .allMatch(DockerNetworkAuthority.LedgerEntry::conflict));
    }

    @Test void dependencyRegistrationRacesCleanupAsOneSerializedMutation() throws Exception {
        BlockingHooks hooks = new BlockingHooks(8); Fixture fixture = fixture(
                ContainmentDeadline.after(Duration.ofSeconds(5)), hooks);
        fixture.docker.create.add(ok(ID, 1)); fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
        fixture.docker.remove.add(ok("", 3)); fixture.docker.absence.add(absent(4));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var acquisition = executor.submit(fixture::acquire);
            assertTrue(hooks.entered.await(2, TimeUnit.SECONDS));
            var cleanup = executor.submit(() -> fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome));
            assertFalse(cleanup.isDone()); hooks.release.countDown();
            assertEquals(DockerNetworkAuthority.AcquisitionStatus.ACQUIRED, acquisition.get());
            assertTrue(cleanup.get().safe());
        }
        assertEquals(List.of(ID), fixture.docker.removed);
    }

    @Test void lateOwnershipRacingActiveCleanupIsRetainedAndReconciledOnce() throws Exception {
        Fixture fixture = acquired(); fixture.docker.blockRemove = true;
        fixture.docker.remove.add(ok("", 3)); fixture.docker.absence.add(absent(4));
        fixture.docker.inspect.add(ok(inspect(fixture, ID2), 5));
        fixture.docker.remove.add(ok("", 6)); fixture.docker.absence.add(absent(7));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cleanup = executor.submit(() -> fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome));
            assertTrue(fixture.docker.removeEntered.await(2, TimeUnit.SECONDS));
            var late = executor.submit(() -> fixture.cleanup.recordLateNetworkAcquisition(
                    fixture.owner, fixture.attempt, ID2));
            assertFalse(late.isDone()); fixture.docker.removeRelease.countDown();
            cleanup.get(); late.get();
        }
        assertEquals(List.of(ID, ID2), fixture.docker.removed);
        assertEquals(2, fixture.docker.removed.stream().distinct().count());
    }

    @Test void lateReconciliationResumesAbsenceWithoutRepeatingSuccessfulRemove() {
        Fixture fixture = acquired();
        fixture.docker.remove.add(ok("", 3)); fixture.docker.absence.add(timeout(4));
        assertFalse(fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        var first = fixture.cleanup.networkLedger(fixture.owner).get(0);
        assertEquals(DockerNetworkAuthority.RemovalPhase.AWAITING_ABSENCE, first.removalPhase());
        assertEquals("removed", first.removeResult()); assertEquals("unproved", first.absenceResult());

        fixture.docker.inspect.add(ok(inspect(fixture, ID2), 5));
        fixture.docker.remove.add(ok("", 6));
        fixture.docker.absence.add(absent(7)); fixture.docker.absence.add(absent(8));
        assertEquals(DockerNetworkAuthority.AcquisitionStatus.CONFLICT,
                fixture.cleanup.recordLateNetworkAcquisition(fixture.owner, fixture.attempt, ID2));

        assertEquals(List.of(ID, ID2), fixture.docker.removed);
        assertEquals(2, fixture.docker.removed.stream().distinct().count());
        var resumed = fixture.cleanup.networkLedger(fixture.owner).stream()
                .filter(entry -> entry.immutableId().equals(ID)).findFirst().orElseThrow();
        assertEquals(DockerNetworkAuthority.RemovalPhase.ABSENT, resumed.removalPhase());
        assertEquals("removed", resumed.removeResult());
        assertEquals("unproved;later-exact-id-absent", resumed.absenceResult());
    }

    @Test void failedRemoveIsNeverRetriedOrMaskedByLaterReconciliation() {
        Fixture fixture = acquired(); fixture.docker.remove.add(protocol(3));
        assertFalse(fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        var first = fixture.cleanup.networkLedger(fixture.owner).get(0);
        assertEquals(DockerNetworkAuthority.RemovalPhase.REMOVE_FAILED, first.removalPhase());
        assertEquals("failed", first.removeResult());

        fixture.docker.inspect.add(ok(inspect(fixture, ID2), 4));
        fixture.docker.remove.add(ok("", 5)); fixture.docker.absence.add(absent(6));
        fixture.cleanup.recordLateNetworkAcquisition(fixture.owner, fixture.attempt, ID2);

        assertEquals(List.of(ID, ID2), fixture.docker.removed);
        var retainedFailure = fixture.cleanup.networkLedger(fixture.owner).stream()
                .filter(entry -> entry.immutableId().equals(ID)).findFirst().orElseThrow();
        assertEquals(DockerNetworkAuthority.RemovalPhase.REMOVE_FAILED, retainedFailure.removalPhase());
        assertEquals("failed", retainedFailure.removeResult());
        assertEquals("remove-not-authoritative", retainedFailure.unresolvedReason());
    }

    @Test void n20AuthorityIsNonForgeableN21SurfaceIsClosedN22SocketAndWorkerNetworkStayClosed() {
        assertTrue(java.util.Arrays.stream(DockerCliNetworkControlPlane.class.getDeclaredConstructors())
                .allMatch(value -> Modifier.isPrivate(value.getModifiers())));
        assertTrue(java.util.Arrays.stream(DockerNetworkControlPlane.class.getMethods())
                .noneMatch(method -> java.util.Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> type == String[].class || type == Duration.class)));
        List<String> worker = SeleniumWorkerCommand.create(UUID.randomUUID(),
                "image@sha256:" + "c".repeat(64), SeleniumContainmentLimits.defaults());
        assertTrue(worker.containsAll(List.of("--network", "none")));
        assertFalse(String.join(" ", worker).contains("docker.sock"));
    }

    @Test void parserRejectsDuplicateZeroMultipleConcatenatedTrailingTruncatedAndUnexpectedAuthority() {
        Fixture fixture = fixture(); String valid = inspect(fixture, ID);
        List<String> invalid = List.of("[]", "[{},{}]", valid + valid, valid + " x",
                valid.substring(0, valid.length() - 2),
                valid.replaceFirst("\"Id\":", "\"Id\":\"" + ID + "\",\"Id\":"),
                valid.replace("\"Labels\":{", "\"Labels\":{\"com.automationstudio.extra\":\"x\","),
                valid.replace("\"Config\":[{", "\"Unexpected\":{},\"Config\":[{"),
                valid.replace("\"Subnet\":", "\"Unexpected\":\"x\",\"Subnet\":"));
        invalid.forEach(payload -> assertThrows(IllegalArgumentException.class,
                () -> DockerNetworkInspectParser.parse(payload, fixture.attempt.spec(), ID)));
    }

    @Test void cleanupContinuesAfterIndependentIdFailureAndDoesNotHideIt() {
        Fixture fixture = acquired(); fixture.docker.inspect.add(ok(inspect(fixture, ID2), 3));
        fixture.cleanup.recordLateNetworkAcquisition(fixture.owner, fixture.attempt, ID2);
        fixture.docker.remove.add(protocol(4)); fixture.docker.remove.add(ok("", 5));
        fixture.docker.absence.add(absent(6));
        assertFalse(fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        assertEquals(List.of(ID2, ID), fixture.docker.removed);
        assertEquals(2, fixture.cleanup.networkLedger(fixture.owner).size());
    }

    @Test void everyBoundaryUsesDeterministicLatchAndPreservesOneDeadline() throws Exception {
        for (int boundary = 1; boundary <= 8; boundary++) {
            BlockingHooks hooks = new BlockingHooks(boundary); Fixture fixture = fixture(
                    ContainmentDeadline.after(Duration.ofSeconds(5)), hooks);
            fixture.docker.create.add(ok(ID, 1)); fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var future = executor.submit(fixture::acquire); release(hooks);
                assertEquals(DockerNetworkAuthority.AcquisitionStatus.ACQUIRED, future.get());
            }
            assertTrue(fixture.docker.deadlines.stream().allMatch(value -> value == fixture.deadline));
        }
        BlockingHooks recovery = new BlockingHooks(9); Fixture recovering = fixture(
                ContainmentDeadline.after(Duration.ofSeconds(5)), recovery);
        recovering.docker.create.add(timeout(1)); recovering.docker.lookup.add(ok(ID, 2));
        recovering.docker.inspect.add(ok(inspect(recovering, ID), 3));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(recovering::acquire); release(recovery);
            assertEquals(DockerNetworkAuthority.AcquisitionStatus.ACQUIRED, future.get());
        }
        for (int boundary : List.of(10, 13)) {
            BlockingHooks hooks = new BlockingHooks(boundary); Fixture fixture = fixture(
                    ContainmentDeadline.after(Duration.ofSeconds(5)), hooks);
            fixture.docker.create.add(notDispatched(1)); fixture.acquire();
            if (boundary == 13) fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome);
            fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
            if (boundary == 13) { fixture.docker.remove.add(ok("", 3)); fixture.docker.absence.add(absent(4)); }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var future = executor.submit(() -> fixture.cleanup.recordLateNetworkAcquisition(
                        fixture.owner, fixture.attempt, ID)); release(hooks); future.get();
            }
        }
        for (int boundary : List.of(11, 12)) {
            BlockingHooks hooks = new BlockingHooks(boundary); Fixture fixture = fixture(
                    ContainmentDeadline.after(Duration.ofSeconds(5)), hooks);
            fixture.docker.create.add(ok(ID, 1)); fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
            fixture.acquire(); fixture.docker.remove.add(ok("", 3)); fixture.docker.absence.add(absent(4));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var future = executor.submit(() -> fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome));
                release(hooks); future.get();
            }
        }
    }

    @Test void deadlineExpiryAtEveryVerificationAndDestructiveBoundaryFailsClosed() {
        for (int boundary = 4; boundary <= 8; boundary++) {
            var ticks = new AtomicLong(); var hooks = new ExpiryHooks(boundary, ticks);
            Fixture fixture = fixture(ContainmentDeadline.after(Duration.ofNanos(10), ticks::get), hooks);
            fixture.docker.create.add(ok(ID, 1)); fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
            DockerNetworkAuthority.AcquisitionStatus status = fixture.acquire();
            assertNotEquals(DockerNetworkAuthority.AcquisitionStatus.ACQUIRED, status, "boundary " + boundary);
            assertFalse(fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
        }
        for (int boundary : List.of(11, 12)) {
            var ticks = new AtomicLong(); var hooks = new ExpiryHooks(boundary, ticks);
            Fixture fixture = fixture(ContainmentDeadline.after(Duration.ofNanos(10), ticks::get), hooks);
            fixture.docker.create.add(ok(ID, 1)); fixture.docker.inspect.add(ok(inspect(fixture, ID), 2));
            fixture.acquire(); fixture.docker.remove.add(ok("", 3));
            if (boundary == 12) fixture.docker.absence.add(absent(4));
            assertFalse(fixture.owner.execute(DockerNetworkAuthorityTest::safeOutcome).safe());
            assertNotEquals(DockerNetworkAuthority.EntryState.OWNED_ABSENT,
                    fixture.cleanup.networkLedger(fixture.owner).get(0).state());
        }
    }

    private static void release(BlockingHooks hooks) throws InterruptedException {
        assertTrue(hooks.entered.await(2, TimeUnit.SECONDS), "boundary " + hooks.boundary);
        hooks.release.countDown();
    }

    private static Fixture acquired() { Fixture value = fixture(); value.docker.create.add(ok(ID, 1));
        value.docker.inspect.add(ok(inspect(value, ID), 2)); value.acquire(); return value; }
    private static Fixture fixture() { return fixture(ContainmentDeadline.after(Duration.ofSeconds(5)),
            DockerNetworkAuthority.Boundaries.NONE); }
    private static Fixture fixture(ContainmentDeadline deadline, DockerNetworkAuthority.Boundaries hooks) {
        try {
            var docker = new FakeNetwork(); var constructor = SingleOwnerCleanup.class.getDeclaredConstructor(
                    ContainmentDeadline.class, SingleOwnerCleanup.AwaitBoundary.class,
                    DockerControlPlane.class, DockerNetworkControlPlane.class,
                    DockerNetworkAuthority.Boundaries.class);
            constructor.setAccessible(true); var cleanup = constructor.newInstance(deadline,
                    SingleOwnerCleanup.AwaitBoundary.NONE, null, docker, hooks);
            var owner = cleanup.claim(); UUID execution = UUID.randomUUID();
            var attempt = cleanup.beginNetworkAcquisition(owner, execution);
            return new Fixture(deadline, docker, cleanup, owner, attempt, execution);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private record Fixture(ContainmentDeadline deadline, FakeNetwork docker, SingleOwnerCleanup cleanup,
            SingleOwnerCleanup.Claim owner, DockerNetworkAuthority.Attempt attempt, UUID execution) {
        DockerNetworkAuthority.AcquisitionStatus acquire() { return cleanup.acquireNetwork(owner, attempt); }
    }
    private static String inspect(Fixture fixture, String id) {
        var labels = fixture.attempt.spec().labels();
        return "[{\"Id\":\"" + id + "\",\"Driver\":\"bridge\",\"Scope\":\"local\"," +
                "\"Internal\":false,\"Attachable\":false,\"Ingress\":false,\"ConfigOnly\":false," +
                "\"ConfigFrom\":{\"Network\":\"\"},\"EnableIPv4\":true,\"EnableIPv6\":false," +
                "\"IPAM\":{\"Driver\":\"default\",\"Options\":{},\"Config\":[{" +
                "\"Subnet\":\"172.30.0.0/16\",\"Gateway\":\"172.30.0.1\",\"IPRange\":\"\"," +
                "\"AuxiliaryAddresses\":{}}]},\"Options\":{},\"Labels\":{" +
                labels.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                        .collect(java.util.stream.Collectors.joining(",")) + "}}]";
    }
    private static String workerInspect(UUID execution, String nonce, String id) {
        return "[{\"Id\":\"" + id + "\",\"Image\":\"sha256:" + "c".repeat(64)
                + "\",\"Config\":{\"Labels\":{\"automation-studio.execution\":\"" + execution
                + "\",\"automation-studio.role\":\"WORKER\",\"automation-studio.attempt\":\"" + nonce
                + "\"},\"Entrypoint\":[\"/opt/java/openjdk/bin/java\",\"-Xms16m\",\"-Xmx64m\",\"-Djava.io.tmpdir=/work/tmp\",\"-jar\",\"/opt/worker/worker.jar\"],\"Cmd\":[\""
                + execution + "\"],\"User\":\"10001:10001\",\"Env\":[\"LANG=C.UTF-8\"]},"
                + "\"HostConfig\":{\"ReadonlyRootfs\":true,\"RestartPolicy\":{\"Name\":\"no\",\"MaximumRetryCount\":0},"
                + "\"NetworkMode\":\"none\",\"Privileged\":false,\"PidMode\":\"private\",\"IpcMode\":\"private\"," +
                "\"CapAdd\":[],\"CapDrop\":[\"ALL\"],\"Devices\":[],\"Binds\":[],\"Mounts\":[],"
                + "\"Tmpfs\":{\"/work/runtime\":\"rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=33554432\","
                + "\"/work/tmp\":\"rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=8388608\"},"
                + "\"SecurityOpt\":[\"no-new-privileges:true\",\"seccomp=builtin\"]}}]";
    }
    private static String ownedIdentity(SingleOwnerCleanup.ResourceTransition transition) throws Exception {
        return owned(transition).identity().value();
    }
    private static ResourceDisposition.OwnedPresent owned(
            SingleOwnerCleanup.ResourceTransition transition) throws Exception {
        var field = SingleOwnerCleanup.ResourceTransition.class.getDeclaredField("owned");
        field.setAccessible(true); return (ResourceDisposition.OwnedPresent) field.get(transition);
    }
    private static DockerControlPlane.DockerTransportOutcome ok(String response, long revision) {
        return ok(response, revision, DAEMON); }
    private static DockerControlPlane.DockerTransportOutcome ok(String response, long revision,
            DockerControlPlane.DockerDaemonIdentity daemon) {
        return outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, 0, response, true, revision,
                DockerControlPlane.Semantic.NONE, daemon); }
    private static DockerControlPlane.DockerTransportOutcome absent(long revision) {
        return outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.COMPLETED, 1, "No such network " + ID, true, revision,
                DockerControlPlane.Semantic.EXACT_ID_NOT_FOUND, DAEMON); }
    private static DockerControlPlane.DockerTransportOutcome timeout(long revision) {
        return outcome(DockerControlPlane.Dispatch.MAY_HAVE_DISPATCHED,
                DockerControlPlane.Completion.TIMED_OUT, null, null, false, revision,
                DockerControlPlane.Semantic.NONE, DAEMON); }
    private static DockerControlPlane.DockerTransportOutcome protocol(long revision) {
        return outcome(DockerControlPlane.Dispatch.DEFINITELY_DISPATCHED,
                DockerControlPlane.Completion.PROTOCOL_FAILED, 1, "failure", true, revision,
                DockerControlPlane.Semantic.NONE, DAEMON); }
    private static DockerControlPlane.DockerTransportOutcome notDispatched(long revision) {
        return outcome(DockerControlPlane.Dispatch.DEFINITELY_NOT_DISPATCHED,
                DockerControlPlane.Completion.SPAWN_FAILED, null, null, true, revision,
                DockerControlPlane.Semantic.NONE, null); }
    private static DockerControlPlane.DockerTransportOutcome outcome(DockerControlPlane.Dispatch dispatch,
            DockerControlPlane.Completion completion, Integer exit, String response, boolean complete,
            long revision, DockerControlPlane.Semantic semantic,
            DockerControlPlane.DockerDaemonIdentity daemon) {
        return new DockerControlPlane.DockerTransportOutcome(dispatch, completion, exit, response,
                complete, revision, semantic, daemon); }
    private static SingleOwnerCleanup.TerminalOutcome safeOutcome() { return new SingleOwnerCleanup.TerminalOutcome(
            ExecutionOutcome.FAILED, ContainmentCode.ABSENT, AttachmentState.NOT_STARTED,
            DependencyEvidence.SATISFIED); }

    private static final class FakeNetwork implements DockerNetworkControlPlane {
        final ArrayDeque<DockerControlPlane.DockerTransportOutcome> create = new ArrayDeque<>(),
                lookup = new ArrayDeque<>(), inspect = new ArrayDeque<>(), remove = new ArrayDeque<>(),
                absence = new ArrayDeque<>();
        final List<ContainmentDeadline> deadlines = new ArrayList<>();
        final List<String> inspected = new ArrayList<>(), removed = new ArrayList<>();
        volatile boolean blockRemove;
        final CountDownLatch removeEntered = new CountDownLatch(1), removeRelease = new CountDownLatch(1);
        private synchronized DockerControlPlane.DockerTransportOutcome next(
                ArrayDeque<DockerControlPlane.DockerTransportOutcome> values, ContainmentDeadline deadline) {
            deadlines.add(deadline); return values.remove(); }
        public DockerControlPlane.DockerTransportOutcome create(NetworkCreateRequest request,
                ContainmentDeadline deadline) { return next(create, deadline); }
        public DockerControlPlane.DockerTransportOutcome lookupAttempt(NetworkAttemptLookup request,
                ContainmentDeadline deadline) { return next(lookup, deadline); }
        public DockerControlPlane.DockerTransportOutcome inspectExactId(String id,
                ContainmentDeadline deadline) { synchronized (inspected) { inspected.add(id); }
            var result = next(inspect, deadline);
            if (result.response() != null && result.response().contains("\"Id\":\"")) {
                String response = result.response().replaceFirst("\\\"Id\\\":\\\"[a-f0-9]{64}\\\"",
                        "\\\"Id\\\":\\\"" + id + "\\\"");
                return new DockerControlPlane.DockerTransportOutcome(result.dispatch(), result.completion(),
                        result.exitStatus(), response, result.responseComplete(),
                        result.observationRevision(), result.semantic(), result.daemonIdentity());
            }
            return result; }
        public DockerControlPlane.DockerTransportOutcome removeExactId(String id,
                ContainmentDeadline deadline) { synchronized (removed) { removed.add(id); }
            if (blockRemove) { blockRemove = false; removeEntered.countDown(); try {
                if (!removeRelease.await(2, TimeUnit.SECONDS)) throw new AssertionError("remove timeout");
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } }
            return next(remove, deadline); }
        public DockerControlPlane.DockerTransportOutcome inspectExactIdAbsence(String id,
                ContainmentDeadline deadline) { return next(absence, deadline); }
    }
    private static final class BarrierHooks implements DockerNetworkAuthority.Boundaries {
        final CyclicBarrier verified = new CyclicBarrier(2);
        public void afterVerificationBeforeHandoff() {
            try { verified.await(2, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
    }
    private static final class FakeContainer implements DockerControlPlane {
        final ArrayDeque<DockerTransportOutcome> create = new ArrayDeque<>(), inspect = new ArrayDeque<>(),
                remove = new ArrayDeque<>(), absence = new ArrayDeque<>();
        final List<String> removed = new ArrayList<>();
        public DockerTransportOutcome resolveImage(String value, ContainmentDeadline deadline) { return inspect.remove(); }
        public DockerTransportOutcome create(DockerCreateRequest value, ContainmentDeadline deadline) { return create.remove(); }
        public DockerTransportOutcome inspectExactId(String value, ContainmentDeadline deadline) { return inspect.remove(); }
        public DockerTransportOutcome lookupAttempt(DockerAttemptLookup value, ContainmentDeadline deadline) { throw new AssertionError(); }
        public DockerTransportOutcome removeExactId(String value, ContainmentDeadline deadline) {
            removed.add(value); return remove.remove(); }
        public DockerTransportOutcome inspectExactIdAbsence(String value, ContainmentDeadline deadline) {
            return absence.remove(); }
    }
    private static final class BlockingHooks implements DockerNetworkAuthority.Boundaries {
        final int boundary; final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BlockingHooks(int boundary) { this.boundary = boundary; }
        private void hit(int value) { if (boundary == value) { entered.countDown(); try {
            if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("boundary timeout");
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } } }
        public void afterVerificationBeforeHandoff() { hit(6); }
        public void beforeCreateDispatch() { hit(1); }
        public void afterCreateDispatch() { hit(2); }
        public void afterPossibleSideEffect() { hit(3); }
        public void afterIdBeforeInspect() { hit(4); }
        public void afterInspectBeforeVerification() { hit(5); }
        public void duringHandoff() { hit(7); }
        public void duringDependencyRegistration() { hit(8); }
        public void duringRecovery() { hit(9); }
        public void cleanupStartVsLateOwnership() { hit(10); }
        public void beforeNetworkRemoveEligibility() { hit(11); }
        public void removeVsAbsenceObservation() { hit(12); }
        public void terminalPublicationVsLateEvidence() { hit(13); }
    }
    private static final class ExpiryHooks implements DockerNetworkAuthority.Boundaries {
        final int boundary; final AtomicLong ticks;
        ExpiryHooks(int boundary, AtomicLong ticks) { this.boundary = boundary; this.ticks = ticks; }
        private void hit(int value) { if (boundary == value) ticks.set(10); }
        public void afterIdBeforeInspect() { hit(4); }
        public void afterInspectBeforeVerification() { hit(5); }
        public void afterVerificationBeforeHandoff() { hit(6); }
        public void duringHandoff() { hit(7); }
        public void duringDependencyRegistration() { hit(8); }
        public void beforeNetworkRemoveEligibility() { hit(11); }
        public void removeVsAbsenceObservation() { hit(12); }
    }
}
