package com.automationstudio.engine.selenium.manifest;

import com.automationstudio.engine.sdk.PreparedSourceAccess;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict bounded parser with no browser, process, network, secret, or artifact authority. */
public final class SeleniumManifestParser {
    public static final int MAX_MANIFEST_BYTES = 1_048_576;
    public static final int MAX_JSON_DEPTH = 32;
    public static final int MAX_REFERENCE_LENGTH = 512;
    private static final Set<String> MANIFEST_FIELDS = Set.of("schemaVersion", "name", "scenarios");
    private static final Set<String> SCENARIO_FIELDS = Set.of("id", "name", "steps");
    private static final Set<String> STEP_FIELDS = Set.of(
            "id", "action", "selector", "url", "value", "secretRef", "expected", "timeoutMs");
    private final ObjectMapper mapper;

    public SeleniumManifestParser() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_JSON_DEPTH)
                        .maxStringLength(SeleniumStep.MAX_VALUE_LENGTH)
                        .build())
                .build();
        mapper = new ObjectMapper(factory);
    }

    public SeleniumManifest load(String reference, PreparedSourceAccess source) {
        Objects.requireNonNull(source, "Prepared source access must not be null");
        validateReference(reference);
        try (InputStream input = source.open(reference)) {
            return parse(readBounded(input));
        } catch (SeleniumManifestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure("MANIFEST_UNREADABLE", "Selenium manifest could not be read");
        }
    }

    public SeleniumManifest parse(byte[] content) {
        if (content == null || content.length == 0) throw failure("INVALID_MANIFEST", "Selenium manifest is invalid");
        if (content.length > MAX_MANIFEST_BYTES) throw failure("MANIFEST_TOO_LARGE", "Selenium manifest exceeds the size limit");
        try (InputStream input = new ByteArrayInputStream(content)) {
            JsonNode root = mapper.readTree(input);
            requireObject(root, MANIFEST_FIELDS, "manifest");
            return new SeleniumManifest(requiredText(root, "schemaVersion"), requiredText(root, "name"),
                    scenarios(requiredArray(root, "scenarios")));
        } catch (SeleniumManifestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure("MALFORMED_JSON", "Selenium manifest JSON is malformed");
        }
    }

    private List<SeleniumScenario> scenarios(JsonNode array) {
        if (array.isEmpty() || array.size() > SeleniumManifest.MAX_SCENARIOS) invalid("scenario");
        List<SeleniumScenario> values = new ArrayList<>();
        int[] totalSteps = {0};
        for (JsonNode node : array) {
            requireObject(node, SCENARIO_FIELDS, "scenario");
            values.add(new SeleniumScenario(requiredText(node, "id"), requiredText(node, "name"),
                    steps(requiredArray(node, "steps"), totalSteps)));
        }
        return values;
    }

    private List<SeleniumStep> steps(JsonNode array, int[] totalSteps) {
        if (array.isEmpty() || array.size() > SeleniumScenario.MAX_STEPS) invalid("step");
        List<SeleniumStep> values = new ArrayList<>();
        for (JsonNode node : array) {
            if (totalSteps[0] == SeleniumManifest.MAX_TOTAL_STEPS) {
                throw failure("INVALID_MANIFEST", "Selenium manifest is invalid");
            }
            totalSteps[0]++;
            requireObject(node, STEP_FIELDS, "step");
            values.add(new SeleniumStep(requiredText(node, "id"),
                    SeleniumActionType.fromManifestValue(requiredText(node, "action")),
                    optionalText(node, "selector"), optionalText(node, "url"),
                    optionalText(node, "value"), optionalText(node, "secretRef"),
                    optionalText(node, "expected"), optionalInteger(node, "timeoutMs")));
        }
        return values;
    }

    private void requireObject(JsonNode node, Set<String> allowed, String owner) {
        if (node == null || !node.isObject()) invalid(owner);
        for (String field : node.propertyNames()) {
            if (!allowed.contains(field)) {
                throw failure("UNKNOWN_FIELD", "Selenium manifest contains an unknown " + owner + " field");
            }
        }
    }
    private JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) invalid(field);
        return value;
    }
    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) invalid(field);
        return value;
    }
    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isTextual()) invalid(field);
        return value.textValue();
    }
    private Integer optionalInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isInt()) invalid(field);
        return value.intValue();
    }
    private byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        if (bytes.length > MAX_MANIFEST_BYTES) throw failure("MANIFEST_TOO_LARGE", "Selenium manifest exceeds the size limit");
        return bytes;
    }
    private void validateReference(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH
                || reference.startsWith("/") || reference.contains("\\") || reference.contains(":")
                || List.of(reference.split("/", -1)).stream()
                        .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw failure("UNSAFE_MANIFEST_LOCATION", "Selenium manifest location is invalid");
        }
    }
    private void invalid(String owner) { throw failure("INVALID_MANIFEST", "Selenium manifest " + owner + " is invalid"); }
    private SeleniumManifestException failure(String code, String message) { return new SeleniumManifestException(code, message); }
}
