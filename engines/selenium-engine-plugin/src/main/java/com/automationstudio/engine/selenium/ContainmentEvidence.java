package com.automationstudio.engine.selenium;

import java.util.Objects;

record ContainmentEvidence(String value) {
    ContainmentEvidence {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("Evidence must not be blank");
    }
}
