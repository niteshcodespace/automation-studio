package com.automationstudio.engine.selenium;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

final class SeleniumWorkerCommand {
    private static final Pattern IMAGE = Pattern.compile(
            "^[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]+)?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*"
                    + "@sha256:[a-f0-9]{64}$");
    static String workerName(UUID id) { return "as-selenium-worker-" + id.toString().replace("-", ""); }
    static String label(UUID id) { return id.toString().replace("-", ""); }
    static List<String> create(UUID id, String image, SeleniumContainmentLimits limits) {
        if (id == null || image == null || !IMAGE.matcher(image).matches())
            throw new IllegalArgumentException("Invalid worker image identity");
        String name = workerName(id), label = label(id);
        return List.of("docker", "create", "--interactive", "--name", name,
                "--label", "automation-studio.execution=" + label, "--network", "none",
                "--read-only", "--user", "10001:10001", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true", "--security-opt", "seccomp=builtin",
                "--pids-limit", String.valueOf(limits.pids()), "--memory", String.valueOf(limits.memoryBytes()),
                "--memory-swap", String.valueOf(limits.memoryBytes()), "--cpus", String.valueOf(limits.cpus()),
                "--tmpfs", "/work/runtime:rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=" + limits.tmpfsBytes(),
                "--tmpfs", "/work/tmp:rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=8388608",
                "--env", "LANG=C.UTF-8", image, id.toString());
    }
    static List<String> inspectName(String name) { validName(name); return List.of("docker", "container", "inspect",
            "--format", "{{.Id}}|{{index .Config.Labels \"automation-studio.execution\"}}", name); }
    static List<String> inspectId(String id) { validId(id); return List.of("docker", "container", "inspect", id); }
    static List<String> inspectIdentityId(String id) { validId(id); return List.of("docker", "container", "inspect",
            "--format", "{{.Id}}|{{index .Config.Labels \"automation-studio.execution\"}}", id); }
    static List<String> attach(String name) { validName(name); return List.of("docker", "start", "--attach", "--interactive", name); }
    static List<String> remove(String id) { validId(id); return List.of("docker", "rm", "--force", id); }
    private static void validName(String value) { if (value == null || !value.matches("as-selenium-worker-[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid worker identity"); }
    private static void validId(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid worker identity"); }
    private SeleniumWorkerCommand() {}
}
