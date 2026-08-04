package com.automationstudio.api.execution.business;

import java.util.Objects;

public record OrangeHrmQualificationEvidence(
        String runnerOs,
        String runnerArchitecture,
        String javaVersion,
        String playwrightVersion,
        String browserProduct,
        String browserBuild,
        String browserExecutableSource,
        String targetClassification,
        String manifestRevision,
        long passed,
        long failed,
        long errors) {

    public OrangeHrmQualificationEvidence {
        requireSafe(runnerOs);
        requireSafe(runnerArchitecture);
        requireSafe(javaVersion);
        requireSafe(playwrightVersion);
        requireSafe(browserProduct);
        requireSafe(browserBuild);
        requireSafe(browserExecutableSource);
        requireSafe(targetClassification);
        requireSafe(manifestRevision);
        if (passed < 0 || failed < 0 || errors < 0) {
            throw new IllegalArgumentException("Qualification result counts are invalid");
        }
    }

    private static void requireSafe(String value) {
        Objects.requireNonNull(value, "Qualification evidence is invalid");
        if (value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9._() /:-]+")) {
            throw new IllegalArgumentException("Qualification evidence is invalid");
        }
    }
}
