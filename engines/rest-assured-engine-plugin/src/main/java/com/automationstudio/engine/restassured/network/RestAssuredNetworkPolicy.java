package com.automationstudio.engine.restassured.network;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable runner-owned outbound policy. Manifest and environment data cannot alter it. */
public record RestAssuredNetworkPolicy(
        Set<Origin> admittedNonGlobalOrigins,
        Duration connectTimeout,
        Duration readTimeout,
        int maximumWireBytes,
        int maximumDecompressedBytes) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAXIMUM_WIRE_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_MAXIMUM_DECOMPRESSED_BYTES = 4 * 1024 * 1024;

    public RestAssuredNetworkPolicy {
        admittedNonGlobalOrigins = Set.copyOf(Objects.requireNonNull(
                admittedNonGlobalOrigins, "Admitted origins must not be null"));
        connectTimeout = bounded(connectTimeout, "Connect timeout");
        readTimeout = bounded(readTimeout, "Read timeout");
        if (maximumWireBytes < 1 || maximumWireBytes > 16 * 1024 * 1024
                || maximumDecompressedBytes < 1 || maximumDecompressedBytes > 32 * 1024 * 1024
                || maximumDecompressedBytes < maximumWireBytes) {
            throw new IllegalArgumentException("Response limits are invalid");
        }
    }

    public static RestAssuredNetworkPolicy productionDefaults() {
        return new RestAssuredNetworkPolicy(Set.of(), DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT,
                DEFAULT_MAXIMUM_WIRE_BYTES, DEFAULT_MAXIMUM_DECOMPRESSED_BYTES);
    }

    public boolean admitsNonGlobal(URI uri) {
        return admittedNonGlobalOrigins.contains(Origin.from(uri));
    }

    private static Duration bounded(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    public record Origin(String scheme, String host, int port) {
        public Origin {
            scheme = Objects.requireNonNull(scheme, "Scheme must not be null").toLowerCase(Locale.ROOT);
            host = Objects.requireNonNull(host, "Host must not be null").toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || host.isBlank()
                    || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Origin is invalid");
            }
        }

        public static Origin from(URI uri) {
            int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80)
                    : uri.getPort();
            return new Origin(uri.getScheme(), uri.getHost(), port);
        }
    }
}
