package com.automationstudio.api.execution.engine.playwright.manifest;

import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccess;
import com.automationstudio.api.execution.workspace.local.access.EngineWorkspaceAccessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class PlaywrightScenarioManifestLoader {

    public static final int MAX_MANIFEST_BYTES = 1_048_576;
    public static final int MAX_JSON_DEPTH = 32;
    public static final int MAX_REFERENCE_LENGTH = 512;

    private static final Set<String> MANIFEST_FIELDS =
            Set.of("schemaVersion", "name", "scenarios");
    private static final Set<String> SCENARIO_FIELDS = Set.of("id", "name", "steps");
    private static final Set<String> STEP_FIELDS =
            Set.of("id", "action", "selector", "url", "value", "expected", "timeoutMs");
    private static final Set<String> VERSION_2_STEP_FIELDS =
            Set.of("id", "action", "selector", "url", "value", "secretRef", "expected", "timeoutMs");

    private final ObjectMapper objectMapper;

    public PlaywrightScenarioManifestLoader() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_JSON_DEPTH)
                        .maxStringLength(PlaywrightStep.MAX_DATA_LENGTH)
                        .build())
                .build();
        this.objectMapper = new ObjectMapper(factory);
    }

    public PlaywrightScenarioManifest load(
            ExecutionSuiteSnapshot suite, EngineWorkspaceAccess workspaceAccess) {
        Objects.requireNonNull(suite, "Execution suite snapshot must not be null");
        Objects.requireNonNull(workspaceAccess, "Engine workspace access must not be null");
        Path manifest = resolveManifest(workspaceAccess, suite.suiteReference());
        return parse(readBounded(manifest));
    }

    private Path resolveManifest(EngineWorkspaceAccess access, String reference) {
        if (!access.isOpen()) {
            throw failure("MANIFEST_UNREADABLE", "Scenario manifest could not be read");
        }
        if (reference == null
                || reference.isBlank()
                || reference.length() > MAX_REFERENCE_LENGTH
                || reference.indexOf('\0') >= 0) {
            throw unsafeLocation();
        }
        try {
            Path relative = Path.of(reference);
            if (relative.isAbsolute()
                    || relative.getNameCount() == 0
                    || pathContainsTraversal(relative)) {
                throw unsafeLocation();
            }
            Path source = access.sourceDirectory().toRealPath();
            Path candidate = source.resolve(relative).normalize();
            if (!candidate.startsWith(source)) {
                throw unsafeLocation();
            }
            rejectLinkedPath(source, candidate);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("MANIFEST_MISSING", "Scenario manifest was not found");
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate)
                    || !candidate.toRealPath().startsWith(source)) {
                throw unsafeLocation();
            }
            return candidate;
        } catch (EngineWorkspaceAccessException exception) {
            throw exception;
        } catch (PlaywrightManifestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(
                    "UNSAFE_MANIFEST_LOCATION",
                    "Scenario manifest location is invalid");
        }
    }

    private boolean pathContainsTraversal(Path path) {
        for (Path part : path) {
            String value = part.toString();
            if (".".equals(value) || "..".equals(value) || value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private void rejectLinkedPath(Path source, Path candidate) throws IOException {
        Path current = source;
        Path relative = source.relativize(candidate);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw unsafeLocation();
            }
        }
    }

    private byte[] readBounded(Path manifest) {
        try {
            long size = Files.size(manifest);
            if (size <= 0) {
                throw failure("INVALID_MANIFEST", "Scenario manifest is empty");
            }
            if (size > MAX_MANIFEST_BYTES) {
                throw failure("MANIFEST_TOO_LARGE", "Scenario manifest exceeds the size limit");
            }
            try (InputStream input = Files.newInputStream(manifest)) {
                byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) {
                    throw failure(
                            "MANIFEST_TOO_LARGE", "Scenario manifest exceeds the size limit");
                }
                return bytes;
            }
        } catch (PlaywrightManifestException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw failure(
                    "MANIFEST_UNREADABLE",
                    "Scenario manifest could not be read");
        }
    }

    private PlaywrightScenarioManifest parse(byte[] json) {
        try (InputStream input = new ByteArrayInputStream(json)) {
            JsonNode root = objectMapper.readTree(input);
            if (root == null || !root.isObject()) {
                throw invalid("Scenario manifest root must be an object");
            }
            rejectUnknownFields(root, MANIFEST_FIELDS, "manifest");
            String version = requiredText(root, "schemaVersion", "manifest");
            if (!PlaywrightScenarioManifest.SCHEMA_VERSION_1.equals(version)
                    && !PlaywrightScenarioManifest.SCHEMA_VERSION_2.equals(version)) {
                throw failure(
                        "UNSUPPORTED_SCHEMA_VERSION",
                        "Manifest schema version is not supported");
            }
            String name = requiredText(root, "name", "manifest");
            JsonNode scenariosNode = requiredArray(root, "scenarios", "manifest");
            List<PlaywrightScenario> scenarios = new ArrayList<>();
            for (JsonNode scenario : scenariosNode) {
                scenarios.add(parseScenario(scenario, version));
            }
            return new PlaywrightScenarioManifest(version, name, scenarios);
        } catch (PlaywrightManifestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure("MALFORMED_JSON", "Scenario manifest JSON is malformed");
        }
    }

    private PlaywrightScenario parseScenario(JsonNode node, String version) {
        if (!node.isObject()) {
            throw failure("INVALID_SCENARIO", "Manifest scenario must be an object");
        }
        rejectUnknownFields(node, SCENARIO_FIELDS, "scenario");
        String id = requiredText(node, "id", "scenario");
        String name = requiredText(node, "name", "scenario");
        JsonNode stepsNode = requiredArray(node, "steps", "scenario");
        List<PlaywrightStep> steps = new ArrayList<>();
        for (JsonNode step : stepsNode) {
            steps.add(parseStep(step, version));
        }
        return new PlaywrightScenario(id, name, steps);
    }

    private PlaywrightStep parseStep(JsonNode node, String version) {
        if (!node.isObject()) {
            throw failure("INVALID_STEP", "Manifest step must be an object");
        }
        rejectUnknownFields(
                node,
                PlaywrightScenarioManifest.SCHEMA_VERSION_2.equals(version)
                        ? VERSION_2_STEP_FIELDS
                        : STEP_FIELDS,
                "step");
        String id = requiredText(node, "id", "step");
        PlaywrightActionType action =
                PlaywrightActionType.fromManifestValue(requiredText(node, "action", "step"));
        String selector = optionalText(node, "selector", "step");
        String url = optionalText(node, "url", "step");
        String value = optionalText(node, "value", "step");
        String secretRef = optionalText(node, "secretRef", "step");
        String expected = optionalText(node, "expected", "step");
        Duration timeout = optionalTimeout(node);
        return new PlaywrightStep(
                id,
                action,
                selector == null ? null : new PlaywrightSelector(selector),
                url,
                value,
                secretRef,
                expected,
                timeout);
    }

    private Duration optionalTimeout(JsonNode node) {
        JsonNode timeout = node.get("timeoutMs");
        if (timeout == null) {
            return null;
        }
        if (!timeout.isIntegralNumber() || !timeout.canConvertToLong()) {
            throw failure("INVALID_STEP", "Manifest step timeout must be an integer");
        }
        return Duration.ofMillis(timeout.longValue());
    }

    private String requiredText(JsonNode node, String field, String owner) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw failure(
                    codeFor(owner), "Manifest " + owner + " " + field + " must be a string");
        }
        return value.stringValue();
    }

    private String optionalText(JsonNode node, String field, String owner) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            throw failure(
                    codeFor(owner), "Manifest " + owner + " " + field + " must be a string");
        }
        return value.stringValue();
    }

    private JsonNode requiredArray(JsonNode node, String field, String owner) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw failure(
                    codeFor(owner), "Manifest " + owner + " " + field + " must be an array");
        }
        return value;
    }

    private void rejectUnknownFields(JsonNode node, Set<String> allowed, String owner) {
        HashSet<String> unknown = new HashSet<>();
        node.propertyNames().forEach(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw failure(codeFor(owner), "Manifest " + owner + " contains unknown fields");
        }
    }

    private String codeFor(String owner) {
        return switch (owner) {
            case "scenario" -> "INVALID_SCENARIO";
            case "step" -> "INVALID_STEP";
            default -> "INVALID_MANIFEST";
        };
    }

    private PlaywrightManifestException invalid(String message) {
        return failure("INVALID_MANIFEST", message);
    }

    private PlaywrightManifestException unsafeLocation() {
        return failure(
                "UNSAFE_MANIFEST_LOCATION", "Scenario manifest location is invalid");
    }

    private PlaywrightManifestException failure(String code, String message) {
        return new PlaywrightManifestException(code, message);
    }

}
