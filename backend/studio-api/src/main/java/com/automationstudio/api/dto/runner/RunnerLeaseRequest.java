package com.automationstudio.api.dto.runner;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;

public record RunnerLeaseRequest(
        @NotBlank @Size(max = 150) String runnerId,
        @NotNull Duration leaseDuration) {

    @JsonIgnore
    @AssertTrue(message = "leaseDuration must be positive")
    public boolean isLeaseDurationPositive() {
        return leaseDuration == null
                || (!leaseDuration.isZero() && !leaseDuration.isNegative());
    }
}
