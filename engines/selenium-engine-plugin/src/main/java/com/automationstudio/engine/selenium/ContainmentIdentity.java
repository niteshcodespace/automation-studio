package com.automationstudio.engine.selenium;

import java.util.Objects;

record ContainmentIdentity(String value) {
    ContainmentIdentity {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("Identity must not be blank");
    }
}
