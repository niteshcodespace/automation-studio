package com.automationstudio.api.entity;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
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
@Table(name = "environment", uniqueConstraints = @UniqueConstraint(
        name = "uk_environment_project_name", columnNames = {"project_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class Environment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    @Setter(lombok.AccessLevel.NONE)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Size(max = 1000)
    @Column(length = 1000)
    private String description;

    @NotBlank
    @Size(max = 500)
    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EnvironmentType type;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> configuration = new LinkedHashMap<>();

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "secret_references", nullable = false, columnDefinition = "jsonb")
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> secretReferences = new LinkedHashMap<>();

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EnvironmentStatus status = EnvironmentStatus.ACTIVE;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

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
        return new LinkedHashMap<>(configuration);
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(configuration);
    }

    public Map<String, Object> getSecretReferences() {
        return new LinkedHashMap<>(secretReferences);
    }

    public void setSecretReferences(Map<String, Object> secretReferences) {
        this.secretReferences = secretReferences == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(secretReferences);
    }
}
