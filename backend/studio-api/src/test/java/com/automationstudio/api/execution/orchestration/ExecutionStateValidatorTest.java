package com.automationstudio.api.execution.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.entity.Execution;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ExecutionStateValidatorTest {

    private final ExecutionStateValidator validator = new ExecutionStateValidator();

    @Test
    void permitsOnlyClaimedStartAndRunningCompletionPreparation() {
        Execution claimed = new Execution();
        claimed.claim();
        validator.validateStart(claimed);

        Execution running = new Execution();
        running.claim();
        running.start(OffsetDateTime.parse("2026-07-29T10:00:00Z"));
        validator.validateCompletionPreparation(running);

        assertThatThrownBy(() -> validator.validateStart(running))
                .isInstanceOf(RunnerExecutionException.class);
        assertThatThrownBy(() -> validator.validateCompletionPreparation(claimed))
                .isInstanceOf(RunnerExecutionException.class);
        assertThatThrownBy(() -> validator.validateStart(new Execution()))
                .isInstanceOf(RunnerExecutionException.class);
    }
}
