package com.automationstudio.engine.restassured.manifest;

import static com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest.*;

import com.automationstudio.engine.sdk.PreparedSourceAccess;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict, bounded parser. It performs no DNS, network, secret, artifact, or runtime work. */
public final class RestAssuredManifestParser {

    public static final int MAX_MANIFEST_BYTES = 1_048_576;
    public static final int MAX_JSON_DEPTH = 32;
    public static final int MAX_REFERENCE_LENGTH = 512;

    private static final Set<String> MANIFEST_FIELDS =
            Set.of("schemaVersion", "name", "defaults", "scenarios");
    private static final Set<String> DEFAULT_FIELDS = Set.of("headers");
    private static final Set<String> SCENARIO_FIELDS = Set.of("id", "name", "requests");
    private static final Set<String> REQUEST_FIELDS = Set.of("id", "method", "path",
            "pathParameters", "queryParameters", "headers", "body", "authentication",
            "assertions", "retry", "evidence");
    private static final Set<String> BODY_FIELDS = Set.of("inline", "sourceReference", "mediaType");
    private static final Set<String> AUTH_FIELDS =
            Set.of("type", "secretRef", "usernameSecretRef", "placement");
    private static final Set<String> ASSERTION_FIELDS =
            Set.of("type", "name", "expression", "expected");
    private static final Set<String> RETRY_FIELDS = Set.of("maxRetries", "backoffMillis");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("sanitizedSummary");

    private final ObjectMapper mapper;

