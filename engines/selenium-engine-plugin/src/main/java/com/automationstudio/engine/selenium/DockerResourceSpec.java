package com.automationstudio.engine.selenium;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** Platform-owned create attributes; no caller-defined Docker options are accepted. */
record DockerResourceSpec(UUID executionId, ContainmentResourceRole role, String attemptNonce,
        String imageReference, String imageIdentity, List<String> entrypoint, List<String> command, String user,
        boolean readOnlyRootfs, String restartPolicy, long restartMaximumRetryCount,
        String networkMode, boolean privileged, String pidMode, String ipcMode,
        List<String> isolationTuples, List<String> environment) {
    private static final Pattern NONCE = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern IMAGE = Pattern.compile("(?:sha256:)?[a-f0-9]{64}");

    DockerResourceSpec {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(role, "role");
        if (attemptNonce == null || !NONCE.matcher(attemptNonce).matches())
            throw new IllegalArgumentException("Invalid acquisition nonce");
        imageReference = required(imageReference, "imageReference");
        if (imageIdentity == null || !IMAGE.matcher(imageIdentity).matches())
            throw new IllegalArgumentException("Image identity must be immutable");
        entrypoint = ordered(entrypoint, "entrypoint");
        command = ordered(command, "command");
        user = required(user, "user");
        restartPolicy = required(restartPolicy, "restartPolicy");
        if (restartMaximumRetryCount < 0) throw new IllegalArgumentException("Invalid retry count");
        networkMode = required(networkMode, "networkMode");
        pidMode = required(pidMode, "pidMode");
        ipcMode = required(ipcMode, "ipcMode");
        isolationTuples = sorted(isolationTuples, "isolationTuples");
        environment = sorted(environment, "environment");
    }

    DockerResourceFingerprint fingerprint(String immutableId) {
        return new DockerResourceFingerprint(immutableId, executionId, role, attemptNonce,
                imageIdentity, entrypoint, command, user, readOnlyRootfs, restartPolicy,
                restartMaximumRetryCount, networkMode, privileged, pidMode, ipcMode,
                isolationTuples, environment);
    }

    DockerResourceSpec withResolvedImage(String resolved) {
        return new DockerResourceSpec(executionId, role, attemptNonce, imageReference, resolved,
                entrypoint, command, user, readOnlyRootfs, restartPolicy,
                restartMaximumRetryCount, networkMode, privileged, pidMode, ipcMode,
                isolationTuples, environment);
    }

    DockerResourceSpec withAttempt(String nonce) {
        return new DockerResourceSpec(executionId, role, nonce, imageReference, imageIdentity, entrypoint, command,
                user, readOnlyRootfs, restartPolicy, restartMaximumRetryCount, networkMode,
                privileged, pidMode, ipcMode, isolationTuples, environment);
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
    private static List<String> sorted(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        var result = new TreeSet<String>();
        for (String value : values) {
            String checked = required(value, name + " element");
            if (!result.add(checked)) throw new IllegalArgumentException("Duplicate " + name);
        }
        return List.copyOf(result);
    }
}
