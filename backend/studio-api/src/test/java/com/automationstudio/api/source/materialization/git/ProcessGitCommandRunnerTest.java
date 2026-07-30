package com.automationstudio.api.source.materialization.git;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.source.materialization.SourceMaterializationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessGitCommandRunnerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsUnavailableExecutableWithoutLeakingItsValue() throws Exception {
        Path home = isolatedHome();
        ProcessGitCommandRunner runner = new ProcessGitCommandRunner(
                properties("definitely-missing-git-executable", Duration.ofSeconds(1), 1024));

        assertThatThrownBy(() -> runner.execute(
                List.of("--version"), temporaryDirectory, home))
                .isInstanceOf(SourceMaterializationException.class)
                .hasMessage("Git source materialization command failed")
                .hasMessageNotContaining("missing-git")
                .hasMessageNotContaining(temporaryDirectory.toString());
    }

    @Test
    void enforcesBoundedOutput() throws Exception {
        Path home = isolatedHome();
        ProcessGitCommandRunner runner = new ProcessGitCommandRunner(
                properties("git", Duration.ofSeconds(5), 1));

        assertThatThrownBy(() -> runner.execute(
                List.of("--version"), temporaryDirectory, home))
                .isInstanceOf(SourceMaterializationException.class)
                .extracting(exception -> ((SourceMaterializationException) exception).code())
                .isEqualTo("GIT_OUTPUT_LIMIT_EXCEEDED");
    }

    @Test
    void terminatesACommandAtItsDeadline() throws Exception {
        Path home = isolatedHome();
        ProcessGitCommandRunner runner = new ProcessGitCommandRunner(
                properties("git", Duration.ofMillis(100), 1024));

        assertThatThrownBy(() -> runner.execute(
                List.of("credential", "fill"), temporaryDirectory, home))
                .isInstanceOf(SourceMaterializationException.class)
                .extracting(exception -> ((SourceMaterializationException) exception).code())
                .isEqualTo("GIT_COMMAND_TIMEOUT");
    }

    private Path isolatedHome() throws Exception {
        Path home = temporaryDirectory.resolve("home-" + System.nanoTime());
        Files.createDirectory(home);
        Files.createDirectory(home.resolve("hooks"));
        return home;
    }

    private GitMaterializationProperties properties(
            String executable,
            Duration timeout,
            int outputLimit) {
        return new GitMaterializationProperties(
                executable, timeout, outputLimit, false);
    }
}
