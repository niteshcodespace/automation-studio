package com.automationstudio.engine.selenium;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

record DependencyEvidenceSnapshot<N>(long revision, Map<N, DependencyEvidence> evidence) {
    DependencyEvidenceSnapshot {
        if (revision < 0) throw new IllegalArgumentException("Revision must not be negative");
        Objects.requireNonNull(evidence, "evidence");
        var copy = new HashMap<N, DependencyEvidence>();
        evidence.forEach((node, value) -> copy.put(Objects.requireNonNull(node, "node"),
                Objects.requireNonNull(value, "dependency evidence")));
        evidence = Map.copyOf(copy);
    }
}
