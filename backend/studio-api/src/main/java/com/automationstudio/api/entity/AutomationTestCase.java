package com.automationstudio.api.entity;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
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
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "automation_test_case", uniqueConstraints = {
        @UniqueConstraint(name = "uk_automation_test_case_suite_name",
                columnNames = {"test_suite_id", "name"}),
        @UniqueConstraint(name = "uk_automation_test_case_suite_reference",
                columnNames = {"test_suite_id", "case_reference"}),
        @UniqueConstraint(name = "uk_automation_test_case_suite_position",
                columnNames = {"test_suite_id", "position"})
})
@Getter
@Setter
@NoArgsConstructor
public class AutomationTestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_suite_id", nullable = false)
    private AutomationSuite automationSuite;

    @NotBlank
    @Size(max = 150)
    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @NotBlank
    @Size(max = 300)
    @Column(name = "case_reference", nullable = false, length = 300)
    private String caseReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> configuration;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AutomationTestCaseStatus status = AutomationTestCaseStatus.ACTIVE;

    @NotNull
    @PositiveOrZero
    @Column(nullable = false)
    private Integer position;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Map<String, Object> getConfiguration() {
        return configuration == null ? null : new LinkedHashMap<>(configuration);
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration == null ? null : new LinkedHashMap<>(configuration);
    }
}
