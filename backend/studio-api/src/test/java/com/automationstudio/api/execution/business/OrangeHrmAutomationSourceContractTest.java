package com.automationstudio.api.execution.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionContextBuilder;
import com.automationstudio.api.execution.ExecutionSecretReference;
import com.automationstudio.api.execution.ExecutionVariableSource;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifest;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightScenarioManifestLoader;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccess;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrangeHrmAutomationSourceContractTest {

    private static final Path PACKAGE_PATH =
            Path.of("demo-projects", "orangehrm-login-smoke");
    private static final Set<String> PACKAGE_FILES = Set.of("README.md", "scenario.json");

    @TempDir Path temporaryDirectory;

    @Test
    void loadsCanonicalSchemaTwoManifestWithDeterministicSensitiveLoginFlow()
            throws IOException {
        PlaywrightScenarioManifest manifest = loadFromPreparedWorkspace();

        assertThat(manifest.schemaVersion()).isEqualTo("2.0");
        assertThat(manifest.name()).isEqualTo("OrangeHRM login smoke");
        assertThat(manifest.scenarios()).hasSize(1);
        List<PlaywrightStep> steps = manifest.scenarios().getFirst().steps();
        assertThat(steps).extracting(PlaywrightStep::id).containsExactly(
                "open-login",
                "login-form-visible",
                "enter-username",
                "enter-password",
                "submit-login",
                "dashboard-url",
                "dashboard-visible");
        assertThat(steps).extracting(PlaywrightStep::action).containsExactly(
                PlaywrightActionType.NAVIGATE,
                PlaywrightActionType.ASSERT_VISIBLE,
                PlaywrightActionType.FILL,
                PlaywrightActionType.FILL,
                PlaywrightActionType.CLICK,
                PlaywrightActionType.ASSERT_URL,
                PlaywrightActionType.ASSERT_VISIBLE);
        assertThat(steps).allSatisfy(step -> {
            if (step.selector() != null) {
                assertThat(step.selector().value()).isNotBlank();
            }
        });
        assertThat(steps.get(0).url()).isEqualTo("/web/index.php/auth/login");
        assertThat(steps.get(2).secretRef()).isEqualTo(OrangeHrmExecutionFixture.USERNAME_SECRET);
        assertThat(steps.get(3).secretRef()).isEqualTo(OrangeHrmExecutionFixture.PASSWORD_SECRET);
        assertThat(steps.get(2).value()).isNull();
        assertThat(steps.get(3).value()).isNull();
        assertThat(steps.get(5).expected())
                .isEqualTo("/web/index.php/dashboard/index");
    }

    @Test
    void fixtureBuildsImmutableExecutionDataWithExactSourceSuiteAndSecretReferences() {
        ExecutionContext context = new ExecutionContextBuilder(new SensitiveKeyDetector())
                .build(OrangeHrmExecutionFixture.contextSource());
        Map<String, Object> sourceSnapshot =
                OrangeHrmExecutionFixture.sourceReference().toSnapshot();

        assertThat(context.projectId()).isNotNull();
        assertThat(context.suite().suiteReference())
                .isEqualTo(OrangeHrmExecutionFixture.SUITE_REFERENCE)
                .doesNotStartWith("/")
                .doesNotContain("..", "\\");
        assertThat(context.suite().engineId()).isEqualTo(OrangeHrmExecutionFixture.ENGINE_NAME);
        assertThat(context.suite().engineVersion())
                .isEqualTo(OrangeHrmExecutionFixture.ENGINE_VERSION);
        assertThat(context.variables()).containsOnlyKeys("baseUrl");
        assertThat(context.variables().get("baseUrl").value())
                .isEqualTo(OrangeHrmExecutionFixture.BASE_URL);
        assertThat(context.variables().get("baseUrl").source())
                .isEqualTo(ExecutionVariableSource.EXECUTION);
        assertThat(context.secretReferences())
                .extracting(ExecutionSecretReference::name)
                .containsExactlyInAnyOrder(
                        OrangeHrmExecutionFixture.USERNAME_SECRET,
                        OrangeHrmExecutionFixture.PASSWORD_SECRET);
        assertThat(sourceSnapshot)
                .containsEntry("revision", OrangeHrmExecutionFixture.SOURCE_REVISION)
                .containsEntry("sourceLocation", "demo-projects/orangehrm-login-smoke");
        assertThatThrownBy(() -> sourceSnapshot.put("revision", "mutable"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void packageIsDeclarativeCredentialFreeAndDocumented() throws IOException {
        Path sourcePackage = repositoryRoot().resolve(PACKAGE_PATH);
        List<Path> files;
        try (var entries = Files.list(sourcePackage)) {
            files = entries.toList();
        }
        assertThat(files).allMatch(Files::isRegularFile);
        assertThat(files).extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrderElementsOf(PACKAGE_FILES);

        String manifest = Files.readString(sourcePackage.resolve("scenario.json"));
        String readme = Files.readString(sourcePackage.resolve("README.md"));
        assertThat(readme)
                .contains(OrangeHrmExecutionFixture.SUITE_REFERENCE)
                .contains("${baseUrl}")
                .contains(OrangeHrmExecutionFixture.USERNAME_SECRET)
                .contains(OrangeHrmExecutionFixture.PASSWORD_SECRET)
                .contains("input[name='username']")
                .contains("input[name='password']")
                .contains("button[type='submit']")
                .contains("header h6");
        assertThat(manifest)
                .doesNotContain("AS025E_ORANGEHRM_USERNAME")
                .doesNotContain("AS025E_ORANGEHRM_PASSWORD")
                .doesNotContain("operator-environment")
                .doesNotContain("http://", "https://")
                .doesNotContain("${baseUrl}")
                .doesNotContain("<script", "javascript:");
    }

    private PlaywrightScenarioManifest loadFromPreparedWorkspace() throws IOException {
        Path workspaceSource = temporaryDirectory.resolve("workspace").resolve("source");
        Path targetPackage = workspaceSource.resolve(PACKAGE_PATH);
        Files.createDirectories(targetPackage);
        Path repositoryPackage = repositoryRoot().resolve(PACKAGE_PATH);
        for (String filename : PACKAGE_FILES) {
            Files.copy(repositoryPackage.resolve(filename), targetPackage.resolve(filename));
        }
        ExecutionContext context = new ExecutionContextBuilder(new SensitiveKeyDetector())
                .build(OrangeHrmExecutionFixture.contextSource());
        return new PlaywrightScenarioManifestLoader().load(
                context.suite(), new TestAccess(workspaceSource));
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("demo-projects"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("Repository root could not be located");
        }
        return current;
    }

    private static final class TestAccess implements EngineWorkspaceAccess {
        private final Path source;
        private boolean open = true;

        private TestAccess(Path source) {
            this.source = source;
        }

        @Override
        public WorkspaceId workspaceId() {
            return new WorkspaceId(UUID.fromString("025e0000-0000-4000-8000-000000000003"));
        }

        @Override
        public Path sourceDirectory() {
            return source;
        }

        @Override
        public Path artifactsDirectory() {
            return source.getParent().resolve("artifacts");
        }

        @Override
        public Path metadataDirectory() {
            return source.getParent().resolve("metadata");
        }

        @Override
        public Path temporaryDirectory() {
            return source.getParent().resolve("temp");
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
