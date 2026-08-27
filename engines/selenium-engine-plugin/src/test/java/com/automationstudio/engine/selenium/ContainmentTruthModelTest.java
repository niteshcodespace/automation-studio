package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContainmentTruthModelTest {
    private static final ContainmentEvidence TEXT = new ContainmentEvidence("caller-text");
    private static final ContainmentIdentity ID = new ContainmentIdentity("immutable-id");

    @Test void arbitraryTextAndUntrustedAcquisitionResultsCannotCreateTerminalSafety() {
        for (AcquisitionResult result : java.util.List.of(
                new AcquisitionResult.DefiniteFailure(TEXT),
                new AcquisitionResult.AmbiguousCompletion(TEXT),
                new AcquisitionResult.AmbiguousOwnership(ID, TEXT),
                new AcquisitionResult.ForeignCollision(ID, TEXT),
                new AcquisitionResult.CreatedAndOwned(ID, TEXT))) {
            assertFalse(stateFor(result).safe(), result.getClass().getSimpleName());
        }
    }

    @Test void fixedUntouchedTransitionsCloseOnlyInsideTheirCleanupAuthority() {
        var cleanup = cleanup(); var owner = cleanup.claim();
        var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        assertSame(worker, cleanup.resource(owner, ContainmentResourceRole.WORKER));
        var state = owner.execute(ContainmentTruthModelTest::safeOutcome);
        assertTrue(state.safe());
        assertThrows(IllegalStateException.class,
                () -> cleanup.resource(owner, ContainmentResourceRole.WORKER));
    }

    @Test void crossCleanupDispositionIsRejectedAtTerminalConsumption() throws Exception {
        var first = safeState(); var second = cleanup();
        assertThrows(IllegalArgumentException.class, () -> issue(second,
                dispositions(first.report()), revisions(), safeOutcome()));
    }

    @Test void crossRoleAndDispositionReuseAreRejectedAtTerminalConsumption() throws Exception {
        var state = safeState(); var cleanup = cleanup();
        var wrongRole = dispositions(state.report());
        wrongRole.put(ContainmentResourceRole.NETWORK, state.report().worker());
        assertThrows(IllegalArgumentException.class,
                () -> issue(cleanup, wrongRole, revisions(), safeOutcome()));

        var reused = new EnumMap<ContainmentResourceRole, ResourceDisposition>(
                ContainmentResourceRole.class);
        for (ContainmentResourceRole role : ContainmentResourceRole.values()) {
            reused.put(role, state.report().worker());
        }
        assertThrows(IllegalArgumentException.class,
                () -> issue(cleanup, reused, revisions(), safeOutcome()));
    }

    @Test void crossRevisionDispositionIsRejectedAtTerminalConsumption() throws Exception {
        var state = safeState(); var cleanup = cleanup(); var wrong = revisions();
        wrong.put(ContainmentResourceRole.WORKER,
                wrong.get(ContainmentResourceRole.WORKER) + 1);
        assertThrows(IllegalArgumentException.class,
                () -> issue(cleanup, dispositions(state.report()), wrong, safeOutcome()));
    }

    @Test void terminalReportAndAuthoritativeStateCannotBeDirectlyConstructed() {
        assertTrue(Arrays.stream(ContainmentTerminalReport.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(AuthoritativeContainmentState.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(SingleOwnerCleanup.ProofIssuer.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test void foreignExclusionRemainsUnavailableUntilD2a2AuthorityExists() {
        assertTrue(Arrays.stream(ForeignExclusionProof.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(ResourceDisposition.ForeignExcluded.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertFalse(stateFor(new AcquisitionResult.ForeignCollision(ID, TEXT)).safe());
    }

    @Test void defaultCleanupCannotManufactureAuthoritativeAbsence() {
        var cleanup = cleanup(); var owner = cleanup.claim();
        var resource = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        var attempt = resource.beginAcquisition(owner);
        var owned = (ResourceDisposition.OwnedPresent) resource.recordAcquisition(owner, attempt,
                new AcquisitionResult.CreatedAndOwned(ID, TEXT));
        var state = owner.execute(() -> {
            resource.verifyAbsent(owner, owned);
            return safeOutcome();
        });
        assertFalse(state.safe());
        assertEquals(ContainmentCode.UNSAFE, state.report().containmentCode());
    }

    @Test void executionAndContainmentOutcomesRemainIndependent() {
        var cleanup = cleanup(); var owner = cleanup.claim();
        var unsafe = owner.execute(() -> new SingleOwnerCleanup.TerminalOutcome(
                ExecutionOutcome.SUCCEEDED, ContainmentCode.UNSAFE, AttachmentState.NOT_STARTED,
                DependencyEvidence.SATISFIED));
        assertFalse(unsafe.safe());
        assertEquals(ExecutionOutcome.SUCCEEDED, unsafe.report().executionOutcome());
        assertEquals(ContainmentCode.UNSAFE, unsafe.report().containmentCode());
    }

    private static AuthoritativeContainmentState stateFor(AcquisitionResult result) {
        var cleanup = cleanup(); var owner = cleanup.claim();
        var worker = cleanup.resource(owner, ContainmentResourceRole.WORKER);
        var attempt = worker.beginAcquisition(owner); worker.recordAcquisition(owner, attempt, result);
        return owner.execute(ContainmentTruthModelTest::safeOutcome);
    }

    private static AuthoritativeContainmentState safeState() {
        var cleanup = cleanup(); return cleanup.claim().execute(ContainmentTruthModelTest::safeOutcome);
    }

    private static SingleOwnerCleanup cleanup() {
        return new SingleOwnerCleanup(ContainmentDeadline.after(Duration.ofSeconds(2)));
    }

    private static SingleOwnerCleanup.TerminalOutcome safeOutcome() {
        return new SingleOwnerCleanup.TerminalOutcome(ExecutionOutcome.FAILED,
                ContainmentCode.ABSENT, AttachmentState.NOT_STARTED,
                DependencyEvidence.SATISFIED);
    }

    private static EnumMap<ContainmentResourceRole, ResourceDisposition> dispositions(
            ContainmentTerminalReport report) {
        var result = new EnumMap<ContainmentResourceRole, ResourceDisposition>(
                ContainmentResourceRole.class);
        result.put(ContainmentResourceRole.NETWORK, report.network());
        result.put(ContainmentResourceRole.ANCHOR, report.anchor());
        result.put(ContainmentResourceRole.BOOTSTRAP, report.bootstrap());
        result.put(ContainmentResourceRole.GATEWAY, report.gateway());
        result.put(ContainmentResourceRole.WORKER, report.worker());
        return result;
    }

    private static EnumMap<ContainmentResourceRole, Long> revisions() {
        var result = new EnumMap<ContainmentResourceRole, Long>(ContainmentResourceRole.class);
        long revision = 0;
        for (ContainmentResourceRole role : ContainmentResourceRole.values()) {
            result.put(role, ++revision);
        }
        return result;
    }

    private static ContainmentTerminalReport issue(SingleOwnerCleanup cleanup,
            Map<ContainmentResourceRole, ResourceDisposition> dispositions,
            Map<ContainmentResourceRole, Long> revisions,
            SingleOwnerCleanup.TerminalOutcome outcome) throws Exception {
        var field = SingleOwnerCleanup.class.getDeclaredField("proofIssuer");
        field.setAccessible(true);
        var issuer = (SingleOwnerCleanup.ProofIssuer) field.get(cleanup);
        return ContainmentTerminalReport.issue(issuer, outcome.executionOutcome(),
                outcome.containmentCode(), dispositions, revisions, outcome.workerAttachment(),
                outcome.dependencyClosure());
    }
}
