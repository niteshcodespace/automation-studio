package com.automationstudio.engine.selenium;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** The complete, closed D2a2 ownership fingerprint. */
record DockerResourceFingerprint(String containerId, UUID executionId,
        ContainmentResourceRole role, String attemptNonce, String imageIdentity,
        List<String> entrypoint, List<String> command, String user, boolean readOnlyRootfs,
        String restartPolicy, long restartMaximumRetryCount, String networkMode,
        boolean privileged, String pidMode, String ipcMode, List<String> isolationTuples,
        List<String> environment) {
    private static final Pattern ID = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern NONCE = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern IMAGE = Pattern.compile("(?:sha256:)?[a-f0-9]{64}");

    DockerResourceFingerprint {
        if (!ID.matcher(required(containerId, "containerId")).matches())
            throw new IllegalArgumentException("Invalid immutable Docker ID");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(role, "role");
        if (!NONCE.matcher(required(attemptNonce, "attemptNonce")).matches())
            throw new IllegalArgumentException("Invalid acquisition nonce");
        if (!IMAGE.matcher(required(imageIdentity, "imageIdentity")).matches())
            throw new IllegalArgumentException("Image identity must be immutable");
        entrypoint = ordered(entrypoint, "entrypoint");
        command = ordered(command, "command");
        user = required(user, "user");
        restartPolicy = required(restartPolicy, "restartPolicy");
        if (restartMaximumRetryCount < 0) throw new IllegalArgumentException("Invalid retry count");
        networkMode = required(networkMode, "networkMode");
        pidMode = Objects.requireNonNull(pidMode, "pidMode");
        ipcMode = required(ipcMode, "ipcMode");
        isolationTuples = sortedUnique(isolationTuples, "isolationTuples");
        environment = sortedUnique(environment, "environment");
    }

    DockerResourceFingerprint withContainerId(String id) {
        return new DockerResourceFingerprint(id, executionId, role, attemptNonce, imageIdentity,
                entrypoint, command, user, readOnlyRootfs, restartPolicy,
                restartMaximumRetryCount, networkMode, privileged, pidMode, ipcMode,
                isolationTuples, environment);
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return value;
    }

    private static List<String> ordered(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream().map(value -> required(value, name + " element")).toList();
    }

    private static List<String> sortedUnique(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        Set<String> sorted = new TreeSet<>();
        for (String value : values) {
            String checked = required(value, name + " element");
            if (!sorted.add(checked)) throw new IllegalArgumentException("Duplicate " + name);
        }
        return List.copyOf(sorted);
    }
}
