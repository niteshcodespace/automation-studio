package com.automationstudio.api.execution.engine.playwright.action;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlaywrightActionExecutorRegistry {
    private final Map<String, PlaywrightActionExecutor> executors;

    public PlaywrightActionExecutorRegistry(Collection<PlaywrightActionExecutor> executors) {
        if (executors == null) {
            throw failure("INVALID_EXECUTOR_REGISTRATION", "Action executor registration is invalid");
        }
        LinkedHashMap<String, PlaywrightActionExecutor> registered = new LinkedHashMap<>();
        for (PlaywrightActionExecutor executor : executors) {
            if (executor == null) {
                throw failure("INVALID_EXECUTOR_REGISTRATION", "Action executor is invalid");
            }
            String identifier = executor.actionType();
            if (identifier == null || identifier.isBlank() || !identifier.equals(identifier.trim())) {
                throw failure("INVALID_EXECUTOR_REGISTRATION", "Action executor identifier is invalid");
            }
            if (registered.putIfAbsent(identifier, executor) != null) {
                throw failure("DUPLICATE_ACTION_EXECUTOR", "Action executor is registered more than once");
            }
        }
        this.executors = Map.copyOf(registered);
    }

    public PlaywrightActionExecutor resolve(PlaywrightActionType actionType) {
        if (actionType == null) {
            throw failure("UNSUPPORTED_ACTION", "Manifest action is not supported");
        }
        PlaywrightActionExecutor executor = executors.get(actionType.manifestValue());
        if (executor == null) {
            throw failure("UNSUPPORTED_ACTION", "Manifest action is not supported");
        }
        return executor;
    }

    public int size() {
        return executors.size();
    }

    private PlaywrightActionException failure(String code, String message) {
        return new PlaywrightActionException(code, message);
    }
}
