package com.automationstudio.engine.selenium;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Singular approved gateway container policy. */
record D2cGatewaySpec(UUID executionId, long topologyGeneration, long revision, String nonce,
        String deadlineCorrelation, String imageDigest, String executableDigest, Map<String,String> labels) {
    static final String ENTRYPOINT = "/opt/automation-studio/gateway-entrypoint";
    D2cGatewaySpec {
        Objects.requireNonNull(executionId); token(nonce); token(deadlineCorrelation);
        digest(imageDigest); digest(executableDigest);
        if (topologyGeneration <= 0 || revision <= 0) throw new IllegalArgumentException("Invalid gateway revision");
        labels = Map.copyOf(labels);
        if (!labels.equals(expectedLabels(executionId, topologyGeneration, revision, nonce, deadlineCorrelation,imageDigest,executableDigest)))
            throw new IllegalArgumentException("Unexpected gateway labels");
    }
    List<String> entrypoint() { return List.of(ENTRYPOINT); }
    List<String> command() { return List.of(); }
    List<String> capabilities() { return List.of("cap-drop=ALL", "cap-add=NET_ADMIN"); }
    List<String> environment() { return List.of("AS_GATEWAY_MODE=TOPOLOGY_ONLY"); }
    List<String> tmpfs() { return List.of("/run:rw,nosuid,nodev,noexec,size=1m,mode=0755"); }
    String user() { return "65532:65532"; }
    String pidMode() { return ""; }
    String ipcMode() { return "private"; }
    String networkMode() { return "none"; }
    DockerResourceSpec resourceSpec() {
        return new DockerResourceSpec(executionId, ContainmentResourceRole.GATEWAY, nonce,
                imageDigest, imageDigest, entrypoint(), command(), user(), true, "no", 0,
                networkMode(), false, pidMode(), ipcMode(), List.of(
                "cap-add=NET_ADMIN", "cap-drop=ALL",
                "tmpfs=/run=rw,nosuid,nodev,noexec,size=1m,mode=0755"), environment());
    }
    static Map<String,String> expectedLabels(UUID id, long generation, long revision, String nonce, String deadline,String imageDigest,String executableDigest) {
        return Map.ofEntries(Map.entry("com.automationstudio.execution-id", id.toString()),
                Map.entry("com.automationstudio.resource-role", "GATEWAY"),Map.entry("com.automationstudio.topology-generation", Long.toString(generation)),
                Map.entry("com.automationstudio.acquisition-revision", Long.toString(revision)),Map.entry("com.automationstudio.attempt-nonce", nonce),
                Map.entry("com.automationstudio.deadline-correlation", deadline),Map.entry("com.automationstudio.image-digest",imageDigest),
                Map.entry("com.automationstudio.executable-digest",executableDigest),Map.entry("automation-studio.execution", id.toString()),
                Map.entry("automation-studio.role", "GATEWAY"),Map.entry("automation-studio.attempt", nonce));
    }
    private static void token(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid token"); }
    private static void digest(String value) { if (value == null || !value.matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid digest"); }
}
