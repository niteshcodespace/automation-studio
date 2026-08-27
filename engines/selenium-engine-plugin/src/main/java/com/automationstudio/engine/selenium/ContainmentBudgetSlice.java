package com.automationstudio.engine.selenium;

import java.time.Duration;
import java.util.Objects;

/** A non-owning view of ordinary and reserved time within one containment deadline. */
final class ContainmentBudgetSlice {
    private final ContainmentDeadline deadline;
    private final long tailReserveNanos;

    ContainmentBudgetSlice(ContainmentDeadline deadline, Duration tailReserve) {
        this(deadline, reserveNanos(tailReserve));
    }

    private ContainmentBudgetSlice(ContainmentDeadline deadline, long tailReserveNanos) {
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.tailReserveNanos = tailReserveNanos;
    }

    ContainmentBudgetSlice nested(Duration requestedReserve) {
        return new ContainmentBudgetSlice(deadline,
                Math.max(tailReserveNanos, reserveNanos(requestedReserve)));
    }

    ContainmentDeadline deadline() {
        return deadline;
    }

    Duration tailReserve() {
        return Duration.ofNanos(tailReserveNanos);
    }

    Duration ordinaryRemaining() {
        return Duration.ofNanos(Math.max(0L, deadline.remainingNanos() - tailReserveNanos));
    }

    Duration absoluteRemaining() {
        return deadline.remaining();
    }

    private static long reserveNanos(Duration reserve) {
        Objects.requireNonNull(reserve, "tailReserve");
        if (reserve.isNegative()) {
            throw new IllegalArgumentException("Tail reserve must not be negative");
        }
        try {
            return reserve.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Tail reserve is too large", failure);
        }
    }
}
