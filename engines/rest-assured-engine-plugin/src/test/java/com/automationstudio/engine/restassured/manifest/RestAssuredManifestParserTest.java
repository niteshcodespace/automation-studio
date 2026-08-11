package com.automationstudio.engine.restassured.manifest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.junit.jupiter.api.Test;

public class RestAssuredManifestParserTest {

    private final RestAssuredManifestParser parser = new RestAssuredManifestParser();

    @Test
    void parsesCompleteVersionOneManifest() {
        RestAssuredApiManifest manifest = parse(validManifest());

        assertEquals("1", manifest.schemaVersion());
        assertEquals("api-contract", manifest.name());
        assertEquals("scenario-one", manifest.scenarios().getFirst().id());
        var request = manifest.scenarios().getFirst().requests().getFirst();
        assertEquals(RestAssuredApiManifest.HttpMethod.GET, request.method());
        assertEquals(RestAssuredApiManifest.AuthenticationType.NONE,
                request.authentication().type());
        assertTrue(request.evidence().sanitizedSummary());
    }

    @Test
    void rejectsUnsupportedVersionMissingFieldsNullBlankUnknownAndInvalidEnum() {
        assertCode("UNSUPPORTED_SCHEMA_VERSION", validManifest().replace("\"1\"", "\"2\""));
        assertFailure(validManifest().replace("\"name\":\"api-contract\",", ""));
        assertFailure(validManifest().replace("\"name\":\"api-contract\"", "\"name\":null"));
        assertFailure(validManifest().replace("\"name\":\"api-contract\"", "\"name\":\" \""));
        assertCode("UNKNOWN_FIELD", validManifest().replace("\"name\":\"api-contract\"",
                "\"name\":\"api-contract\",\"unexpected\":true"));
        assertCode("INVALID_ENUM", validManifest().replace("\"method\":\"GET\"", "\"method\":\"TRACE\""));
        assertCode("MALFORMED_JSON", "{not-json");
        assertCode("MALFORMED_JSON", validManifest().replaceFirst(
                "\"name\":\"api-contract\"", "\"name\":\"api-contract\",\"name\":\"duplicate\""));
    }

    @Test
    void enforcesStringCollectionAndIdentifierBounds() {
        assertFailure(validManifest().replace("api-contract", "a".repeat(257)));
        String scenarios = "[" + String.join(",", java.util.Collections.nCopies(101,
                "{\"id\":\"s\",\"name\":\"s\",\"requests\":[]}")) + "]";
        assertFailure(validManifest().replaceFirst("\"scenarios\":\\[.*", "\"scenarios\":" + scenarios + "}"));
        assertCode("DUPLICATE_IDENTIFIER", validManifest().replace(
                "}]}", "},{\"id\":\"scenario-one\",\"name\":\"duplicate\",\"requests\":["
                        + requestJson() + "]}]}"));
    }

    @Test
    void defensivelyCopiesConfigurationCollections() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        var defaults = new RestAssuredApiManifest.Defaults(headers);
        headers.put("Changed", "value");

        assertEquals(Map.of("Accept", "application/json"), defaults.headers());
        assertThrows(UnsupportedOperationException.class,
                () -> defaults.headers().put("Another", "value"));

