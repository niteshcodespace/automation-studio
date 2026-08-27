package com.automationstudio.engine.selenium;

import java.util.Objects;

record EligibilityDecision<N>(N node, long revision, DependencyEvidence status) {
    EligibilityDecision {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(status, "status");
        if (revision < 0) throw new IllegalArgumentException("Revision must not be negative");
    }
}
