package com.automationstudio.engine.restassured.manifest;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable version-one REST API configuration; it contains references, never resolved secrets. */
public record RestAssuredApiManifest(
        String schemaVersion, String name, Defaults defaults, List<Scenario> scenarios) {

    public static final String SCHEMA_VERSION = "1";
    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_PATH_LENGTH = 2_048;
    public static final int MAX_VALUE_LENGTH = 4_096;
    public static final int MAX_BODY_LENGTH = 65_536;
    public static final int MAX_SCENARIOS = 100;
    public static final int MAX_REQUESTS_PER_SCENARIO = 100;
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_ASSERTIONS = 64;
    public static final int MAX_RETRIES = 3;
    public static final int MAX_BACKOFF_MILLIS = 30_000;
    public static final int MAX_CORRELATION_HEADER_LENGTH = 128;

    public RestAssuredApiManifest {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalid("UNSUPPORTED_SCHEMA_VERSION", "Manifest schema version is not supported");
        }
        name = text(name, MAX_NAME_LENGTH, "Manifest name is invalid");
        defaults = defaults == null ? new Defaults(Map.of()) : defaults;
        scenarios = List.copyOf(requiredList(
                scenarios, MAX_SCENARIOS, "Manifest scenarios are invalid"));
        unique(scenarios.stream().map(Scenario::id).toList(), "Manifest scenario ids are duplicated");
    }

    public record Defaults(Map<String, String> headers) {
        public Defaults {
            headers = safeHeaders(headers);
        }
    }

    public record Scenario(String id, String name, List<Request> requests) {
        public Scenario {
            id = identifier(id, "Scenario id is invalid");
            name = text(name, MAX_NAME_LENGTH, "Scenario name is invalid");
            requests = List.copyOf(requiredList(
                    requests, MAX_REQUESTS_PER_SCENARIO, "Scenario requests are invalid"));
            unique(requests.stream().map(Request::id).toList(), "Scenario request ids are duplicated");
        }
    }

    public record Request(
            String id,
            HttpMethod method,
            String path,
            Map<String, String> pathParameters,
            Map<String, String> queryParameters,
            Map<String, String> headers,
            Body body,
            Authentication authentication,
            List<Assertion> assertions,
            Retry retry,
            String correlationHeader,
            Evidence evidence) {
        public Request {
            id = identifier(id, "Request id is invalid");
            if (method == null) throw invalid("INVALID_REQUEST", "Request method is invalid");
            path = relativePath(path);
            pathParameters = safeMap(pathParameters, "Path parameters are invalid");
            queryParameters = safeParameters(queryParameters, "Query parameters are invalid");
            headers = safeHeaders(headers);
            authentication = authentication == null
                    ? new Authentication(AuthenticationType.NONE, null, null, null) : authentication;
            if (authentication.type() == AuthenticationType.API_KEY_HEADER
                    && containsHeader(headers, authentication.placement())) {
                throw invalid("INVALID_HEADERS", "Request headers conflict with authentication");
            }
            if (authentication.type() == AuthenticationType.API_KEY_QUERY
                    && queryParameters.containsKey(authentication.placement())) {
                throw invalid("INVALID_PARAMETERS", "Query parameters conflict with authentication");
            }
            assertions = List.copyOf(requiredList(
                    assertions, MAX_ASSERTIONS, "Request assertions are invalid"));
            retry = retry == null ? new Retry(0, 0) : retry;
            correlationHeader = optionalText(correlationHeader, MAX_CORRELATION_HEADER_LENGTH,
                    "Correlation header is invalid");
            if (correlationHeader != null) {
                safeHeaders(Map.of(correlationHeader, "placeholder"));
                if (containsHeader(headers, correlationHeader)
                        || (authentication.type() == AuthenticationType.API_KEY_HEADER
                        && correlationHeader.equalsIgnoreCase(authentication.placement()))) {
                    throw invalid("INVALID_CORRELATION", "Correlation header conflicts with request configuration");
                }
            }
            evidence = evidence == null ? new Evidence(true) : evidence;
        }
    }

    public record Body(String inline, String sourceReference, String mediaType) {
        public Body {
            if ((inline == null) == (sourceReference == null)) {
                throw invalid("INVALID_BODY", "Request body must select exactly one content source");
            }
            if (inline != null) inline = text(inline, MAX_BODY_LENGTH, "Inline request body is invalid");
            if (sourceReference != null) {
                sourceReference = relativeReference(sourceReference, "Request body reference is invalid");
            }
            mediaType = text(mediaType, 128, "Request body media type is invalid");
        }
    }

    public record Authentication(
            AuthenticationType type, String secretRef, String usernameSecretRef, String placement) {
        public Authentication {
            if (type == null) throw invalid("INVALID_AUTHENTICATION", "Authentication type is invalid");
            secretRef = optionalIdentifier(secretRef, "Authentication secret reference is invalid");
            usernameSecretRef = optionalIdentifier(
                    usernameSecretRef, "Authentication username reference is invalid");
            placement = optionalText(placement, 128, "Authentication placement is invalid");
            switch (type) {
                case NONE -> require(secretRef == null && usernameSecretRef == null && placement == null);
                case BEARER -> require(secretRef != null && usernameSecretRef == null && placement == null);
                case BASIC -> require(secretRef != null && usernameSecretRef != null && placement == null);
                case API_KEY_HEADER -> {
                    require(secretRef != null && usernameSecretRef == null && placement != null);
                    safeHeaders(Map.of(placement, "placeholder"));
                }
                case API_KEY_QUERY -> {
                    require(secretRef != null && usernameSecretRef == null && placement != null);
                    identifier(placement, "Authentication placement is invalid");
                }
            }
        }

        private static void require(boolean valid) {
            if (!valid) throw invalid("INVALID_AUTHENTICATION", "Authentication declaration is invalid");
        }
    }

    public record Assertion(AssertionType type, String name, String expression, String expected) {
        public Assertion {
            if (type == null) throw invalid("INVALID_ASSERTION", "Assertion type is invalid");
            name = optionalText(name, 128, "Assertion name is invalid");
            expression = optionalText(expression, MAX_VALUE_LENGTH, "Assertion expression is invalid");
            expected = optionalText(expected, MAX_VALUE_LENGTH, "Assertion expected value is invalid");
            switch (type) {
                case STATUS -> {
                    if (expected == null || name != null || expression != null) invalidAssertion();
                }
                case HEADER -> {
                    if (name == null || expected == null || expression != null) invalidAssertion();
                }
                case JSON -> {
                    if (expression == null || expected == null || name != null) invalidAssertion();
                }
                case JSON_SCHEMA -> {
                    if (expression == null || expected != null || name != null) invalidAssertion();
                    expression = relativeReference(expression, "JSON schema reference is invalid");
                }
            }
        }

        private static void invalidAssertion() {
            throw invalid("INVALID_ASSERTION", "Assertion declaration is invalid");
        }
    }

    public record Retry(int maxRetries, int backoffMillis) {
        public Retry {
            if (maxRetries < 0 || maxRetries > MAX_RETRIES || backoffMillis < 0
                    || backoffMillis > MAX_BACKOFF_MILLIS
                    || (maxRetries == 0 && backoffMillis != 0)) {
                throw invalid("INVALID_RETRY", "Retry declaration is invalid");
            }
        }
    }

    public record Evidence(boolean sanitizedSummary) {
        public Evidence {
            if (!sanitizedSummary) {
                throw invalid("INVALID_EVIDENCE", "Evidence declaration is invalid");
            }
        }
    }

    public enum HttpMethod { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS }
    public enum AuthenticationType { NONE, BEARER, BASIC, API_KEY_HEADER, API_KEY_QUERY }
    public enum AssertionType { STATUS, HEADER, JSON, JSON_SCHEMA }

    private static <T> List<T> requiredList(List<T> values, int maximum, String message) {
        if (values == null || values.isEmpty() || values.size() > maximum
                || values.stream().anyMatch(value -> value == null)) {
            throw invalid("INVALID_MANIFEST", message);
        }
        return values;
    }

    private static Map<String, String> safeMap(Map<String, String> values, String message) {
        if (values == null) return Map.of();
        if (values.size() > MAX_ENTRIES) throw invalid("INVALID_REQUEST", message);
        for (var entry : values.entrySet()) {
            identifier(entry.getKey(), message);
            text(entry.getValue(), MAX_VALUE_LENGTH, message);
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> safeHeaders(Map<String, String> values) {
        Map<String, String> headers = safeMap(values, "Request headers are invalid");
        Set<String> prohibited = Set.of("authorization", "proxy-authorization", "cookie",
                "set-cookie", "host", "content-length", "transfer-encoding", "te", "trailer",
                "connection", "proxy-connection", "keep-alive", "upgrade", "forwarded");
        if (headers.keySet().stream().map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> prohibited.contains(name) || name.startsWith("x-forwarded-")
                        || credentialLikeName(name))) {
            throw invalid("INVALID_HEADERS", "Request headers contain a prohibited name");
        }
        headers.forEach((name, value) -> {
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || containsControl(value)) {
                throw invalid("INVALID_HEADERS", "Request headers are invalid");
            }
        });
        return headers;
    }

    private static boolean containsHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(header -> header.equalsIgnoreCase(name));
    }

    private static Map<String, String> safeParameters(Map<String, String> values, String message) {
        Map<String, String> parameters = safeMap(values, message);
        if (parameters.keySet().stream().anyMatch(RestAssuredApiManifest::credentialLikeName)) {
            throw invalid("INVALID_PARAMETERS", message);
        }
        return parameters;
    }

    private static boolean credentialLikeName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("authorization") || normalized.contains("credential")
                || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("secret") || normalized.contains("apikey")
                || normalized.contains("accesstoken") || normalized.contains("refreshtoken")
                || normalized.equals("token") || normalized.contains("sessionid")
                || normalized.contains("cookie") || normalized.contains("bearer")
                || normalized.equals("jwt");
    }

    private static String relativePath(String value) {
        String path = text(value, MAX_PATH_LENGTH, "Request path is invalid");
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("#") || path.contains("?")
                || path.contains("://") || path.contains("\\")) {
            throw invalid("INVALID_REQUEST", "Request path is invalid");
        }
        return path;
    }

    private static String relativeReference(String value, String message) {
        String reference = text(value, MAX_PATH_LENGTH, message);
        if (reference.startsWith("/") || reference.contains("\\") || reference.contains(":")
                || List.of(reference.split("/", -1)).stream()
                        .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw invalid("INVALID_REFERENCE", message);
        }
        return reference;
    }

    private static String identifier(String value, String message) {
        String identifier = text(value, MAX_ID_LENGTH, message);
        if (!identifier.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            throw invalid("INVALID_IDENTIFIER", message);
        }
        return identifier;
    }

    private static String optionalIdentifier(String value, String message) {
        return value == null ? null : identifier(value, message);
    }

    private static String optionalText(String value, int maximum, String message) {
        return value == null ? null : text(value, maximum, message);
    }

    private static String text(String value, int maximum, String message) {
        if (value == null || value.isBlank() || value.length() > maximum || containsControl(value)) {
            throw invalid("INVALID_VALUE", message);
        }
        return value;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static void unique(List<String> values, String message) {
        if (Set.copyOf(values).size() != values.size()) {
            throw invalid("DUPLICATE_IDENTIFIER", message);
        }
    }

    private static RestAssuredManifestException invalid(String code, String message) {
        return new RestAssuredManifestException(code, message);
    }
}
