package com.automationstudio.api.execution.workspace.local;

public class LocalWorkspaceException extends RuntimeException {

    public LocalWorkspaceException(String message) {
        super(message);
    }

    public LocalWorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
