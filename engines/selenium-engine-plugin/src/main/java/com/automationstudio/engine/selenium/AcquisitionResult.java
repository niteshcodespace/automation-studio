package com.automationstudio.engine.selenium;

import java.util.Objects;

sealed interface AcquisitionResult permits AcquisitionResult.CreatedAndOwned,
        AcquisitionResult.DefiniteFailure, AcquisitionResult.ForeignCollision,
        AcquisitionResult.AmbiguousCompletion, AcquisitionResult.AmbiguousOwnership {

    record CreatedAndOwned(ContainmentIdentity identity, ContainmentEvidence causalResponse)
            implements AcquisitionResult {
        public CreatedAndOwned {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(causalResponse, "causalResponse");
        }
    }

    record DefiniteFailure(ContainmentEvidence evidence) implements AcquisitionResult {
        public DefiniteFailure { Objects.requireNonNull(evidence, "evidence"); }
    }

    record ForeignCollision(ContainmentIdentity candidate, ContainmentEvidence evidence)
            implements AcquisitionResult {
        public ForeignCollision {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record AmbiguousCompletion(ContainmentEvidence evidence) implements AcquisitionResult {
        public AmbiguousCompletion { Objects.requireNonNull(evidence, "evidence"); }
    }

    record AmbiguousOwnership(ContainmentIdentity candidate, ContainmentEvidence evidence)
            implements AcquisitionResult {
        public AmbiguousOwnership {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