        var requests = new java.util.ArrayList<RestAssuredApiManifest.Request>();
        requests.add(parse(validManifest()).scenarios().getFirst().requests().getFirst());
        var scenario = new RestAssuredApiManifest.Scenario("isolated", "Isolated", requests);
        requests.clear();
        assertEquals(1, scenario.requests().size());
    }

    @Test
    void rejectsCredentialAndFramingHeadersAndUnsafeReferences() {
        assertCode("INVALID_HEADERS", validManifest().replace(
                "\"X-Client\":\"contract\"", "\"Authorization\":\"literal\""));
        assertCode("INVALID_HEADERS", validManifest().replace(
                "\"X-Client\":\"contract\"", "\"Transfer-Encoding\":\"chunked\""));
        assertCode("INVALID_HEADERS", validManifest().replace(
                "\"X-Client\":\"contract\"", "\"Proxy-Connection\":\"keep-alive\""));
        assertCode("INVALID_HEADERS", validManifest().replace(
                "\"X-Client\":\"contract\"", "\"X-Forwarded-Host\":\"internal\""));
        assertCode("INVALID_HEADERS", validManifest().replace(
                "\"X-Client\":\"contract\"", "\"X-API-Key\":\"literal\""));
        assertCode("INVALID_PARAMETERS", validManifest().replace(
                "\"view\":\"summary\"", "\"api_key\":\"literal\""));
        assertCode("INVALID_HEADERS", validManifest().replace(
                "\"Accept\":\"application/json\"", "\"Cookie\":\"literal\""));
        assertCode("INVALID_HEADERS", validManifest().replace(
                "\"type\":\"NONE\"",
                "\"type\":\"API_KEY_HEADER\",\"secretRef\":\"api-token\",\"placement\":\"Authorization\""));
        assertCode("INVALID_HEADERS", validManifest()
                .replace("\"X-Client\":\"contract\"", "\"X-Key\":\"literal\"")
                .replace("\"type\":\"NONE\"",
                        "\"type\":\"API_KEY_HEADER\",\"secretRef\":\"api-token\",\"placement\":\"x-key\""));
        assertCode("INVALID_PARAMETERS", validManifest()
                .replace("\"view\":\"summary\"", "\"clientkey\":\"literal\"")
                .replace("\"type\":\"NONE\"",
                        "\"type\":\"API_KEY_QUERY\",\"secretRef\":\"api-token\",\"placement\":\"clientkey\""));
        assertFailure(validManifest().replace("\"/users/{id}\"", "\"https://internal.example/\""));
        assertFailure(validManifest().replace("\"/users/{id}\"", "\"/safe?api_key=literal\""));
        assertFailure(validManifest().replace("\"headers\":{\"X-Client\":\"contract\"}",
                "\"headers\":{\"X-Client\":\"contract\"},"
                        + "\"body\":{\"sourceReference\":\"../secret.json\","
                        + "\"mediaType\":\"application/json\"}"));
    }

    @Test
    void prohibitedHeaderNormalizationIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertCode("INVALID_HEADERS", validManifest().replace(
                    "\"X-Client\":\"contract\"", "\"AUTHORIZATION\":\"literal\""));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void evidenceMustRemainSanitizedByConstruction() {
        assertThrows(RestAssuredManifestException.class,
                () -> new RestAssuredApiManifest.Evidence(false));
    }

    @Test
    void failuresAreSanitizedAndDoNotRetainCauseOrInput() {
        String sensitive = "literal-sensitive-value";
        RestAssuredManifestException failure = assertThrows(RestAssuredManifestException.class,
                () -> parse(validManifest().replace("GET", sensitive)));

        assertFalse(failure.getMessage().contains(sensitive));
        assertNull(failure.getCause());
        assertEquals(0, failure.getStackTrace().length);
    }

    private RestAssuredApiManifest parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private void assertFailure(String json) {
        assertThrows(RestAssuredManifestException.class, () -> parse(json));
    }

    private void assertCode(String code, String json) {
        assertEquals(code, assertThrows(RestAssuredManifestException.class, () -> parse(json)).code());
    }

    public static String validManifest() {
        return """
                {"schemaVersion":"1","name":"api-contract","defaults":{"headers":{"Accept":"application/json"}},
                 "scenarios":[{"id":"scenario-one","name":"User lookup","requests":[%s]}]}
                """.formatted(requestJson());
    }

    private static String requestJson() {
        return """
                {"id":"get-user","method":"GET","path":"/users/{id}",
                 "pathParameters":{"id":"42"},"queryParameters":{"view":"summary"},
                 "headers":{"X-Client":"contract"},
                 "authentication":{"type":"NONE"},
                 "assertions":[{"type":"STATUS","expected":"200"}],
                 "retry":{"maxRetries":0,"backoffMillis":0},
                 "evidence":{"sanitizedSummary":true}}
                """;
    }
}
