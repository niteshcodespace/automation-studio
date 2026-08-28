package com.automationstudio.engine.selenium;

import java.util.Objects;

/** Platform-owned worker policy. No caller-defined Docker isolation option is accepted. */
final class DockerResourceTemplate {
    private final String imageReference;
    private final ContainmentResourceRole role;
    private final SeleniumContainmentLimits limits;

    private DockerResourceTemplate(String imageReference, SeleniumContainmentLimits limits) {
        this(imageReference, limits, ContainmentResourceRole.WORKER);
    }
    private DockerResourceTemplate(String imageReference, SeleniumContainmentLimits limits,
            ContainmentResourceRole role) {
        this.imageReference = Objects.requireNonNull(imageReference, "imageReference");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.role = Objects.requireNonNull(role, "role");
    }

    static DockerResourceTemplate worker(String imageIdentity, SeleniumContainmentLimits limits) {
        return new DockerResourceTemplate(imageIdentity, limits);
    }

    DockerResourceSpec bind(java.util.UUID executionId, ContainmentResourceRole role, String nonce) {
        if (role != this.role) throw new IllegalArgumentException("Template role mismatch");
        String initialIdentity = imageReference.matches("sha256:[a-f0-9]{64}") ? imageReference
                : "sha256:" + "0".repeat(64);
        return new DockerResourceSpec(executionId, role, nonce, imageReference,
                initialIdentity, java.util.List.of(
                "/opt/java/openjdk/bin/java", "-Xms16m", "-Xmx64m",
                "-Djava.io.tmpdir=/work/tmp", "-jar", "/opt/worker/worker.jar"),
                java.util.List.of(executionId.toString()), "10001:10001", true, "no", 0,
                "none", false, "private", "private", java.util.List.of("cap-drop=ALL",
                        "security-opt=no-new-privileges:true", "security-opt=seccomp=builtin",
                        "tmpfs=/work/runtime=rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size="
                                + limits.tmpfsBytes(),
                        "tmpfs=/work/tmp=rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=8388608"),
                java.util.List.of("LANG=C.UTF-8"));
    }

    SeleniumContainmentLimits limits() { return limits; }
}
