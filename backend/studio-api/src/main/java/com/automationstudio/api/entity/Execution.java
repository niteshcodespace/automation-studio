package com.automationstudio.api.entity;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
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

    private static Map<String, Object> copySnapshot(Map<String, Object> snapshot) {
        return snapshot == null ? null : new LinkedHashMap<>(snapshot);
    }
}
