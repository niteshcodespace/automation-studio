package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SingleOwnerCleanupTest {
    private static final ContainmentEvidence EVIDENCE = new ContainmentEvidence("observed");

    @Test void exactlyOneOwnerAndAllObserversShareDeadlineCompletionAndTerminalState() throws Exception {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(1));
        var cleanup = new SingleOwnerCleanup(deadline);
        List<Callable<SingleOwnerCleanup.Claim>> calls = new ArrayList<>();
        for (int index = 0; index < 16; index++) calls.add(cleanup::claim);
        List<SingleOwnerCleanup.Claim> claims;
        try (var pool = Executors.newFixedThreadPool(8)) {
            claims = pool.invokeAll(calls).stream().map(future -> {
                try { return future.get(); } catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
        }
        assertEquals(1, claims.stream().filter(SingleOwnerCleanup.Claim::owner).count());
        var owner = claims.stream().filter(SingleOwnerCleanup.Claim::owner).findFirst().orElseThrow();
        var state = owner.execute(SingleOwnerCleanupTest::safeOutcome);
        for (var claim : claims) {
            assertSame(deadline, claim.deadline());
            assertSame(state, claim.completion().await());
        }
        assertTrue(state.safe());
    }

    @Test void fixedRoleRegistrationRejectsSyntheticReplacementAndIsOneShot() {
        var cleanup = cleanup(); var owner = cleanup.claim();
        var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        assertSame(worker, cleanup.resource(owner, ContainmentResourceRole.WORKER));
        worker.closeWithoutAttempt(owner);
        assertThrows(IllegalStateException.class, () -> worker.closeWithoutAttempt(owner));
        owner.execute(SingleOwnerCleanupTest::safeOutcome);
        assertThrows(IllegalStateException.class,
                () -> cleanup.resource(owner, ContainmentResourceRole.WORKER));
        assertThrows(IllegalStateException.class,
                () -> owner.execute(SingleOwnerCleanupTest::safeOutcome));
    }

    @Test void trustedPrivateAbsenceSourceBindsCausalOwnershipAndRejectsReplayMismatchAndPreCleanup()
            throws Exception {
        var cleanup = trustedCleanup(ContainmentDeadline.after(Duration.ofSeconds(2)),
                SingleOwnerCleanup.AwaitBoundary.NONE);
        var owner = cleanup.claim();
        var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        var network = cleanup.resource(owner, ContainmentResourceRole.NETWORK);
        var workerOwned = owned(worker, owner, "worker-id");
        var networkOwned = owned(network, owner, "network-id");
        assertThrows(IllegalStateException.class, () -> worker.verifyAbsent(owner, workerOwned));
        var state = owner.execute(() -> {
            assertThrows(IllegalArgumentException.class,
                    () -> worker.verifyAbsent(owner, networkOwned));
            var absent = worker.verifyAbsent(owner, workerOwned);
            assertThrows(IllegalStateException.class, () -> worker.verifyAbsent(owner, workerOwned));
            return safeOutcome();
        });
        assertFalse(state.safe());
        assertInstanceOf(ResourceDisposition.OwnedAbsent.class, state.report().worker());
        assertInstanceOf(ResourceDisposition.OwnedPresent.class, state.report().network());
    }

    @Test void trustedAbsenceProducesSafeStateWhenEveryOtherFixedRoleCloses() throws Exception {
        var cleanup = trustedCleanup(ContainmentDeadline.after(Duration.ofSeconds(2)),
                SingleOwnerCleanup.AwaitBoundary.NONE);
        var owner = cleanup.claim(); var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        var owned = owned(worker, owner, "worker-id");
        var state = owner.execute(() -> { worker.verifyAbsent(owner, owned); return safeOutcome(); });
        assertTrue(state.safe());
    }

    @Test void ordinaryPackageSurfaceCannotSupplyVerifierLambdaOrSuccessfulObservation() {
        assertFalse(ArraysSupport.hasConstructorParameterNamed(
                SingleOwnerCleanup.class, "AuthoritativeAbsenceVerifier"));
        assertFalse(ArraysSupport.hasConstructorParameterNamed(
                SingleOwnerCleanup.class, "AuthoritativeAbsenceSource"));
        assertTrue(ArraysSupport.nestedClass(SingleOwnerCleanup.class,
                "AuthoritativeAbsenceSource").map(Class::getModifiers)
                .map(Modifier::isPrivate).orElseThrow());
        assertTrue(ArraysSupport.nestedClass(SingleOwnerCleanup.class,
                "AuthoritativeAbsenceObservation").map(Class::getModifiers)
                .map(Modifier::isPrivate).orElseThrow());
    }

    @Test void unavailableAuthoritativeAbsenceFailsClosedAndPublishesCanonicalUnsafeState() {
        var cleanup = cleanup(); var owner = cleanup.claim(); var observer = cleanup.claim();
        var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        var owned = owned(worker, owner, "worker-id");
        var state = owner.execute(() -> { worker.verifyAbsent(owner, owned); return safeOutcome(); });
        assertFalse(state.safe());
        assertEquals(ContainmentCode.UNSAFE, state.report().containmentCode());
        assertSame(state, assertDoesNotThrow(observer.completion()::await));
    }

    @Test void lateOwnershipMonotonicallyCompromisesSharedPublishedState() throws Exception {
        var cleanup = cleanup(); var owner = cleanup.claim(); var observer = cleanup.claim();
        var state = owner.execute(SingleOwnerCleanupTest::safeOutcome);
        assertTrue(state.safe());
        assertTrue(cleanup.recordOwnership(new OwnershipEvidence(
                new ContainmentIdentity("late"), EVIDENCE)));
        assertFalse(state.safe()); assertTrue(state.compromised());
        assertSame(state, observer.completion().await());
    }

    @Test void ownerExceptionAndInterruptionPublishOneUnsafeState() throws Exception {
        assertOwnerFailure(() -> { throw new IllegalStateException("failure"); }, false);
        assertOwnerFailure(() -> { throw new InterruptedException("stop"); }, true);
    }

    @Test void observerInterruptionDoesNotPublishFallback() throws Exception {
        var cleanup = cleanup(); cleanup.claim(); var observer = cleanup.claim();
        var interrupted = new AtomicBoolean(); var unexpected = new AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try { observer.completion().await(); }
            catch (InterruptedException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable failure) { unexpected.set(failure); }
        });
        while (thread.getState() != Thread.State.TIMED_WAITING) Thread.onSpinWait();
        thread.interrupt(); thread.join(1000);
        assertTrue(interrupted.get()); assertNull(unexpected.get());
        assertFalse(observer.completion().isDone());
    }

    @Test void trueNonPublicationTimesOutOnOriginalDeadline() {
        var ticks = new AtomicLong();
        var deadline = ContainmentDeadline.after(Duration.ofNanos(10), ticks::get);
        var cleanup = new SingleOwnerCleanup(deadline); cleanup.claim(); var observer = cleanup.claim();
        ticks.set(10);
        assertThrows(TimeoutException.class, observer.completion()::await);
        assertSame(deadline, observer.deadline()); assertFalse(observer.completion().isDone());
    }

    @Test void publicationBetweenInitialReadAndZeroBudgetReturnsCanonicalState() throws Exception {
        var ticks = new AtomicLong(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var boundary = new SingleOwnerCleanup.AwaitBoundary() {
            @Override public void afterInitialCompletionRead() throws InterruptedException {
                entered.countDown(); assertTrue(release.await(1, TimeUnit.SECONDS));
            }
        };
        var cleanup = new SingleOwnerCleanup(
                ContainmentDeadline.after(Duration.ofNanos(10), ticks::get), boundary);
        var owner = cleanup.claim(); var observer = cleanup.claim();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var waiting = pool.submit(observer.completion()::await);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var state = owner.execute(SingleOwnerCleanupTest::safeOutcome);
            ticks.set(10); release.countDown(); assertSame(state, waiting.get());
        }
    }

    @Test void publicationRacingTimedTimeoutReturnsCanonicalState() throws Exception {
        var timedOut = new CountDownLatch(1); var release = new CountDownLatch(1);
        var boundary = new SingleOwnerCleanup.AwaitBoundary() {
            @Override public void afterTimedTimeout() throws InterruptedException {
                timedOut.countDown(); assertTrue(release.await(1, TimeUnit.SECONDS));
            }
        };
        var cleanup = new SingleOwnerCleanup(
                ContainmentDeadline.after(Duration.ofMillis(100)), boundary);
        var owner = cleanup.claim(); var observer = cleanup.claim();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var waiting = pool.submit(observer.completion()::await);
            assertTrue(timedOut.await(1, TimeUnit.SECONDS));
            var state = owner.execute(SingleOwnerCleanupTest::safeOutcome);
            release.countDown(); assertSame(state, waiting.get());
        }
    }

    private static ResourceDisposition.OwnedPresent owned(SingleOwnerCleanup.ResourceTransition resource,
            SingleOwnerCleanup.Claim owner, String identity) {
        var attempt = resource.beginAcquisition(owner);
        return (ResourceDisposition.OwnedPresent) resource.recordAcquisition(owner, attempt,
                new AcquisitionResult.CreatedAndOwned(new ContainmentIdentity(identity), EVIDENCE));
    }

    private static void assertOwnerFailure(SingleOwnerCleanup.OwnerCleanupWork work,
            boolean interrupted) throws Exception {
        var cleanup = cleanup(); var owner = cleanup.claim(); var observer = cleanup.claim();
        var state = owner.execute(work);
        assertEquals(interrupted, Thread.currentThread().isInterrupted());
        if (interrupted) Thread.interrupted();
        assertFalse(state.safe()); assertSame(state, observer.completion().await());
        assertEquals(ContainmentCode.UNSAFE, state.report().containmentCode());
    }

    private static SingleOwnerCleanup cleanup() {
        return new SingleOwnerCleanup(ContainmentDeadline.after(Duration.ofSeconds(2)));
    }

    private static SingleOwnerCleanup trustedCleanup(ContainmentDeadline deadline,
            SingleOwnerCleanup.AwaitBoundary boundary) throws Exception {
        var constructor = SingleOwnerCleanup.class.getDeclaredConstructor(
                ContainmentDeadline.class, SingleOwnerCleanup.AwaitBoundary.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(deadline, boundary, true);
    }

    private static SingleOwnerCleanup.TerminalOutcome safeOutcome() {
        return new SingleOwnerCleanup.TerminalOutcome(ExecutionOutcome.FAILED,
                ContainmentCode.ABSENT, AttachmentState.NOT_STARTED,
                DependencyEvidence.SATISFIED);
    }

    private static final class ArraysSupport {
        static boolean hasConstructorParameterNamed(Class<?> type, String simpleName) {
            return java.util.Arrays.stream(type.getDeclaredConstructors())
                    .flatMap(constructor -> java.util.Arrays.stream(constructor.getParameterTypes()))
                    .anyMatch(parameter -> parameter.getSimpleName().equals(simpleName));
        }
        static java.util.Optional<Class<?>> nestedClass(Class<?> type, String simpleName) {
            return java.util.Arrays.stream(type.getDeclaredClasses())
                    .filter(candidate -> candidate.getSimpleName().equals(simpleName)).findFirst();
        }
    }
}
