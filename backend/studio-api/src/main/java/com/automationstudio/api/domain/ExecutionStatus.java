package com.automationstudio.api.domain;

public enum ExecutionStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    CANCEL_REQUESTED,
    PASSED,
    FAILED,
    CANCELLED,
    ERROR
}
