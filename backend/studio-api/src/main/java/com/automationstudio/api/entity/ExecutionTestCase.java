package com.automationstudio.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "execution_test_case", uniqueConstraints = {
        @UniqueConstraint(name = "uk_execution_test_case_execution_case",
                columnNames = {"execution_id", "automation_test_case_id"}),
        @UniqueConstraint(name = "uk_execution_test_case_execution_sequence",
                columnNames = {"execution_id", "sequence_number"})
})
@Getter
@Setter
@NoArgsConstructor
public class ExecutionTestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "automation_test_case_id", nullable = false)
    private AutomationTestCase automationTestCase;

    @NotNull
    @PositiveOrZero
    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "test_case_snapshot", columnDefinition = "jsonb")
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> testCaseSnapshot;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Map<String, Object> getTestCaseSnapshot() {
        return testCaseSnapshot == null ? null : new LinkedHashMap<>(testCaseSnapshot);
    }

    public void setTestCaseSnapshot(Map<String, Object> testCaseSnapshot) {
        this.testCaseSnapshot =
                testCaseSnapshot == null ? null : new LinkedHashMap<>(testCaseSnapshot);
    }
}
