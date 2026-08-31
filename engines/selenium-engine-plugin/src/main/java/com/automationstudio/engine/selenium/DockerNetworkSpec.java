package com.automationstudio.engine.selenium;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** The complete platform-owned D2b network request; callers supply no Docker options. */
record DockerNetworkSpec(UUID executionId, long acquisitionRevision, String attemptNonce,
        String deadlineCorrelation, String diagnosticName, Map<String, String> labels) {
    static final String EXECUTION = "com.automationstudio.execution-id";
    static final String ROLE = "com.automationstudio.resource-role";
    static final String REVISION = "com.automationstudio.acquisition-revision";
    static final String ATTEMPT = "com.automationstudio.attempt-nonce";
    static final String DEADLINE = "com.automationstudio.deadline-correlation";
    static final String ROLE_VALUE = "NETWORK";
    private static final Pattern TOKEN = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,63}");

    DockerNetworkSpec {
        Objects.requireNonNull(executionId, "executionId");
        if (acquisitionRevision <= 0) throw new IllegalArgumentException("Invalid revision");
        token(attemptNonce, "attemptNonce"); token(deadlineCorrelation, "deadlineCorrelation");
        if (diagnosticName == null || !NAME.matcher(diagnosticName).matches())
            throw new IllegalArgumentException("Invalid diagnostic name");
        labels = Map.copyOf(Objects.requireNonNull(labels, "labels"));
        if (!labels.equals(expectedLabels(executionId, acquisitionRevision, attemptNonce,
                deadlineCorrelation))) throw new IllegalArgumentException("Unexpected network labels");
    }

    static DockerNetworkSpec create(UUID executionId, long revision, String nonce,
            String deadlineCorrelation) {
        String executionToken = executionId.toString().replace("-", "");
        String name = "as-sel-net-" + executionToken.substring(0, 12) + "-" + nonce.substring(0, 12);
        return new DockerNetworkSpec(executionId, revision, nonce, deadlineCorrelation, name,
                expectedLabels(executionId, revision, nonce, deadlineCorrelation));
    }

    DockerNetworkFingerprint fingerprint(String id, String subnet, String gateway) {
        return new DockerNetworkFingerprint(id, executionId, acquisitionRevision, attemptNonce,
                deadlineCorrelation, subnet, gateway, labels);
    }

    static Map<String, String> expectedLabels(UUID executionId, long revision, String nonce,
            String deadlineCorrelation) {
        var labels = new LinkedHashMap<String, String>();
        labels.put(EXECUTION, executionId.toString()); labels.put(ROLE, ROLE_VALUE);
        labels.put(REVISION, Long.toUnsignedString(revision)); labels.put(ATTEMPT, nonce);
        labels.put(DEADLINE, deadlineCorrelation); return Map.copyOf(labels);
    }

    private static void token(String value, String name) {
        if (value == null || !TOKEN.matcher(value).matches())
            throw new IllegalArgumentException("Invalid " + name);
    }
}
