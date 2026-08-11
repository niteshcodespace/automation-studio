package com.automationstudio.engine.restassured.network;

import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;

/** Provider-local seam for deterministic transport security tests. */
@FunctionalInterface
public interface RestAssuredTransport {
    RestAssuredHttpTransport.Response execute(RestAssuredTargetAuthorizer.AuthorizedTarget target,
            RestAssuredApiManifest.Request request, byte[] body);
}
