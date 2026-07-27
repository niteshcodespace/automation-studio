package com.automationstudio.api.domain;

public enum SchedulingOutcome {
    SCHEDULED,
    RUNNER_NOT_FOUND,
    RUNNER_INELIGIBLE,
    CAPACITY_EXHAUSTED,
    NO_COMPATIBLE_EXECUTION
}
