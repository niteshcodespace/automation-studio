package com.automationstudio.api.exception;

public class RunnerAlreadyDeregisteredException extends ResourceConflictException {

    public RunnerAlreadyDeregisteredException(String runnerKey) {
        super("Runner '" + runnerKey + "' is deregistered and cannot be registered again");
    }
}
