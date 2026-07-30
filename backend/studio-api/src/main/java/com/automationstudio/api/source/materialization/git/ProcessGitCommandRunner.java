package com.automationstudio.api.source.materialization.git;

import com.automationstudio.api.source.materialization.SourceMaterializationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ProcessGitCommandRunner implements GitCommandRunner {

    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);

    private final GitMaterializationProperties properties;

    ProcessGitCommandRunner(GitMaterializationProperties properties) {
        this.properties = properties;
    }

    @Override
    public GitCommandResult execute(
            List<String> arguments,
            Path workingDirectory,
            Path isolatedHome) {
        List<String> command = new ArrayList<>();
        command.add(properties.executable());
        command.add("-c");
        command.add("credential.helper=");
        command.add("-c");
        command.add("core.hooksPath=" + isolatedHome.resolve("hooks"));
        command.add("-c");
        command.add("filter.lfs.smudge=");
        command.add("-c");
        command.add("filter.lfs.process=");
        command.add("-c");
        command.add("filter.lfs.required=false");
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        isolateEnvironment(builder.environment(), isolatedHome);

        final Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw failure("GIT_PROCESS_START_FAILED", exception);
        }

        try (ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CapturedOutput> stdout = drains.submit(
                    () -> capture(process.getInputStream()));
            Future<CapturedOutput> stderr = drains.submit(
                    () -> capture(process.getErrorStream()));
            boolean completed = process.waitFor(
                    properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminate(process);
                awaitDrain(stdout);
                awaitDrain(stderr);
                throw failure("GIT_COMMAND_TIMEOUT", null);
            }
            CapturedOutput standardOutput = awaitDrain(stdout);
            CapturedOutput standardError = awaitDrain(stderr);
            if (standardOutput.overflow() || standardError.overflow()) {
                throw failure("GIT_OUTPUT_LIMIT_EXCEEDED", null);
            }
            if (process.exitValue() != 0) {
                throw failure("GIT_COMMAND_FAILED", null);
            }
            return new GitCommandResult(standardOutput.text().trim());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminate(process);
            throw failure("GIT_COMMAND_INTERRUPTED", exception);
        }
    }

    private void isolateEnvironment(Map<String, String> environment, Path isolatedHome) {
        String path = environment.get("PATH");
        String systemRoot = environment.get("SystemRoot");
        String temp = environment.get("TEMP");
        environment.clear();
        copy(environment, "PATH", path);
        copy(environment, "SystemRoot", systemRoot);
        copy(environment, "TEMP", temp);
        environment.put("HOME", isolatedHome.toString());
        environment.put("XDG_CONFIG_HOME", isolatedHome.toString());
        environment.put(
                "GIT_CONFIG_GLOBAL",
                isolatedHome.resolve("global.gitconfig").toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_ATTR_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "Never");
        environment.put("GIT_ASKPASS", "");
        environment.put("SSH_ASKPASS", "");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        environment.put("LC_ALL", "C");
    }

    private void copy(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private CapturedOutput capture(InputStream input) throws IOException {
        int maximum = properties.maxOutputBytes();
        ByteArrayOutputStream captured =
                new ByteArrayOutputStream(Math.min(maximum, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        boolean overflow = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = maximum - total;
            if (remaining > 0) {
                int retained = Math.min(read, remaining);
                captured.write(buffer, 0, retained);
                total += retained;
            }
            if (read > remaining) {
                overflow = true;
            }
        }
        return new CapturedOutput(
                captured.toString(StandardCharsets.UTF_8), overflow);
    }

    private CapturedOutput awaitDrain(Future<CapturedOutput> future) {
        try {
            return future.get(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("GIT_OUTPUT_CAPTURE_FAILED", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw failure("GIT_OUTPUT_CAPTURE_FAILED", exception);
        }
    }

    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(
                    TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(
                        TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private SourceMaterializationException failure(String code, Throwable cause) {
        return new SourceMaterializationException(
                code, "Git source materialization command failed", cause);
    }

    private record CapturedOutput(String text, boolean overflow) {
    }
}
