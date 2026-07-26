package com.automationstudio.api.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionLeaseTest {

    @Test
    void replacesOwnershipAndAdvancesGenerationExactlyOnce() {
        ExecutionLease lease = new ExecutionLease();
        lease.setRunnerId("old");
        lease.setClaimToken(UUID.randomUUID());
        lease.setLeaseGeneration(4L);
        OffsetDateTime time = OffsetDateTime.parse("2026-07-26T12:00:00Z");
        UUID token = UUID.randomUUID();

        lease.reclaim("new", token, time, time.plusMinutes(2));

        assertThat(lease.getRunnerId()).isEqualTo("new");
        assertThat(lease.getClaimToken()).isEqualTo(token);
        assertThat(lease.getLeaseGeneration()).isEqualTo(5);
        assertThat(lease.getClaimedAt()).isEqualTo(time);
        assertThat(lease.getLastHeartbeatAt()).isEqualTo(time);
        assertThat(lease.getLeaseExpiresAt()).isEqualTo(time.plusMinutes(2));
    }

    @Test
    void rejectsOverflowAndInvalidExpiryBeforeChangingOwnership() {
        ExecutionLease lease = new ExecutionLease();
        UUID oldToken = UUID.randomUUID();
        lease.setRunnerId("old");
        lease.setClaimToken(oldToken);
        lease.setLeaseGeneration(Long.MAX_VALUE);
        OffsetDateTime time = OffsetDateTime.parse("2026-07-26T12:00:00Z");

        assertThatThrownBy(() -> lease.reclaim("new", UUID.randomUUID(), time, time.plusSeconds(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThat(lease.getRunnerId()).isEqualTo("old");
        assertThat(lease.getClaimToken()).isEqualTo(oldToken);

        lease.setLeaseGeneration(1L);
        assertThatThrownBy(() -> lease.reclaim("new", UUID.randomUUID(), time, time))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
