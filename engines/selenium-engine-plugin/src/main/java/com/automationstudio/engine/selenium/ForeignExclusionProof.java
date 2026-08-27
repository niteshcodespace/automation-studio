package com.automationstudio.engine.selenium;

/**
 * Reserved capability for D2a2. D2a1 intentionally exposes no constructor or issuer because no
 * authoritative topology-exclusion source exists yet.
 */
final class ForeignExclusionProof {
    private ForeignExclusionProof() {
        throw new UnsupportedOperationException("Authoritative topology exclusion is unavailable");
    }
}
