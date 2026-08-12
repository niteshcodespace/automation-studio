package com.automationstudio.engine.restassured.network;

import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;
import java.util.Map;

/** Provider-local seam for deterministic transport security tests. */
@FunctionalInterface
public interface RestAssuredTransport {
    RestAssuredHttpTransport.Response execute(RestAssuredTargetAuthorizer.AuthorizedTarget target,
            RestAssuredApiManifest.Request request, byte[] body, Map<String, String> authentication);

    default RestAssuredHttpTransport.Response execute(RestAssuredTargetAuthorizer.AuthorizedTarget target,
            RestAssuredApiManifest.Request request, byte[] body) {
        return execute(target, request, body, Map.of());
    }
}
