package com.automationstudio.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionSelectionTest {

    @Test
    void suiteHasNoExplicitSelections() {
        ExecutionSelection selection = ExecutionSelection.suite();

        assertThat(selection.getMode()).isEqualTo(ExecutionSelectionMode.SUITE);
        assertThat(selection.getTestCaseIds()).isEmpty();
    }

    @Test
    void testCasesPreserveRequestedOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        ExecutionSelection selection = ExecutionSelection.testCases(List.of(first, second));

        assertThat(selection.getMode()).isEqualTo(ExecutionSelectionMode.TEST_CASES);
        assertThat(selection.getTestCaseIds()).containsExactly(first, second);
    }

    @Test
    void testCasesRejectEmptyDuplicateAndNullSelections() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> ExecutionSelection.testCases(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionSelection.testCases(List.of(id, id)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionSelection.testCases(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
