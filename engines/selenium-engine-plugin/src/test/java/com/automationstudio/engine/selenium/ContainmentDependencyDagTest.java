package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ContainmentDependencyDagTest {
    private enum Node { WORKER, ANCHOR, GATEWAY, ENDPOINTS, NETWORK, AUDIT }

    @Test void immutableSnapshotRejectsNullAndDoesNotFollowCallerMutation() {
        var mutable = new HashMap<Node, DependencyEvidence>();
        mutable.put(Node.WORKER, DependencyEvidence.SATISFIED);
        var snapshot = new DependencyEvidenceSnapshot<>(1, mutable);
        mutable.put(Node.WORKER, DependencyEvidence.DISPROVED);
        assertEquals(DependencyEvidence.SATISFIED, snapshot.evidence().get(Node.WORKER));
        mutable.put(Node.WORKER, null);
        assertThrows(NullPointerException.class, () -> new DependencyEvidenceSnapshot<>(2, mutable));
    }

    @Test void unknownDisprovedMultiplePrerequisitesAndIndependentSiblingsFailClosed() {
        var dag = dag();
        assertEquals(DependencyEvidence.SATISFIED, status(dag, Node.WORKER, Map.of()));
        assertEquals(DependencyEvidence.UNKNOWN, status(dag, Node.ANCHOR, Map.of()));
        assertEquals(DependencyEvidence.DISPROVED, status(dag, Node.ANCHOR,
                Map.of(Node.WORKER, DependencyEvidence.DISPROVED)));
        assertEquals(DependencyEvidence.SATISFIED, status(dag, Node.ANCHOR,
                Map.of(Node.WORKER, DependencyEvidence.SATISFIED)));
        assertEquals(DependencyEvidence.SATISFIED, status(dag, Node.GATEWAY,
                Map.of(Node.WORKER, DependencyEvidence.SATISFIED)));
        assertFalse(dag.prerequisites(Node.ANCHOR).contains(Node.GATEWAY));
        assertEquals(DependencyEvidence.UNKNOWN, status(dag, Node.NETWORK,
                Map.of(Node.ANCHOR, DependencyEvidence.SATISFIED,
                        Node.GATEWAY, DependencyEvidence.SATISFIED)));
        assertEquals(DependencyEvidence.SATISFIED, status(dag, Node.NETWORK,
                Map.of(Node.ANCHOR, DependencyEvidence.SATISFIED,
                        Node.GATEWAY, DependencyEvidence.SATISFIED,
                        Node.ENDPOINTS, DependencyEvidence.SATISFIED)));
    }

    @Test void selfDirectIndirectCyclesAndUnknownNodesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ContainmentDependencyDag<>(
                Map.of(Node.ANCHOR, Set.of(Node.ANCHOR))));
        assertThrows(IllegalArgumentException.class, () -> new ContainmentDependencyDag<>(Map.of(
                Node.ANCHOR, Set.of(Node.NETWORK), Node.NETWORK, Set.of(Node.ANCHOR))));
        assertThrows(IllegalArgumentException.class, () -> new ContainmentDependencyDag<>(Map.of(
                Node.WORKER, Set.of(Node.ANCHOR), Node.ANCHOR, Set.of(Node.NETWORK),
                Node.NETWORK, Set.of(Node.WORKER))));
        assertThrows(IllegalArgumentException.class, () -> new ContainmentDependencyDag<>(
                Map.of(Node.NETWORK, Set.of(Node.ANCHOR))));
        assertThrows(NullPointerException.class,
                () -> dag().eligibility(null, new DependencyEvidenceSnapshot<>(0, Map.of())));
        var small = new ContainmentDependencyDag<>(Map.of(Node.WORKER, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> small.eligibility(Node.AUDIT, new DependencyEvidenceSnapshot<>(0, Map.of())));
    }

    @Test void diamondGraphEvaluatesAllBranches() {
        var diamond = new ContainmentDependencyDag<>(Map.of(
                Node.WORKER, Set.of(), Node.ANCHOR, Set.of(Node.WORKER),
                Node.GATEWAY, Set.of(Node.WORKER), Node.NETWORK, Set.of(Node.ANCHOR, Node.GATEWAY)));
        assertEquals(DependencyEvidence.SATISFIED, status(diamond, Node.NETWORK, Map.of(
                Node.ANCHOR, DependencyEvidence.SATISFIED,
                Node.GATEWAY, DependencyEvidence.SATISFIED)));
        assertEquals(DependencyEvidence.UNKNOWN, status(diamond, Node.NETWORK,
                Map.of(Node.ANCHOR, DependencyEvidence.SATISFIED)));
    }

    @Test void ambiguousAndLateUpstreamExplicitlyBlockDependents() {
        var evidence = new ContainmentEvidence("observed");
        var identity = new ContainmentIdentity("immutable-id");
        assertEquals(DependencyEvidence.DISPROVED,
                DependencyEvidence.from(new ResourceDisposition.Ambiguous(evidence)));
        assertEquals(DependencyEvidence.DISPROVED, DependencyEvidence.from(
                new ResourceDisposition.LateOwnedPresent(identity, evidence)));
        assertEquals(DependencyEvidence.UNKNOWN, DependencyEvidence.from(
                new ResourceDisposition.Unknown(evidence)));
    }

    @Test void staleDecisionIsRejectedAndActionUsesCurrentOwnerRevision() {
        var cleanup = cleanup(); var owner = cleanup.claim(); var dependencies = cleanup.dependencies(dag());
        dependencies.update(owner, Node.WORKER, DependencyEvidence.SATISFIED);
        var old = dag().eligibility(Node.ANCHOR, dependencies.snapshot());
        dependencies.update(owner, Node.WORKER, DependencyEvidence.DISPROVED);
        assertThrows(IllegalStateException.class,
                () -> dependencies.performDecision(owner, old, () -> fail("stale action ran")));
        var ran = new AtomicBoolean();
        assertEquals(DependencyEvidence.DISPROVED,
                dependencies.performIfEligible(owner, Node.ANCHOR, () -> ran.set(true)).status());
        assertFalse(ran.get());
    }

    @Test void eligibilityAndActionAreSerializedAgainstConcurrentEvidenceMutation() throws Exception {
        var cleanup = cleanup(); var owner = cleanup.claim(); var dependencies = cleanup.dependencies(dag());
        dependencies.update(owner, Node.WORKER, DependencyEvidence.SATISFIED);
        var actionEntered = new CountDownLatch(1); var releaseAction = new CountDownLatch(1);
        var mutationStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var action = pool.submit(() -> dependencies.performIfEligible(owner, Node.ANCHOR, () -> {
                actionEntered.countDown();
                try { assertTrue(releaseAction.await(1, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            }));
            assertTrue(actionEntered.await(1, TimeUnit.SECONDS));
            var mutation = pool.submit(() -> {
                mutationStarted.countDown();
                dependencies.update(owner, Node.WORKER, DependencyEvidence.DISPROVED);
            });
            assertTrue(mutationStarted.await(1, TimeUnit.SECONDS));
            assertEquals(java.util.concurrent.Future.State.RUNNING, mutation.state());
            releaseAction.countDown();
            action.get(); mutation.get();
        }
        assertEquals(DependencyEvidence.DISPROVED,
                dependencies.snapshot().evidence().get(Node.WORKER));
    }

    @Test void observerCannotMutateOrAuthorizeAction() {
        var cleanup = cleanup(); var owner = cleanup.claim(); var observer = cleanup.claim();
        var dependencies = cleanup.dependencies(dag());
        dependencies.update(owner, Node.WORKER, DependencyEvidence.SATISFIED);
        assertThrows(IllegalStateException.class,
                () -> dependencies.update(observer, Node.WORKER, DependencyEvidence.DISPROVED));
        assertThrows(IllegalStateException.class,
                () -> dependencies.performIfEligible(observer, Node.ANCHOR, () -> {}));
    }

    private static DependencyEvidence status(ContainmentDependencyDag<Node> dag, Node node,
            Map<Node, DependencyEvidence> evidence) {
        return dag.eligibility(node, new DependencyEvidenceSnapshot<>(1, evidence)).status();
    }
    private static SingleOwnerCleanup cleanup() {
        return new SingleOwnerCleanup(ContainmentDeadline.after(Duration.ofSeconds(1)));
    }
    private static ContainmentDependencyDag<Node> dag() {
        return new ContainmentDependencyDag<>(Map.of(
                Node.WORKER, Set.of(), Node.ANCHOR, Set.of(Node.WORKER),
                Node.GATEWAY, Set.of(Node.WORKER), Node.ENDPOINTS, Set.of(),
                Node.NETWORK, Set.of(Node.ANCHOR, Node.GATEWAY, Node.ENDPOINTS),
                Node.AUDIT, Set.of(Node.ANCHOR, Node.GATEWAY)));
    }
}
