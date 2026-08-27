package com.automationstudio.engine.selenium;

enum DependencyEvidence {
    SATISFIED, UNKNOWN, DISPROVED;

    static DependencyEvidence from(ResourceDisposition disposition) {
        if (disposition instanceof ResourceDisposition.Ambiguous
                || disposition instanceof ResourceDisposition.LateOwnedPresent
                || disposition instanceof ResourceDisposition.LateOwnedAbsent) return DISPROVED;
        return UNKNOWN;
    }
}
