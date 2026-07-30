package com.automationstudio.api.execution;

public record ExecutionSecretReference(String name, Object reference) {

    public ExecutionSecretReference {
        if (name == null || name.isBlank()) {
            throw new InvalidExecutionContextException("Secret reference name must not be blank");
        }
        if (reference == null) {
            throw new InvalidExecutionContextException(
                    "Secret reference " + name + " must not be null");
        }
        reference = ImmutableExecutionValue.value(reference, "Secret reference " + name);
    }
}
