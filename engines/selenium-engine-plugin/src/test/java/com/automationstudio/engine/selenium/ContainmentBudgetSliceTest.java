package com.automationstudio.engine.selenium;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ContainmentBudgetSliceTest {
    @Test void sameOriginalDeadlineObjectIsRetainedAcrossNesting() {
        var ticks = new AtomicLong();
        var deadline = ContainmentDeadline.after(Duration.ofNanos(100), ticks::get);
        var parent = new ContainmentBudgetSlice(deadline, Duration.ofNanos(20));
        assertSame(deadline, parent.deadline());
        assertSame(deadline, parent.nested(Duration.ofNanos(30)).deadline());
    }

    @Test void ordinaryAndAbsoluteRemainingAreDistinctAndNeverNegative() {
        var ticks = new AtomicLong();
        var deadline = ContainmentDeadline.after(Duration.ofNanos(100), ticks::get);
        var zero = new ContainmentBudgetSlice(deadline, Duration.ZERO);
        assertEquals(Duration.ofNanos(100), zero.ordinaryRemaining());
        assertEquals(Duration.ofNanos(100), zero.absoluteRemaining());

        var smaller = new ContainmentBudgetSlice(deadline, Duration.ofNanos(40));
        assertEquals(Duration.ofNanos(60), smaller.ordinaryRemaining());
        assertEquals(Duration.ofNanos(100), smaller.absoluteRemaining());

        assertEquals(Duration.ZERO,
                new ContainmentBudgetSlice(deadline, Duration.ofNanos(100)).ordinaryRemaining());
        assertEquals(Duration.ZERO,
                new ContainmentBudgetSlice(deadline, Duration.ofNanos(101)).ordinaryRemaining());
    }

    @Test void fakeTickerCrossesOrdinaryCutoffWhileTerminationAllowanceRemains() {
        var ticks = new AtomicLong();
        var deadline = ContainmentDeadline.after(Duration.ofNanos(100), ticks::get);
        var slice = new ContainmentBudgetSlice(deadline, Duration.ofNanos(30));
        ticks.set(69);
        assertEquals(Duration.ofNanos(1), slice.ordinaryRemaining());
        assertEquals(Duration.ofNanos(31), slice.absoluteRemaining());
        ticks.set(70);
        assertEquals(Duration.ZERO, slice.ordinaryRemaining());
        assertEquals(Duration.ofNanos(30), slice.absoluteRemaining());
        ticks.set(101);
        assertEquals(Duration.ZERO, slice.ordinaryRemaining());
        assertEquals(Duration.ZERO, slice.absoluteRemaining());
    }

    @Test void nestedReserveCanOnlyStayEqualOrIncrease() {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(1), () -> 0L);
        var parent = new ContainmentBudgetSlice(deadline, Duration.ofMillis(400));
        assertEquals(Duration.ofMillis(400), parent.nested(Duration.ofMillis(200)).tailReserve());
        assertEquals(Duration.ofMillis(400), parent.nested(Duration.ofMillis(400)).tailReserve());
        assertEquals(Duration.ofMillis(600), parent.nested(Duration.ofMillis(600)).tailReserve());
    }

    @Test void deeplyNestedSlicesPreserveIdentityAndGreatestReserve() {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(2), () -> 0L);
        var slice = new ContainmentBudgetSlice(deadline, Duration.ZERO);
        for (int index = 0; index < 100; index++) {
            Duration request = index % 2 == 0 ? Duration.ofMillis(index) : Duration.ofMillis(1);
            slice = slice.nested(request);
            assertSame(deadline, slice.deadline());
        }
        assertEquals(Duration.ofMillis(98), slice.tailReserve());
    }

    @Test void regressingTickerCannotRestoreConsumedTime() {
        var ticks = new AtomicLong();
        var deadline = ContainmentDeadline.after(Duration.ofNanos(100), ticks::get);
        var slice = new ContainmentBudgetSlice(deadline, Duration.ofNanos(10));
        ticks.set(80);
        assertEquals(Duration.ofNanos(10), slice.ordinaryRemaining());
        ticks.set(20);
        assertEquals(Duration.ofNanos(10), slice.ordinaryRemaining());
        assertEquals(Duration.ofNanos(20), slice.absoluteRemaining());
        ticks.set(100);
        assertEquals(Duration.ZERO, slice.absoluteRemaining());
        ticks.set(0);
        assertEquals(Duration.ZERO, slice.absoluteRemaining());
    }

    @Test void invalidReserveIsRejected() {
        var deadline = ContainmentDeadline.after(Duration.ofSeconds(1), () -> 0L);
        assertThrows(IllegalArgumentException.class,
                () -> new ContainmentBudgetSlice(deadline, Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainmentBudgetSlice(deadline, Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
