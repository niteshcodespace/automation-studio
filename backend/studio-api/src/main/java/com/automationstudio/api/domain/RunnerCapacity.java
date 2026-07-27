package com.automationstudio.api.domain;

public record RunnerCapacity(
        int maxConcurrency,
        long activeLeaseCount,
        long availableCapacity) {

    public RunnerCapacity {
        maxConcurrency = Math.max(0, maxConcurrency);
        activeLeaseCount = Math.max(0, activeLeaseCount);
        availableCapacity = Math.max(0, availableCapacity);
        long expected = Math.max(0L, (long) maxConcurrency - activeLeaseCount);
        if (availableCapacity != expected) {
            throw new IllegalArgumentException(
                    "Available runner capacity must equal maximum minus active leases");
        }
    }

    public static RunnerCapacity from(int maxConcurrency, long activeLeaseCount) {
        long available = Math.max(0L, (long) maxConcurrency - activeLeaseCount);
        return new RunnerCapacity(maxConcurrency, activeLeaseCount, available);
    }
}
