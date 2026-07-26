package com.automationstudio.api.service;

public class ExecutionHeartbeatException extends RuntimeException {

    private final HeartbeatFailure failure;

    public ExecutionHeartbeatException(HeartbeatFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public HeartbeatFailure getFailure() {
        return failure;
    }
}
