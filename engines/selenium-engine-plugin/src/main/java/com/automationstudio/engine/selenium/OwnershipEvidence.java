package com.automationstudio.engine.selenium;

import java.util.Objects;

record OwnershipEvidence(ContainmentIdentity identity, ContainmentEvidence evidence) {
    OwnershipEvidence {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(evidence, "evidence");
    }
}
