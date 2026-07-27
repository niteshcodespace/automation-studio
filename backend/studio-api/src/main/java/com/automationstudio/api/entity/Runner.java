package com.automationstudio.api.entity;

import com.automationstudio.api.domain.RunnerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "runner", uniqueConstraints = @UniqueConstraint(
        name = "uk_runner_runner_key", columnNames = "runner_key"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Runner {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @NotBlank
    @Size(max = 150)
    @Column(name = "runner_key", nullable = false, updatable = false, length = 150)
    private String runnerKey;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Size(max = 1000)
    @Column(length = 1000)
    private String description;

    @NotBlank
    @Size(max = 100)
    @Column(name = "agent_version", nullable = false, length = 100)
    private String agentVersion;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String hostname;

    @NotBlank
    @Size(max = 100)
    @Column(name = "operating_system", nullable = false, length = 100)
    private String operatingSystem;

    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, length = 50)
    private String architecture;

    @Min(1)
    @Max(1000)
    @Column(name = "max_concurrency", nullable = false)
    private int maxConcurrency;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Getter(AccessLevel.NONE)
    private Map<String, Object> capabilities = new LinkedHashMap<>();

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Getter(AccessLevel.NONE)
    private Map<String, Object> labels = new LinkedHashMap<>();

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RunnerStatus status;

    @NotNull
    @Column(name = "registered_at", nullable = false, updatable = false)
    private OffsetDateTime registeredAt;

    @NotNull
    @Column(name = "last_registered_at", nullable = false)
    private OffsetDateTime lastRegisteredAt;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(
            event = {EventType.INSERT, EventType.UPDATE},
            sql = "clock_timestamp()")
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Runner(
            String runnerKey,
            String name,
            String description,
            String agentVersion,
            String hostname,
            String operatingSystem,
            String architecture,
            int maxConcurrency,
            Map<String, Object> capabilities,
            Map<String, Object> labels,
            RunnerStatus status,
            OffsetDateTime registeredAt) {
        this.runnerKey = runnerKey;
        this.name = name;
        this.description = description;
        this.agentVersion = agentVersion;
        this.hostname = hostname;
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
        this.maxConcurrency = maxConcurrency;
        this.capabilities = copyJsonObject(capabilities);
        this.labels = copyJsonObject(labels);
        this.status = status;
        this.registeredAt = registeredAt;
        this.lastRegisteredAt = registeredAt;
    }

    public Map<String, Object> getCapabilities() {
        return copyJsonObject(capabilities);
    }

    public Map<String, Object> getLabels() {
        return copyJsonObject(labels);
    }

    public void updateMetadata(
            String name,
            String description,
            String agentVersion,
            String hostname,
            String operatingSystem,
            String architecture,
            int maxConcurrency,
            Map<String, Object> capabilities,
            Map<String, Object> labels,
            OffsetDateTime lastRegisteredAt) {
        this.name = name;
        this.description = description;
        this.agentVersion = agentVersion;
        this.hostname = hostname;
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
        this.maxConcurrency = maxConcurrency;
        this.capabilities = copyJsonObject(capabilities);
        this.labels = copyJsonObject(labels);
        this.lastRegisteredAt = lastRegisteredAt;
    }

    public void updateStatus(RunnerStatus status) {
        this.status = status;
    }

    private static Map<String, Object> copyJsonObject(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, copyJsonValue(value)));
        return copy;
    }

    private static Object copyJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) ->
                    copy.put(String.valueOf(key), copyJsonValue(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyJsonValue(item)));
            return copy;
        }
        return value;
    }
}
