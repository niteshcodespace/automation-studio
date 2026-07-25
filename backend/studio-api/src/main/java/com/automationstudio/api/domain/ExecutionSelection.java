package com.automationstudio.api.domain;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.Getter;

@Getter
public final class ExecutionSelection {

    private final ExecutionSelectionMode mode;
    private final List<UUID> testCaseIds;

    private ExecutionSelection(ExecutionSelectionMode mode, List<UUID> testCaseIds) {
        if (mode == null) {
            throw new IllegalArgumentException("Selection mode must not be null");
        }
        if (testCaseIds == null) {
            throw new IllegalArgumentException("Test-case selections must not be null");
        }
        if (testCaseIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("Test-case selections must not contain null IDs");
        }
        if (mode == ExecutionSelectionMode.SUITE && !testCaseIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "SUITE selection must not contain explicit test cases");
        }
        if (mode == ExecutionSelectionMode.TEST_CASES && testCaseIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "TEST_CASES selection must contain at least one test case");
        }
        if (new HashSet<>(testCaseIds).size() != testCaseIds.size()) {
            throw new IllegalArgumentException(
                    "Test-case selections must not contain duplicate IDs");
        }
        this.mode = mode;
        this.testCaseIds = List.copyOf(testCaseIds);
    }

    public static ExecutionSelection suite() {
        return new ExecutionSelection(ExecutionSelectionMode.SUITE, List.of());
    }

    public static ExecutionSelection testCases(List<UUID> testCaseIds) {
        return new ExecutionSelection(ExecutionSelectionMode.TEST_CASES, testCaseIds);
    }
}
