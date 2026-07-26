package com.automationstudio.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "runner_runtime")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunnerRuntime {

    @Id
    @Column(name = "runner_id", nullable = false, updatable = false)
    private UUID runnerId;

    @NotNull
    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @PositiveOrZero
    @Column(name = "heartbeat_count", nullable = false)
    private long heartbeatCount;

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

    public RunnerRuntime(UUID runnerId, OffsetDateTime lastSeenAt) {
        this.runnerId = runnerId;
        this.lastSeenAt = lastSeenAt;
    }

    public void updateRuntimeState(OffsetDateTime lastSeenAt, long heartbeatCount) {
        this.lastSeenAt = lastSeenAt;
        this.heartbeatCount = heartbeatCount;
    }

    public void recordHeartbeat(OffsetDateTime heartbeatTime) {
        if (heartbeatTime == null) {
            throw new IllegalArgumentException("Heartbeat time must not be null");
        }
        if (heartbeatTime.isBefore(lastSeenAt)) {
            throw new IllegalArgumentException(
                    "Heartbeat time must not be earlier than last seen time");
        }
        this.lastSeenAt = heartbeatTime;
        this.heartbeatCount = Math.incrementExact(heartbeatCount);
    }
}
