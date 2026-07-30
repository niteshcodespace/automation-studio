package com.automationstudio.api.execution;

import java.util.Objects;

public record ExecutionVariable(
        String name,
        Object value,
        ExecutionVariableSource source) {

    public ExecutionVariable {
        if (name == null || name.isBlank()) {
            throw new InvalidExecutionContextException("Execution variable name must not be blank");
        }
        if (value == null) {
            throw new InvalidExecutionContextException(
                    "Execution variable " + name + " must not be null");
        }
        value = ImmutableExecutionValue.value(value, "Execution variable " + name);
        source = Objects.requireNonNull(source, "Execution variable source must not be null");
    }
}
