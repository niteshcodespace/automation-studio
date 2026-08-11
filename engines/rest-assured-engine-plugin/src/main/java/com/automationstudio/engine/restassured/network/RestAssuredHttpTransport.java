package com.automationstudio.engine.restassured.network;

import com.automationstudio.engine.restassured.RestAssuredEngineException;
import com.automationstudio.engine.restassured.manifest.RestAssuredApiManifest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.HttpClients;

/** One-request transport with pinned DNS, redirects/proxies disabled, and bounded response decoding. */
public final class RestAssuredHttpTransport implements RestAssuredTransport {
    private final RestAssuredNetworkPolicy policy;

    public RestAssuredHttpTransport(RestAssuredNetworkPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "Network policy must not be null");
    }

    public Response execute(RestAssuredTargetAuthorizer.AuthorizedTarget target,
            RestAssuredApiManifest.Request request, byte[] body) {
        DnsResolver pinned = host -> {
            if (!host.equalsIgnoreCase(target.logicalHost())) throw new java.net.UnknownHostException();
            return target.pinnedAddresses().toArray(java.net.InetAddress[]::new);
        };
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Math.toIntExact(policy.connectTimeout().toMillis()))
                .setSocketTimeout(Math.toIntExact(policy.readTimeout().toMillis()))
                .setConnectionRequestTimeout(Math.toIntExact(policy.connectTimeout().toMillis()))
                .setRedirectsEnabled(false).build();
        HttpUriRequest outbound = build(target, request, body);
        AtomicBoolean deadlineExpired = new AtomicBoolean();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
                var client = HttpClients.custom().setDnsResolver(pinned).disableRedirectHandling()
                .disableContentCompression().disableCookieManagement().setDefaultRequestConfig(config).build()) {
            var deadline = scheduler.schedule(() -> {
                deadlineExpired.set(true);
                outbound.abort();
            }, policy.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
            try (var response = client.execute(outbound)) {
                int status = response.getStatusLine().getStatusCode();
                Map<String, String> headers = new LinkedHashMap<>();
                for (var header : response.getAllHeaders()) {
                    if (headers.size() >= RestAssuredApiManifest.MAX_ENTRIES
                            || header.getName().length() > 128
                            || header.getValue().length() > RestAssuredApiManifest.MAX_VALUE_LENGTH
                            || header.getName().codePoints().anyMatch(Character::isISOControl)
                            || header.getValue().codePoints().anyMatch(Character::isISOControl)) {
                        throw failure("RESPONSE_HEADERS_INVALID");
                    }
                    headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
                }
                byte[] wire = response.getEntity() == null ? new byte[0]
                        : bounded(response.getEntity().getContent(), policy.maximumWireBytes(), "RESPONSE_TOO_LARGE");
                String encoding = headers.get("content-encoding");
                byte[] decoded;
                if (encoding == null || encoding.isBlank() || encoding.equalsIgnoreCase("identity")) decoded = wire;
                else if (encoding.equalsIgnoreCase("gzip")) {
                    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(wire))) {
                        decoded = bounded(gzip, policy.maximumDecompressedBytes(), "RESPONSE_DECOMPRESSION_LIMIT");
                    }
                } else throw failure("UNSUPPORTED_CONTENT_ENCODING");
                return new Response(status, Map.copyOf(headers), decoded);
            } finally {
                deadline.cancel(false);
            }
        } catch (RestAssuredEngineException exception) {
            throw exception;
        } catch (java.net.SocketTimeoutException exception) {
            throw failure("TRANSPORT_TIMEOUT");
        } catch (IOException | RuntimeException exception) {
            throw failure(deadlineExpired.get() ? "TRANSPORT_TIMEOUT" : "TRANSPORT_FAILURE");
        }
    }

    private HttpUriRequest build(RestAssuredTargetAuthorizer.AuthorizedTarget target,
            RestAssuredApiManifest.Request request, byte[] body) {
        RequestBuilder builder = RequestBuilder.create(request.method().name()).setUri(target.uri());
        request.headers().forEach(builder::addHeader);
        if (body != null) {
            builder.setEntity(new ByteArrayEntity(body));
            builder.setHeader("Content-Type", request.body().mediaType());
        }
        return builder.build();
    }

    private static byte[] bounded(InputStream input, int maximum, String code) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        byte[] buffer = new byte[8192];
        int total = 0, read;
        while ((read = input.read(buffer, 0, Math.min(buffer.length, maximum + 1 - total))) != -1) {
            total += read;
            if (total > maximum) throw failure(code);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static RestAssuredEngineException failure(String code) {
        return new RestAssuredEngineException(code, "REST Assured transport failed");
    }

    public record Response(int statusCode, Map<String, String> headers, byte[] body) {
        public Response { headers = Map.copyOf(headers); body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
}
