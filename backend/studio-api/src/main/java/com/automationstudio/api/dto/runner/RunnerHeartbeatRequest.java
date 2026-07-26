package com.automationstudio.api.dto.runner;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.UUID;

public record RunnerHeartbeatRequest(
        @NotNull UUID executionId,
        @NotBlank @Size(max = 150) String runnerId,
        @NotNull UUID claimToken,
        @Positive long leaseGeneration,
        @PositiveOrZero long leaseVersion,
        @NotNull Duration leaseDuration) {

    @JsonIgnore
    @AssertTrue(message = "leaseDuration must be positive")
    public boolean isLeaseDurationPositive() {
        return leaseDuration == null
                || (!leaseDuration.isZero() && !leaseDuration.isNegative());
    }

    @Override
    public String toString() {
        return "RunnerHeartbeatRequest[executionId="
                + executionId
                + ", runnerId="
                + runnerId
                + ", leaseGeneration="
                + leaseGeneration
                + ", leaseVersion="
                + leaseVersion
                + ", leaseDuration="
                + leaseDuration
                + "]";
    }
}
