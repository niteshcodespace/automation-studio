package com.automationstudio.api.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.InvalidExecutionTransitionException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ExecutionTest {

    private static final OffsetDateTime CLAIMED_AT =
            OffsetDateTime.parse("2026-01-01T10:00:00Z");
    private static final OffsetDateTime STARTED_AT =
            OffsetDateTime.parse("2026-01-01T10:01:00Z");
    private static final OffsetDateTime FINISHED_AT =
            OffsetDateTime.parse("2026-01-01T10:02:00Z");

    @Test
    void supportsNormalLifecycleTransitions() {
        Execution passed = runningExecution();
        passed.markPassed(FINISHED_AT);
        assertThat(passed.getStatus()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(passed.getFinishedAt()).isEqualTo(FINISHED_AT);

        Execution failed = runningExecution();
        failed.markFailed(FINISHED_AT);
        assertThat(failed.getStatus()).isEqualTo(ExecutionStatus.FAILED);

        Execution error = runningExecution();
        error.markError(FINISHED_AT);
        assertThat(error.getStatus()).isEqualTo(ExecutionStatus.ERROR);
    }

    @Test
    void recordsStartTimestampAndAllowsClaimedInfrastructureFailure() {
        Execution execution = new Execution();
        execution.claim();
        execution.start(STARTED_AT);

        assertThat(execution.getStartedAt()).isEqualTo(STARTED_AT);

        Execution claimedFailure = new Execution();
        claimedFailure.claim();
        claimedFailure.markError(FINISHED_AT);

        assertThat(claimedFailure.getStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(claimedFailure.getFinishedAt()).isEqualTo(FINISHED_AT);
    }

    @Test
    void immediatelyCancelsPendingExecutionAndRecordsMetadata() {
        Execution execution = new Execution();

        execution.requestCancellation(CLAIMED_AT, "operator", "No longer needed");

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(execution.getCancelRequestedAt()).isEqualTo(CLAIMED_AT);
        assertThat(execution.getCancelledAt()).isEqualTo(CLAIMED_AT);
        assertThat(execution.getFinishedAt()).isEqualTo(CLAIMED_AT);
        assertThat(execution.getCancelledBy()).isEqualTo("operator");
        assertThat(execution.getCancellationReason()).isEqualTo("No longer needed");
    }

    @Test
    void requestsAndCompletesCooperativeCancellation() {
        Execution execution = runningExecution();

        execution.requestCancellation(FINISHED_AT, "operator", "Stop requested");

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCEL_REQUESTED);
        assertThat(execution.getCancelledAt()).isNull();
        execution.markCancelled(FINISHED_AT.plusMinutes(1));

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(execution.getCancelledAt()).isEqualTo(FINISHED_AT.plusMinutes(1));
        assertThat(execution.getCancelledBy()).isEqualTo("operator");
        assertThat(execution.getCancellationReason()).isEqualTo("Stop requested");
    }

    @Test
    void cancellationRequestIsIdempotentForRequestedAndCancelledStates() {
        Execution execution = runningExecution();
        execution.requestCancellation(FINISHED_AT, "first", "original");
        execution.requestCancellation(FINISHED_AT.plusMinutes(1), "second", "replacement");

        assertThat(execution.getCancelRequestedAt()).isEqualTo(FINISHED_AT);
        assertThat(execution.getCancelledBy()).isEqualTo("first");
        assertThat(execution.getCancellationReason()).isEqualTo("original");

        execution.markCancelled(FINISHED_AT.plusMinutes(2));
        execution.requestCancellation(FINISHED_AT.plusMinutes(3), "third", null);
        assertThat(execution.getCancelledBy()).isEqualTo("first");
    }

    @Test
    void rejectsInvalidLifecycleTransitions() {
        assertInvalidTransition(new Execution(), ExecutionStatus.PASSED);

        Execution claimed = new Execution();
        claimed.claim();
        assertThatThrownBy(() -> claimed.markPassed(FINISHED_AT))
                .isInstanceOf(InvalidExecutionTransitionException.class);

        Execution cancellationRequested = runningExecution();
        cancellationRequested.requestCancellation(FINISHED_AT, "operator", null);
        assertThatThrownBy(() -> cancellationRequested.start(FINISHED_AT))
                .isInstanceOf(InvalidExecutionTransitionException.class);

        assertCancellationRejected(terminalExecution(ExecutionStatus.PASSED));
        assertCancellationRejected(terminalExecution(ExecutionStatus.FAILED));
        assertCancellationRejected(terminalExecution(ExecutionStatus.ERROR));

        assertThatThrownBy(() -> new Execution().markCancelled(FINISHED_AT))
                .isInstanceOf(InvalidExecutionTransitionException.class);
    }

    @Test
    void rejectsCancellationCompletionBeforeRequest() {
        Execution execution = runningExecution();
        execution.requestCancellation(FINISHED_AT, "operator", null);

        assertThatThrownBy(() -> execution.markCancelled(FINISHED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request time");
    }

    @Test
    void validatesCancellationMetadata() {
        assertThatThrownBy(() -> runningExecution()
                .requestCancellation(FINISHED_AT, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runningExecution()
                .requestCancellation(FINISHED_AT, "a".repeat(151), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runningExecution()
                .requestCancellation(FINISHED_AT, "operator", "r".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Execution runningExecution() {
        Execution execution = new Execution();
        execution.claim();
        execution.start(STARTED_AT);
        return execution;
    }

    private static Execution terminalExecution(ExecutionStatus status) {
        Execution execution = runningExecution();
        switch (status) {
            case PASSED -> execution.markPassed(FINISHED_AT);
            case FAILED -> execution.markFailed(FINISHED_AT);
            case ERROR -> execution.markError(FINISHED_AT);
            default -> throw new IllegalArgumentException("Unsupported test status");
        }
        return execution;
    }

    private static void assertInvalidTransition(
            Execution execution, ExecutionStatus requestedStatus) {
        assertThatThrownBy(() -> execution.markPassed(FINISHED_AT))
                .isInstanceOf(InvalidExecutionTransitionException.class)
                .satisfies(exception -> assertThat(
                        ((InvalidExecutionTransitionException) exception).getRequestedStatus())
                        .isEqualTo(requestedStatus));
    }

    private static void assertCancellationRejected(Execution execution) {
        assertThatThrownBy(() ->
                execution.requestCancellation(FINISHED_AT.plusMinutes(1), "operator", null))
                .isInstanceOf(InvalidExecutionTransitionException.class);
    }
}
