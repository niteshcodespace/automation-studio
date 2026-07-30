package com.automationstudio.api.execution.workspace.local.access;

public final class EngineWorkspaceAccessException extends RuntimeException {

    private final String code;

    public EngineWorkspaceAccessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public EngineWorkspaceAccessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
