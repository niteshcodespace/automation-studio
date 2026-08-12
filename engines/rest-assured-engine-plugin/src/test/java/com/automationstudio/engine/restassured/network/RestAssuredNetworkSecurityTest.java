package com.automationstudio.engine.restassured.network;

import static org.junit.jupiter.api.Assertions.*;

import com.automationstudio.engine.restassured.RestAssuredEngineException;
import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class RestAssuredNetworkSecurityTest {

    @Test
    void productionPolicyRejectsLoopbackPrivateDocumentationAndAmbiguousHosts() throws Exception {
        var policy = RestAssuredNetworkPolicy.productionDefaults();
        for (String address : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "192.0.0.1", "192.0.2.1",
                "198.51.100.1", "203.0.113.1", "::1", "fc00::1", "100::1", "64:ff9b:1::1",
                "::ffff:10.0.0.1", "2001:db8::1")) {
            var authorizer = new RestAssuredTargetAuthorizer(policy,
                    host -> new InetAddress[] {InetAddress.getByName(address)});
            assertEquals("TARGET_ADDRESS_DENIED", assertThrows(RestAssuredEngineException.class,
                    () -> authorizer.authorize("https://service.example", "/health")).code());
        }
        var authorizer = new RestAssuredTargetAuthorizer(policy);
        assertThrows(RestAssuredEngineException.class,
                () -> authorizer.authorize("http://0177.0.0.1", "/health"));
        assertThrows(RestAssuredEngineException.class,
                () -> authorizer.authorize("http://2130706433", "/health"));
    }

    @Test
    void absoluteDeadlineAbortsAResponseThatNeverProducesHeaders() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(exchange -> {
            try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        })) {
            var policy = new RestAssuredNetworkPolicy(Set.of(new RestAssuredNetworkPolicy.Origin(
                    "http", "localhost", fixture.server.getAddress().getPort())),
                    Duration.ofSeconds(1), Duration.ofMillis(100), 1024, 2048);
            var transport = new RestAssuredHttpTransport(policy);
            assertEquals("TRANSPORT_TIMEOUT", assertThrows(RestAssuredEngineException.class,
                    () -> transport.execute(fixture.authorizer(policy).authorize(fixture.base(), "/blocked"),
                            request(), null)).code());
        } finally {
            release.countDown();
        }
    }

    @Test
    void exactOperatorOriginAdmitsLoopbackAndPinsSingleResolution() throws Exception {
        URI origin = URI.create("http://localhost:18080");
        var policy = policy(Set.of(RestAssuredNetworkPolicy.Origin.from(origin)), 1024, 2048);
        AtomicInteger resolutions = new AtomicInteger();
        var authorizer = new RestAssuredTargetAuthorizer(policy, host -> {
            resolutions.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        });
        var target = authorizer.authorize(origin.toString(), "/safe");

        assertEquals(1, resolutions.get());
        assertEquals("localhost", target.logicalHost());
        assertEquals(InetAddress.getByName("127.0.0.1"), target.pinnedAddresses().getFirst());
        assertThrows(RestAssuredEngineException.class,
                () -> authorizer.authorize("http://localhost:18081", "/safe"));
    }

    @Test
    void rejectsOriginEscapeUserInfoFragmentsAndRedirectsRemainDisabled() throws Exception {
        var policy = policy(Set.of(new RestAssuredNetworkPolicy.Origin("http", "localhost", 18080)),
                1024, 2048);
        var authorizer = new RestAssuredTargetAuthorizer(policy,
                host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        assertThrows(RestAssuredEngineException.class,
                () -> authorizer.authorize("http://user@localhost:18080", "/"));
        assertThrows(RestAssuredEngineException.class,
                () -> authorizer.authorize("http://localhost:18080#fragment", "/"));
        assertThrows(RestAssuredEngineException.class,
                () -> authorizer.authorize("http://localhost:18080", "//other.example/path"));
    }

    @Test
    void transportUsesPinnedLoopbackWithoutFollowingRedirectAndBoundsWireAndDecodedBodies() throws Exception {
        byte[] gzip = gzip("x".repeat(300));
        try (Fixture fixture = new Fixture(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", "/final");
                exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/gzip")) {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, gzip.length);
                exchange.getResponseBody().write(gzip);
            } else {
                byte[] body = "z".repeat(300).getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        })) {
            RestAssuredNetworkPolicy policy = fixture.policy(128, 256);
            var authorizer = fixture.authorizer(policy);
            var transport = new RestAssuredHttpTransport(policy);
            var redirect = transport.execute(authorizer.authorize(fixture.base(), "/redirect"), request(), null);
            assertEquals(302, redirect.statusCode());
            assertEquals("/final", redirect.headers().get("location"));
            assertEquals("RESPONSE_TOO_LARGE", assertThrows(RestAssuredEngineException.class,
                    () -> transport.execute(authorizer.authorize(fixture.base(), "/large"), request(), null)).code());
            assertEquals("RESPONSE_DECOMPRESSION_LIMIT", assertThrows(RestAssuredEngineException.class,
                    () -> transport.execute(authorizer.authorize(fixture.base(), "/gzip"), request(), null)).code());
        }
    }

    @Test
    void responseHeaderValuesAreExplicitlyBounded() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            int length = exchange.getRequestURI().getPath().equals("/boundary")
                    ? RestAssuredApiManifest.MAX_VALUE_LENGTH
                    : RestAssuredApiManifest.MAX_VALUE_LENGTH + 1;
            exchange.getResponseHeaders().add("X-Bounded", "v".repeat(length));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        })) {
            var policy = fixture.policy(1024, 2048);
            var transport = new RestAssuredHttpTransport(policy);
            assertEquals(200, transport.execute(
                    fixture.authorizer(policy).authorize(fixture.base(), "/boundary"), request(), null).statusCode());
            assertEquals("RESPONSE_HEADERS_INVALID", assertThrows(RestAssuredEngineException.class,
                    () -> transport.execute(fixture.authorizer(policy).authorize(fixture.base(), "/headers"),
                            request(), null)).code());
        }
    }

    private static RestAssuredApiManifest.Request request() {
        return new RestAssuredApiManifest.Request("request", RestAssuredApiManifest.HttpMethod.GET, "/",
                Map.of(), Map.of(), Map.of(), null,
                new RestAssuredApiManifest.Authentication(RestAssuredApiManifest.AuthenticationType.NONE,
                        null, null, null),
                List.of(new RestAssuredApiManifest.Assertion(
                        RestAssuredApiManifest.AssertionType.STATUS, null, null, "200")),
                new RestAssuredApiManifest.Retry(0, 0), null, new RestAssuredApiManifest.Evidence(true));
    }

    private static RestAssuredNetworkPolicy policy(Set<RestAssuredNetworkPolicy.Origin> origins,
            int wire, int decoded) {
        return new RestAssuredNetworkPolicy(origins, Duration.ofSeconds(1), Duration.ofSeconds(1), wire, decoded);
    }

    private static byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(value.getBytes()); }
        return output.toByteArray();
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private Fixture(com.sun.net.httpserver.HttpHandler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", handler);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }
        String base() { return "http://localhost:" + server.getAddress().getPort(); }
        RestAssuredNetworkPolicy policy(int wire, int decoded) {
            return RestAssuredNetworkSecurityTest.policy(Set.of(
                    new RestAssuredNetworkPolicy.Origin("http", "localhost", server.getAddress().getPort())),
                    wire, decoded);
        }
        RestAssuredTargetAuthorizer authorizer(RestAssuredNetworkPolicy policy) {
            return new RestAssuredTargetAuthorizer(policy,
                    host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
        }
        @Override public void close() { server.stop(0); }
    }
}
