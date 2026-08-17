package com.automationstudio.engine.karate.gateway;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutionConcurrencyLimitTest {
    @Test void boundsConcurrentWorkAndDoesNotLeakPermits() throws Exception {
        var limit = new ExecutionConcurrencyLimit(2);
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(6); var start = new CountDownLatch(1);
            var tasks = java.util.stream.IntStream.range(0, 6).mapToObj(index -> executor.submit(() -> {
                ready.countDown(); start.await();
                try (var ignored = limit.acquire(System.currentTimeMillis() + 5_000)) {
                    int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
                    try { Thread.sleep(40); } finally { active.decrementAndGet(); }
                }
                return null;
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2, maximum.get());
    }

    @Test void usesSharedDeadlineAndKeepsExecutionsIndependent() {
        var one = new ExecutionConcurrencyLimit(1); var two = new ExecutionConcurrencyLimit(2);
        try (var first = one.acquire(System.currentTimeMillis() + 1_000);
             var second = two.acquire(System.currentTimeMillis() + 1_000);
             var third = two.acquire(System.currentTimeMillis() + 1_000)) {
            var failure = assertThrows(GatewayTargetPolicy.GatewayFailure.class,
                    () -> one.acquire(System.currentTimeMillis() - 1));
            assertEquals("GATEWAY_TIMEOUT", failure.code());
        }
    }

    @Test void rejectsValuesOutsideHardCeiling() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionConcurrencyLimit(0));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionConcurrencyLimit(9));
    }
}
