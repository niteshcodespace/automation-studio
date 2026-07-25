package com.automationstudio.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "execution_lease", uniqueConstraints = {
        @UniqueConstraint(name = "uk_execution_lease_claim_token",
                columnNames = "claim_token")
})
@Getter
@Setter
@NoArgsConstructor
public class ExecutionLease {

    @Id
    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @NotNull
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private Execution execution;

    @NotBlank
    @Size(max = 150)
    @Column(name = "runner_id", nullable = false, length = 150)
    private String runnerId;

    @NotNull
    @Column(name = "claim_token", nullable = false, unique = true)
    private UUID claimToken;

    @NotNull
    @Positive
    @Column(name = "lease_generation", nullable = false)
    private Long leaseGeneration;

    @NotNull
    @Column(name = "claimed_at", nullable = false)
    private OffsetDateTime claimedAt;

    @NotNull
    @Column(name = "last_heartbeat_at", nullable = false)
    private OffsetDateTime lastHeartbeatAt;

    @NotNull
    @Column(name = "lease_expires_at", nullable = false)
    private OffsetDateTime leaseExpiresAt;

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
