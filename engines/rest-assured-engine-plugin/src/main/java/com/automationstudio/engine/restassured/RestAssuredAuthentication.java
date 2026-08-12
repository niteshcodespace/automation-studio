package com.automationstudio.engine.restassured;

import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;
import com.automationstudio.engine.restassured.network.RestAssuredTargetAuthorizer;
import com.automationstudio.engine.sdk.ExecutionSecretAccess;
import com.automationstudio.engine.sdk.ResolvedSecret;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Invocation-local authentication material, created only after target authorization. */
final class RestAssuredAuthentication implements AutoCloseable {
    private final List<ResolvedSecret> secrets = new ArrayList<>();
    private final RestAssuredTargetAuthorizer.AuthorizedTarget target;
    private final Map<String, String> headers;

    private RestAssuredAuthentication(RestAssuredTargetAuthorizer.AuthorizedTarget target,
            Map<String, String> headers) {
        this.target = target;
        this.headers = Map.copyOf(headers);
    }

    static RestAssuredAuthentication materialize(RestAssuredApiManifest.Authentication configuration,
            ExecutionSecretAccess access, RestAssuredTargetAuthorizer.AuthorizedTarget authorized) {
        var material = new Builder(access, authorized);
        try {
            return material.build(configuration);
        } catch (RuntimeException exception) {
            material.close();
            if (exception instanceof RestAssuredEngineException engineException) throw engineException;
            throw failure("SECRET_RESOLUTION_FAILED");
        }
    }

    RestAssuredTargetAuthorizer.AuthorizedTarget target() { return target; }
    Map<String, String> headers() { return headers; }

    @Override public void close() {
        for (int index = secrets.size() - 1; index >= 0; index--) secrets.get(index).close();
    }

    private static final class Builder {
        private final ExecutionSecretAccess access;
        private RestAssuredTargetAuthorizer.AuthorizedTarget target;
        private final List<ResolvedSecret> secrets = new ArrayList<>();
        private final Map<String, String> headers = new LinkedHashMap<>();

        Builder(ExecutionSecretAccess access, RestAssuredTargetAuthorizer.AuthorizedTarget target) {
            this.access = access;
            this.target = target;
        }

        RestAssuredAuthentication build(RestAssuredApiManifest.Authentication authentication) {
            switch (authentication.type()) {
                case NONE -> { }
                case BEARER -> headers.put("Authorization", "Bearer " + value(authentication.secretRef()));
                case BASIC -> {
                    String username = value(authentication.usernameSecretRef());
                    String password = value(authentication.secretRef());
                    String encoded = Base64.getEncoder().encodeToString(
                            (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                    headers.put("Authorization", "Basic " + encoded);
                }
                case API_KEY_HEADER -> headers.put(authentication.placement(), value(authentication.secretRef()));
                case API_KEY_QUERY -> appendQuery(authentication.placement(), value(authentication.secretRef()));
            }
            var result = new RestAssuredAuthentication(target, headers);
            result.secrets.addAll(secrets);
            return result;
        }

        String value(String reference) {
            ResolvedSecret secret = access.resolve(reference);
            if (secret == null) throw failure("SECRET_RESOLUTION_FAILED");
            secrets.add(secret);
            final String[] value = new String[1];
            secret.withValue(chars -> value[0] = new String(chars));
            if (value[0].isEmpty() || value[0].length() > RestAssuredApiManifest.MAX_VALUE_LENGTH
                    || value[0].chars().anyMatch(Character::isISOControl)) {
                throw failure("SECRET_VALUE_INVALID");
            }
            return value[0];
        }

        void appendQuery(String name, String value) {
            try {
                URI uri = target.uri();
                String pair = encode(name) + "=" + encode(value);
                String query = uri.getRawQuery() == null ? pair : uri.getRawQuery() + "&" + pair;
                URI updated = new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), query, null);
                target = new RestAssuredTargetAuthorizer.AuthorizedTarget(
                        updated, target.logicalHost(), target.pinnedAddresses());
            } catch (Exception exception) {
                throw failure("AUTHENTICATION_INVALID");
            }
        }

        void close() { for (ResolvedSecret secret : secrets) secret.close(); }
        String encode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    private static RestAssuredEngineException failure(String code) {
        return new RestAssuredEngineException(code, "REST Assured authentication failed");
    }
}
