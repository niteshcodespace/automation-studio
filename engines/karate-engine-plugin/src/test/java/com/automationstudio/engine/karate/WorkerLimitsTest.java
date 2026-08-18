package com.automationstudio.engine.karate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerLimitsTest {
    @Test void defaultsRunnerMaximumToHardCeiling() {
        assertEquals(8, WorkerLimits.defaults().maximumParallelism());
    }

    @Test void acceptsLowerRunnerMaximumAndRejectsOutsideHardCeiling() {
        assertEquals(3, limits(3).maximumParallelism());
        assertThrows(IllegalArgumentException.class, () -> limits(0));
        assertThrows(IllegalArgumentException.class, () -> limits(9));
    }

    @Test void calculatesEffectiveParallelismOnceWithinSuiteRunnerAndHardBounds() {
        assertEquals(1, DockerKarateWorkerRuntime.effectiveParallelism(1, 8));
        assertEquals(4, DockerKarateWorkerRuntime.effectiveParallelism(4, 8));
        assertEquals(3, DockerKarateWorkerRuntime.effectiveParallelism(8, 3));
        assertEquals(8, DockerKarateWorkerRuntime.effectiveParallelism(8, 8));
    }

    private WorkerLimits limits(int maximumParallelism) {
        return new WorkerLimits(Duration.ofMinutes(30), Duration.ofSeconds(2), 1_048_576,
                40L * 1024 * 1024, 1_048_576, 1_048_576, 768L * 1024 * 1024, 1.0, 128,
                64L * 1024 * 1024, maximumParallelism);
    }
}
