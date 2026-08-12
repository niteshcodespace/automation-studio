package com.automationstudio.engine.restassured;

import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;
import com.automationstudio.engine.restassured.network.RestAssuredHttpTransport;
import com.automationstudio.engine.sdk.PreparedSourceAccess;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded provider-local header, JSON-path and local JSON Schema assertions. */
final class RestAssuredResponseAssertions {
    static final int MAX_SCHEMA_BYTES = 65_536;
    static final int MAX_JSON_NODES = 10_000;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(RestAssuredApiManifest.MAX_VALUE_LENGTH).build()).build());

    boolean evaluate(PreparedSourceAccess source, RestAssuredHttpTransport.Response response,
            java.util.List<RestAssuredApiManifest.Assertion> assertions) {
        JsonNode responseJson = null;
        for (var assertion : assertions) {
            switch (assertion.type()) {
                case STATUS -> { if (!status(assertion.expected(), response.statusCode())) return false; }
                case HEADER -> {
                    String actual = response.headers().get(assertion.name().toLowerCase(java.util.Locale.ROOT));
                    if (!assertion.expected().equals(actual)) return false;
                }
                case JSON -> {
                    if (responseJson == null) responseJson = parse(response.body(), "RESPONSE_JSON_INVALID");
                    JsonNode selected = path(responseJson, assertion.expression());
                    if ("__exists__".equals(assertion.expected())) { if (selected == null) return false; }
                    else if ("__absent__".equals(assertion.expected())) { if (selected != null) return false; }
                    else if (selected == null || selected.isObject() || selected.isArray()
                            || !assertion.expected().equals(selected.asText())) return false;
                }
                case JSON_SCHEMA -> {
                    if (responseJson == null) responseJson = parse(response.body(), "RESPONSE_JSON_INVALID");
                    JsonNode schema = schema(source, assertion.expression());
                    if (!validate(schema, responseJson, 0)) return false;
                }
            }
        }
        return true;
    }

    private JsonNode schema(PreparedSourceAccess source, String reference) {
        try (var input = source.open(reference)) {
            byte[] bytes = input.readNBytes(MAX_SCHEMA_BYTES + 1);
            if (bytes.length > MAX_SCHEMA_BYTES) throw failure("SCHEMA_TOO_LARGE");
            JsonNode schema = parse(bytes, "SCHEMA_INVALID");
            if (!schema.isObject() || containsRef(schema)) throw failure("SCHEMA_UNSAFE");
            return schema;
        } catch (RestAssuredEngineException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure("SCHEMA_UNREADABLE");
        }
    }

    private JsonNode parse(byte[] bytes, String code) {
        try {
            JsonNode node = mapper.readTree(bytes);
            if (node == null || count(node, 0) > MAX_JSON_NODES) throw failure(code);
            return node;
        } catch (RestAssuredEngineException exception) { throw exception; }
        catch (RuntimeException exception) { throw failure(code); }
    }

    private int count(JsonNode node, int total) {
        if (++total > MAX_JSON_NODES) return total;
        for (JsonNode child : node) total = count(child, total);
        return total;
    }

    private JsonNode path(JsonNode root, String expression) {
        if (!expression.startsWith("$") || expression.length() > RestAssuredApiManifest.MAX_PATH_LENGTH)
            throw failure("JSON_PATH_INVALID");
        JsonNode current = root;
        if (expression.equals("$")) return current;
        for (String part : expression.substring(2).split("\\.")) {
            if (part.isEmpty() || !part.matches("[A-Za-z0-9_-]+")) throw failure("JSON_PATH_INVALID");
            current = current == null ? null : current.get(part);
        }
        return current;
    }

    private boolean validate(JsonNode schema, JsonNode value, int depth) {
        if (depth > 32) throw failure("SCHEMA_INVALID");
        for (String field : schema.propertyNames()) {
            if (!java.util.Set.of("type", "required", "properties").contains(field)) {
                throw failure("SCHEMA_UNSUPPORTED");
            }
        }
        JsonNode type = schema.get("type");
        if (type != null && (!type.isTextual() || !matchesType(type.asText(), value))) return false;
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray() || required.size() > RestAssuredApiManifest.MAX_ENTRIES) throw failure("SCHEMA_INVALID");
            for (JsonNode name : required) if (!name.isTextual() || !value.isObject() || !value.has(name.asText())) return false;
        }
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            if (!properties.isObject() || properties.size() > RestAssuredApiManifest.MAX_ENTRIES) throw failure("SCHEMA_INVALID");
            Iterator<Map.Entry<String, JsonNode>> fields = properties.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isObject()) throw failure("SCHEMA_INVALID");
                if (value.has(field.getKey()) && !validate(field.getValue(), value.get(field.getKey()), depth + 1)) return false;
            }
        }
        return true;
    }

    private boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isTextual(); case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber(); case "boolean" -> value.isBoolean();
            case "null" -> value.isNull(); default -> throw failure("SCHEMA_INVALID");
        };
    }

    private boolean containsRef(JsonNode node) {
        if (node.isObject() && node.has("$ref")) return true;
        for (JsonNode child : node) if (containsRef(child)) return true;
        return false;
    }

    private boolean status(String expected, int actual) {
        if (expected.matches("[1-5][0-9]{2}")) return Integer.parseInt(expected) == actual;
        if (expected.matches("[1-5][0-9]{2}-[1-5][0-9]{2}")) {
            String[] range = expected.split("-");
            return actual >= Integer.parseInt(range[0]) && actual <= Integer.parseInt(range[1]);
        }
        throw failure("INVALID_STATUS_ASSERTION");
    }

    private RestAssuredEngineException failure(String code) {
        return new RestAssuredEngineException(code, "REST Assured response validation failed");
    }
}