    public RestAssuredManifestParser() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_JSON_DEPTH)
                        .maxStringLength(MAX_BODY_LENGTH)
                        .build())
                .build();
        mapper = new ObjectMapper(factory);
    }

    public RestAssuredApiManifest load(String reference, PreparedSourceAccess source) {
        Objects.requireNonNull(source, "Prepared source access must not be null");
        validateReference(reference);
        try (InputStream input = source.open(reference)) {
            return parse(readBounded(input));
        } catch (RestAssuredManifestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure("MANIFEST_UNREADABLE", "API manifest could not be read");
        }
    }

    public RestAssuredApiManifest parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw failure("INVALID_MANIFEST", "API manifest is empty");
        }
        if (content.length > MAX_MANIFEST_BYTES) {
            throw failure("MANIFEST_TOO_LARGE", "API manifest exceeds the size limit");
        }
        try (InputStream input = new ByteArrayInputStream(content)) {
            JsonNode root = mapper.readTree(input);
            requireObject(root, MANIFEST_FIELDS, "manifest");
            String version = requiredText(root, "schemaVersion", "manifest");
            if (!SCHEMA_VERSION.equals(version)) {
                throw failure("UNSUPPORTED_SCHEMA_VERSION", "Manifest schema version is not supported");
            }
            return new RestAssuredApiManifest(version, requiredText(root, "name", "manifest"),
                    parseDefaults(root.get("defaults")), parseScenarios(requiredArray(root, "scenarios")));
        } catch (RestAssuredManifestException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure("MALFORMED_JSON", "API manifest JSON is malformed");
        }
    }

    private Defaults parseDefaults(JsonNode node) {
        if (node == null) return new Defaults(Map.of());
        requireObject(node, DEFAULT_FIELDS, "defaults");
        return new Defaults(stringMap(node.get("headers"), "default headers"));
    }

    private List<Scenario> parseScenarios(JsonNode array) {
        if (array.size() == 0 || array.size() > MAX_SCENARIOS) invalid("manifest scenarios");
        List<Scenario> scenarios = new ArrayList<>();
        for (JsonNode node : array) {
            requireObject(node, SCENARIO_FIELDS, "scenario");
            scenarios.add(new Scenario(requiredText(node, "id", "scenario"),
                    requiredText(node, "name", "scenario"),
                    parseRequests(requiredArray(node, "requests"))));
        }
        return scenarios;
    }

    private List<Request> parseRequests(JsonNode array) {
        if (array.size() == 0 || array.size() > MAX_REQUESTS_PER_SCENARIO) invalid("scenario requests");
        List<Request> requests = new ArrayList<>();
        for (JsonNode node : array) {
            requireObject(node, REQUEST_FIELDS, "request");
            requests.add(new Request(requiredText(node, "id", "request"),
                    enumValue(HttpMethod.class, requiredText(node, "method", "request"), "method"),
                    requiredText(node, "path", "request"),
                    stringMap(node.get("pathParameters"), "path parameters"),
                    stringMap(node.get("queryParameters"), "query parameters"),
                    stringMap(node.get("headers"), "headers"), parseBody(node.get("body")),
                    parseAuthentication(node.get("authentication")),
                    parseAssertions(requiredArray(node, "assertions")),
                    parseRetry(node.get("retry")), parseEvidence(node.get("evidence"))));
        }
        return requests;
    }

    private Body parseBody(JsonNode node) {
        if (node == null) return null;
        requireObject(node, BODY_FIELDS, "body");
        return new Body(optionalText(node, "inline", "body"),
                optionalText(node, "sourceReference", "body"), requiredText(node, "mediaType", "body"));
    }

    private Authentication parseAuthentication(JsonNode node) {
        if (node == null) return null;
        requireObject(node, AUTH_FIELDS, "authentication");
        return new Authentication(enumValue(AuthenticationType.class,
                requiredText(node, "type", "authentication"), "authentication type"),
                optionalText(node, "secretRef", "authentication"),
                optionalText(node, "usernameSecretRef", "authentication"),
                optionalText(node, "placement", "authentication"));
    }

    private List<Assertion> parseAssertions(JsonNode array) {
        if (array.size() == 0 || array.size() > MAX_ASSERTIONS) invalid("request assertions");
        List<Assertion> assertions = new ArrayList<>();
        for (JsonNode node : array) {
            requireObject(node, ASSERTION_FIELDS, "assertion");
            assertions.add(new Assertion(enumValue(AssertionType.class,
                    requiredText(node, "type", "assertion"), "assertion type"),
                    optionalText(node, "name", "assertion"),
                    optionalText(node, "expression", "assertion"),
                    optionalText(node, "expected", "assertion")));
        }
        return assertions;
    }

    private Retry parseRetry(JsonNode node) {
        if (node == null) return null;
        requireObject(node, RETRY_FIELDS, "retry");
        return new Retry(requiredInt(node, "maxRetries", "retry"),
                requiredInt(node, "backoffMillis", "retry"));
    }

    private Evidence parseEvidence(JsonNode node) {
        if (node == null) return null;
        requireObject(node, EVIDENCE_FIELDS, "evidence");
        JsonNode value = node.get("sanitizedSummary");
        if (value == null || !value.isBoolean() || !value.booleanValue()) {
            throw failure("INVALID_EVIDENCE", "Evidence declaration is invalid");
        }
        return new Evidence(true);
    }

    private Map<String, String> stringMap(JsonNode node, String location) {
        if (node == null) return Map.of();
        if (!node.isObject() || node.size() > MAX_ENTRIES) invalid(location);
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().isTextual()) invalid(location);
            values.put(field.getKey(), field.getValue().textValue());
        }
        return values;
    }

    private void requireObject(JsonNode node, Set<String> fields, String location) {
        if (node == null || !node.isObject()) invalid(location);
        for (String field : node.propertyNames()) {
            if (!fields.contains(field)) {
                throw failure("UNKNOWN_FIELD", "API manifest contains an unknown " + location + " field");
            }
        }
    }

    private JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) invalid(field);
        return value;
    }

    private String requiredText(JsonNode node, String field, String location) {
        String value = optionalText(node, field, location);
        if (value == null) invalid(location + " " + field);
        return value;
    }

    private String optionalText(JsonNode node, String field, String location) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isTextual()) invalid(location + " " + field);
        return value.textValue();
    }

    private int requiredInt(JsonNode node, String field, String location) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) invalid(location + " " + field);
        return value.intValue();
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value, String location) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw failure("INVALID_ENUM", "API manifest " + location + " is invalid");
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw failure("MANIFEST_TOO_LARGE", "API manifest exceeds the size limit");
        }
        return bytes;
    }

    private void validateReference(String reference) {
        if (reference == null || reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH
                || reference.startsWith("/") || reference.contains("\\") || reference.contains(":")
                || List.of(reference.split("/", -1)).stream()
                        .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw failure("UNSAFE_MANIFEST_LOCATION", "API manifest location is invalid");
        }
    }

    private void invalid(String location) {
        throw failure("INVALID_MANIFEST", "API manifest " + location + " is invalid");
    }

    private RestAssuredManifestException failure(String code, String message) {
        return new RestAssuredManifestException(code, message);
    }
}
