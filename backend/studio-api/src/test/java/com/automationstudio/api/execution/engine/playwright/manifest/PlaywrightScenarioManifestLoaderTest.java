package com.automationstudio.api.execution.engine.playwright.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.workspace.WorkspaceId;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PlaywrightScenarioManifestLoaderTest {

    private static final String MINIMAL = """
            {
              "schemaVersion": "1.0",
              "name": "smoke",
              "scenarios": [{
                "id": "home",
                "name": "Home page",
                "steps": [{"id": "open", "action": "navigate", "url": "/"}]
              }]
            }
            """;

    @TempDir Path temporaryDirectory;

    private final PlaywrightScenarioManifestLoader loader =
            new PlaywrightScenarioManifestLoader();

    @Test
    void loadsValidMinimalManifestFromPreparedWorkspace() throws IOException {
        PlaywrightScenarioManifest manifest = load("scenario.json", MINIMAL);

        assertThat(manifest.schemaVersion()).isEqualTo("1.0");
        assertThat(manifest.name()).isEqualTo("smoke");
        assertThat(manifest.scenarios()).extracting(PlaywrightScenario::id).containsExactly("home");
        assertThat(manifest.scenarios().getFirst().steps())
                .extracting(PlaywrightStep::id)
                .containsExactly("open");
    }

    @Test
    void loadsRepresentativeManifestAndPreservesOrdering() throws IOException {
        PlaywrightScenarioManifest manifest = load("nested/scenario.json", """
                {
                  "schemaVersion": "1.0",
                  "name": "checkout",
                  "scenarios": [{
                    "id": "checkout",
                    "name": "Checkout",
                    "steps": [
                      {"id":"open","action":"navigate","url":"/shop","timeoutMs":1000},
                      {"id":"choose","action":"click","selector":"[data-test=product]"},
                      {"id":"quantity","action":"fill","selector":"#quantity","value":"2"},
                      {"id":"visible","action":"assert-visible","selector":"#total"},
                      {"id":"text","action":"assert-text","selector":"#total","expected":"20.00"},
                      {"id":"url","action":"assert-url","expected":"/shop"}
                    ]
                  }]
                }
                """);

        assertThat(manifest.scenarios().getFirst().steps())
                .extracting(PlaywrightStep::action)
                .containsExactly(
                        PlaywrightActionType.NAVIGATE,
                        PlaywrightActionType.CLICK,
                        PlaywrightActionType.FILL,
                        PlaywrightActionType.ASSERT_VISIBLE,
                        PlaywrightActionType.ASSERT_TEXT,
                        PlaywrightActionType.ASSERT_URL);
        assertThat(manifest.scenarios().getFirst().steps().getFirst().timeout())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @ParameterizedTest
    @MethodSource("invalidVersions")
    void rejectsInvalidSchemaVersions(String versionJson) {
        assertFailure(
                MINIMAL.replace("\"1.0\"", versionJson),
                versionJson.equals("\"1.1\"") || versionJson.equals("\"2.0\"")
                        ? "UNSUPPORTED_SCHEMA_VERSION"
                        : null);
    }

    private static Stream<String> invalidVersions() {
        return Stream.of("null", "\"\"", "1", "1.0", "\"1\"", "\"1.1\"", "\"2.0\"", "{}", "[]");
    }

    @Test
    void rejectsMissingSchemaVersion() {
        assertFailure(MINIMAL.replace("  \"schemaVersion\": \"1.0\",\n", ""), null);
    }

    @ParameterizedTest
    @MethodSource("invalidJson")
    void rejectsMalformedDuplicateUnknownAndIncorrectJson(String json) {
        assertFailure(json, null);
    }

    private static Stream<String> invalidJson() {
        return Stream.of(
                "{",
                "[]",
                "true",
                MINIMAL + "{}",
                MINIMAL.replace(
                        "\"schemaVersion\": \"1.0\"",
                        "\"schemaVersion\": \"1.0\", \"schemaVersion\": \"1.0\""),
                MINIMAL.replace("\"name\": \"smoke\"", "\"name\": \"smoke\", \"unknown\": true"),
                MINIMAL.replace("\"name\": \"Home page\"", "\"name\": \"Home page\", \"unknown\": true"),
                MINIMAL.replace("\"url\": \"/\"", "\"url\": \"/\", \"unknown\": true"),
                MINIMAL.replace("\"name\": \"smoke\"", "\"name\": 1"),
                MINIMAL.replace("\"scenarios\": [", "\"scenarios\": {"));
    }

    @ParameterizedTest
    @MethodSource("invalidManifestStructures")
    void rejectsInvalidManifestScenarioAndStepStructures(String json) {
        assertFailure(json, null);
    }

    private static Stream<String> invalidManifestStructures() {
        String duplicateScenario = MINIMAL.replace(
                "}]",
                "}, {\"id\":\"home\",\"name\":\"Again\",\"steps\":[{\"id\":\"x\",\"action\":\"navigate\",\"url\":\"/\"}]}]");
        String duplicateStep = MINIMAL.replace(
                "{\"id\": \"open\", \"action\": \"navigate\", \"url\": \"/\"}",
                "{\"id\":\"same\",\"action\":\"navigate\",\"url\":\"/\"},"
                        + "{\"id\":\"same\",\"action\":\"navigate\",\"url\":\"/next\"}");
        return Stream.of(
                MINIMAL.replace("\"name\": \"smoke\",", ""),
                MINIMAL.replace("\"scenarios\": [{", "\"scenarios\": []"),
                duplicateScenario,
                MINIMAL.replace("\"id\": \"home\",", ""),
                MINIMAL.replace("\"id\": \"home\"", "\"id\": \" home\""),
                MINIMAL.replace(
                        "\"steps\": [{\"id\": \"open\", \"action\": \"navigate\", \"url\": \"/\"}]",
                        "\"steps\": []"),
                duplicateStep,
                MINIMAL.replace("\"action\": \"navigate\",", ""),
                MINIMAL.replace("\"navigate\"", "\"evaluate\""),
                MINIMAL.replace(", \"url\": \"/\"", ""),
                MINIMAL.replace("\"url\": \"/\"", "\"url\": 1"),
                MINIMAL.replace("\"url\": \"/\"", "\"url\": \"/\", \"selector\": \"body\""),
                MINIMAL.replace("\"url\": \"/\"", "\"url\": \"/\", \"timeoutMs\": 99"),
                MINIMAL.replace("\"url\": \"/\"", "\"url\": \"/\", \"timeoutMs\": 300001"),
                MINIMAL.replace("\"url\": \"/\"", "\"url\": \"/\", \"timeoutMs\": \"1000\""),
                MINIMAL.replace("\"url\": \"/\"", "\"url\": \"/\", \"timeoutMs\": 1000.0"));
    }

    @ParameterizedTest
    @MethodSource("invalidActionShapes")
    void rejectsActionSpecificMissingAndProhibitedFields(String step) {
        assertFailure(MINIMAL.replace(
                "{\"id\": \"open\", \"action\": \"navigate\", \"url\": \"/\"}", step), null);
    }

    private static Stream<String> invalidActionShapes() {
        return Stream.of(
                "{\"id\":\"x\",\"action\":\"click\"}",
                "{\"id\":\"x\",\"action\":\"click\",\"selector\":\"#x\",\"value\":\"bad\"}",
                "{\"id\":\"x\",\"action\":\"fill\",\"selector\":\"#x\"}",
                "{\"id\":\"x\",\"action\":\"assert-visible\",\"selector\":\"#x\",\"expected\":\"bad\"}",
                "{\"id\":\"x\",\"action\":\"assert-text\",\"selector\":\"#x\"}",
                "{\"id\":\"x\",\"action\":\"assert-url\",\"selector\":\"body\",\"expected\":\"/\"}");
    }

    @Test
    void rejectsMissingManifest() {
        assertThatThrownBy(() -> loader.load(suite("missing.json"), access()))
                .isInstanceOfSatisfying(
                        PlaywrightManifestException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MANIFEST_MISSING"));
    }

    @ParameterizedTest
    @MethodSource("unsafeReferences")
    void rejectsUnsafeManifestReferences(String reference) {
        assertThatThrownBy(() -> loader.load(suite(reference), access()))
                .isInstanceOfSatisfying(
                        PlaywrightManifestException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo("UNSAFE_MANIFEST_LOCATION");
                            assertThat(failure.getMessage())
                                    .doesNotContain(temporaryDirectory.toString());
                        });
    }

    private static Stream<String> unsafeReferences() {
        return Stream.of("../scenario.json", "./scenario.json", "/scenario.json", "C:\\scenario.json");
    }

    @Test
    void rejectsSymbolicLinkEscapeWhereSupported() throws IOException {
        Path outside = temporaryDirectory.resolve("outside.json");
        Files.writeString(outside, MINIMAL);
        Path source = sourceDirectory();
        Path link = source.resolve("scenario.json");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable");
        }

        assertThatThrownBy(() -> loader.load(suite("scenario.json"), access()))
                .isInstanceOfSatisfying(
                        PlaywrightManifestException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("UNSAFE_MANIFEST_LOCATION"));
    }

    @Test
    void rejectsOversizedManifestWithoutExposingContent() throws IOException {
        String secretMarker = "secret-marker";
        byte[] bytes = new byte[PlaywrightScenarioManifestLoader.MAX_MANIFEST_BYTES + 1];
        byte[] marker = secretMarker.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, bytes, 0, marker.length);
        Files.write(sourceDirectory().resolve("large.json"), bytes);

        assertThatThrownBy(() -> loader.load(suite("large.json"), access()))
                .isInstanceOfSatisfying(
                        PlaywrightManifestException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo("MANIFEST_TOO_LARGE");
                            assertThat(failure.getMessage()).doesNotContain(secretMarker);
                        });
    }

    @Test
    void rejectsClosedWorkspaceAccessAsUnreadable() {
        TestAccess access = access();
        access.close();

        assertThatThrownBy(() -> loader.load(suite("scenario.json"), access))
                .isInstanceOfSatisfying(
                        PlaywrightManifestException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MANIFEST_UNREADABLE"));
    }

    @Test
    void contractsDefensivelyCopyCollections() {
        PlaywrightStep step = new PlaywrightStep(
                "open", PlaywrightActionType.NAVIGATE, null, "/", null, null, null);
        ArrayList<PlaywrightStep> callerSteps = new ArrayList<>(List.of(step));
        PlaywrightScenario scenario = new PlaywrightScenario("home", "Home", callerSteps);
        ArrayList<PlaywrightScenario> callerScenarios = new ArrayList<>(List.of(scenario));
        PlaywrightScenarioManifest manifest =
                new PlaywrightScenarioManifest("1.0", "Smoke", callerScenarios);

        callerSteps.clear();
        callerScenarios.clear();

        assertThat(scenario.steps()).containsExactly(step);
        assertThat(manifest.scenarios()).containsExactly(scenario);
        assertThatThrownBy(() -> scenario.steps().add(step))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> manifest.scenarios().add(scenario))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private PlaywrightScenarioManifest load(String reference, String json) throws IOException {
        Path manifest = sourceDirectory().resolve(reference);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, json);
        return loader.load(suite(reference), access());
    }

    private void assertFailure(String json, String expectedCode) {
        assertThatThrownBy(() -> load("scenario.json", json))
                .isInstanceOfSatisfying(
                        PlaywrightManifestException.class,
                        failure -> {
                            if (expectedCode != null) {
                                assertThat(failure.code()).isEqualTo(expectedCode);
                            }
                            assertThat(failure.getMessage())
                                    .doesNotContain(json)
                                    .doesNotContain(temporaryDirectory.toString());
                            assertThat(failure.getCause()).isNull();
                        });
    }

    private ExecutionSuiteSnapshot suite(String reference) {
        return new ExecutionSuiteSnapshot(
                UUID.randomUUID(),
                "suite",
                "playwright-java",
                "1.61.0",
                "browser",
                null,
                reference,
                Map.of(),
                Map.of());
    }

    private TestAccess access() {
        return new TestAccess(sourceDirectory());
    }

    private Path sourceDirectory() {
        Path source = temporaryDirectory.resolve("workspace").resolve("source");
        try {
            Files.createDirectories(source);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        return source;
    }

    private static final class TestAccess implements EngineWorkspaceAccess {
        private final Path source;
        private boolean open = true;

        private TestAccess(Path source) {
            this.source = source;
        }

        @Override
        public WorkspaceId workspaceId() {
            return new WorkspaceId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
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
