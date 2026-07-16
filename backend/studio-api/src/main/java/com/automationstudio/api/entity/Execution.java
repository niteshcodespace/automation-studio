package com.automationstudio.api.entity;

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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
    private TestSuite testSuite;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionStatus status = ExecutionStatus.PENDING;

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

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
