package com.automationstudio.api.entity;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.InvalidExecutionTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "execution")
@Getter
@Setter
@NoArgsConstructor
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_suite_id", nullable = false)
    private AutomationSuite automationSuite;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Setter(lombok.AccessLevel.NONE)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "selection_mode", nullable = false, length = 30)
    private ExecutionSelectionMode selectionMode = ExecutionSelectionMode.SUITE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "environment_snapshot", columnDefinition = "jsonb")
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> environmentSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suite_snapshot", columnDefinition = "jsonb")
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> suiteSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot", columnDefinition = "jsonb")
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> requestSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_snapshot", columnDefinition = "jsonb")
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> sourceSnapshot;

    @NotBlank
    @Size(max = 150)
    @Column(name = "requested_by", nullable = false, length = 150)
    private String requestedBy;

    @NotNull
    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt = OffsetDateTime.now();

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @PositiveOrZero
    @Column(name = "total_tests")
    private Integer totalTests;

    @PositiveOrZero
    @Column(name = "passed_tests")
    private Integer passedTests;

    @PositiveOrZero
    @Column(name = "failed_tests")
    private Integer failedTests;

    @PositiveOrZero
    @Column(name = "skipped_tests")
    private Integer skippedTests;

    @PositiveOrZero
    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "cancel_requested_at")
    private OffsetDateTime cancelRequestedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Size(max = 150)
    @Column(name = "cancelled_by", length = 150)
    private String cancelledBy;

    @Size(max = 1000)
    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Map<String, Object> getEnvironmentSnapshot() {
        return copySnapshot(environmentSnapshot);
    }

    public void setEnvironmentSnapshot(Map<String, Object> environmentSnapshot) {
        this.environmentSnapshot = copySnapshot(environmentSnapshot);
    }

    public Map<String, Object> getSuiteSnapshot() {
        return copySnapshot(suiteSnapshot);
    }

    public void setSuiteSnapshot(Map<String, Object> suiteSnapshot) {
        this.suiteSnapshot = copySnapshot(suiteSnapshot);
    }

    public Map<String, Object> getRequestSnapshot() {
        return copySnapshot(requestSnapshot);
    }

    public void setRequestSnapshot(Map<String, Object> requestSnapshot) {
        this.requestSnapshot = copySnapshot(requestSnapshot);
    }

    public Map<String, Object> getSourceSnapshot() {
        return copySnapshot(sourceSnapshot);
    }

    public void setSourceSnapshot(Map<String, Object> sourceSnapshot) {
        this.sourceSnapshot = copySnapshot(sourceSnapshot);
    }

    public void claim() {
        requireStatus(ExecutionStatus.PENDING, ExecutionStatus.CLAIMED);
        status = ExecutionStatus.CLAIMED;
    }

    public void start(OffsetDateTime transitionTime) {
        requireStatus(ExecutionStatus.CLAIMED, ExecutionStatus.RUNNING);
        startedAt = requireTimestamp(transitionTime);
        status = ExecutionStatus.RUNNING;
    }

    public void markPassed(OffsetDateTime transitionTime) {
        completeFromRunning(ExecutionStatus.PASSED, transitionTime);
    }

    public void markFailed(OffsetDateTime transitionTime) {
        completeFromRunning(ExecutionStatus.FAILED, transitionTime);
    }

    public void markError(OffsetDateTime transitionTime) {
        if (status != ExecutionStatus.CLAIMED
                && status != ExecutionStatus.RUNNING
                && status != ExecutionStatus.CANCEL_REQUESTED) {
            throw invalidTransition(ExecutionStatus.ERROR);
        }
        finishedAt = requireCompletionTime(transitionTime);
        status = ExecutionStatus.ERROR;
    }

    public void requestCancellation(
            OffsetDateTime transitionTime, String actor, String reason) {
        if (status == ExecutionStatus.CANCEL_REQUESTED || status == ExecutionStatus.CANCELLED) {
            return;
        }
        if (status != ExecutionStatus.PENDING
                && status != ExecutionStatus.CLAIMED
                && status != ExecutionStatus.RUNNING) {
            throw invalidTransition(ExecutionStatus.CANCEL_REQUESTED);
        }

        OffsetDateTime requestedCancellationAt = requireTimestamp(transitionTime);
        validateCancellationActor(actor);
        validateCancellationReason(reason);
        cancelRequestedAt = requestedCancellationAt;
        cancelledBy = actor;
        cancellationReason = reason;

        if (status == ExecutionStatus.PENDING) {
            status = ExecutionStatus.CANCELLED;
            cancelledAt = requestedCancellationAt;
            finishedAt = requestedCancellationAt;
        } else {
            status = ExecutionStatus.CANCEL_REQUESTED;
        }
    }

    public void markCancelled(OffsetDateTime transitionTime) {
        requireStatus(ExecutionStatus.CANCEL_REQUESTED, ExecutionStatus.CANCELLED);
        OffsetDateTime cancellationCompletedAt = requireCompletionTime(transitionTime);
        if (cancellationCompletedAt.isBefore(cancelRequestedAt)) {
            throw new IllegalArgumentException(
                    "Cancellation completion time must not precede cancellation request time");
        }
        cancelledAt = cancellationCompletedAt;
        finishedAt = cancellationCompletedAt;
        status = ExecutionStatus.CANCELLED;
    }

    private static Map<String, Object> copySnapshot(Map<String, Object> snapshot) {
        return snapshot == null ? null : new LinkedHashMap<>(snapshot);
    }

    private void completeFromRunning(
            ExecutionStatus terminalStatus, OffsetDateTime transitionTime) {
        requireStatus(ExecutionStatus.RUNNING, terminalStatus);
        finishedAt = requireCompletionTime(transitionTime);
        status = terminalStatus;
    }

    private void requireStatus(ExecutionStatus requiredStatus, ExecutionStatus requestedStatus) {
        if (status != requiredStatus) {
            throw invalidTransition(requestedStatus);
        }
    }

    private InvalidExecutionTransitionException invalidTransition(
            ExecutionStatus requestedStatus) {
        return new InvalidExecutionTransitionException(id, status, requestedStatus);
    }

    private OffsetDateTime requireCompletionTime(OffsetDateTime transitionTime) {
        OffsetDateTime completedAt = requireTimestamp(transitionTime);
        if (startedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "Execution completion time must not precede start time");
        }
        return completedAt;
    }

    private static OffsetDateTime requireTimestamp(OffsetDateTime transitionTime) {
        if (transitionTime == null) {
            throw new IllegalArgumentException("Transition time must not be null");
        }
        return transitionTime;
    }

    private static void validateCancellationActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("Cancellation actor must not be blank");
        }
        if (actor.length() > 150) {
            throw new IllegalArgumentException(
                    "Cancellation actor must not exceed 150 characters");
        }
    }

    private static void validateCancellationReason(String reason) {
        if (reason != null && reason.length() > 1000) {
            throw new IllegalArgumentException(
                    "Cancellation reason must not exceed 1000 characters");
        }
    }
}
