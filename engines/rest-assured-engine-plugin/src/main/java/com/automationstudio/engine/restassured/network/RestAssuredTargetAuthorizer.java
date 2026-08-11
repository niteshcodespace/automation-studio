package com.automationstudio.engine.restassured.network;

import com.automationstudio.engine.restassured.RestAssuredEngineException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Canonicalizes a target and binds authorization to the exact addresses used by transport. */
public final class RestAssuredTargetAuthorizer {

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final RestAssuredNetworkPolicy policy;
    private final AddressResolver resolver;

    public RestAssuredTargetAuthorizer(RestAssuredNetworkPolicy policy) {
        this(policy, InetAddress::getAllByName);
    }

    public RestAssuredTargetAuthorizer(RestAssuredNetworkPolicy policy, AddressResolver resolver) {
        this.policy = Objects.requireNonNull(policy, "Network policy must not be null");
        this.resolver = Objects.requireNonNull(resolver, "Address resolver must not be null");
    }

    public AuthorizedTarget authorize(String baseUrl, String relativePathAndQuery) {
        try {
            URI base = canonicalBase(new URI(baseUrl));
            URI target = base.resolve(new URI(relativePathAndQuery)).normalize();
            requireSameOrigin(base, target);
            InetAddress[] resolved = resolver.resolve(target.getHost());
            if (resolved.length == 0) deny("TARGET_DNS_DENIED");
            boolean exception = policy.admitsNonGlobal(target);
            if (!exception && Arrays.stream(resolved).anyMatch(address -> !isGlobal(address))) {
                deny("TARGET_ADDRESS_DENIED");
            }
            return new AuthorizedTarget(target, target.getHost(), List.copyOf(Arrays.asList(resolved)));
        } catch (RestAssuredEngineException exception) {
            throw exception;
        } catch (URISyntaxException | UnknownHostException | IllegalArgumentException exception) {
            deny("TARGET_INVALID");
            throw new AssertionError();
        }
    }

    private URI canonicalBase(URI uri) throws URISyntaxException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawQuery() != null || uri.getPort() == 0 || uri.getPort() > 65_535
                || uri.getRawAuthority() == null || uri.getRawAuthority().contains("%")) {
            deny("TARGET_INVALID");
        }
        String asciiHost = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (ambiguousNumericHost(asciiHost)) deny("TARGET_INVALID");
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return new URI(scheme, null, asciiHost, uri.getPort(), path, null, null).normalize();
    }

    private void requireSameOrigin(URI base, URI target) {
        if (target.getRawUserInfo() != null || target.getRawFragment() != null || target.getHost() == null
                || !RestAssuredNetworkPolicy.Origin.from(base).equals(
                        RestAssuredNetworkPolicy.Origin.from(target))) {
            deny("TARGET_ORIGIN_DENIED");
        }
    }

    private boolean ambiguousNumericHost(String host) {
        if (host.matches("[0-9]+") || host.matches("(?i)0x[0-9a-f]+")) return true;
        if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) return true;
            for (String part : parts) {
                if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) return true;
                try { if (Integer.parseInt(part) > 255) return true; }
                catch (NumberFormatException exception) { return true; }
            }
        }
        return false;
    }

    static boolean isGlobal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 0) || (a == 192 && b == 31 && c == 196)
                    || (a == 192 && b == 52 && c == 193) || (a == 192 && b == 88 && c == 99)
                    || (a == 192 && b == 168) || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)
                    || a >= 224);
        }
        if (address instanceof Inet6Address) {
            int a = bytes[0] & 255, b = bytes[1] & 255;
            return ((a & 0xe0) == 0x20) && !((a & 0xfe) == 0xfc
                    || prefix(bytes, new int[] {0x20, 0x01, 0x0d, 0xb8}, 32)
                    || prefix(bytes, new int[] {0x20, 0x01, 0x00, 0x02}, 48)
                    || prefix(bytes, new int[] {0x20, 0x01, 0x00}, 23));
        }
        return false;
    }

    private static boolean prefix(byte[] address, int[] prefix, int bits) {
        int wholeBytes = bits / 8;
        for (int index = 0; index < wholeBytes; index++) {
            if ((address[index] & 255) != prefix[index]) return false;
        }
        int remaining = bits % 8;
        if (remaining == 0) return true;
        int mask = 0xff << (8 - remaining) & 0xff;
        return ((address[wholeBytes] & 255) & mask) == (prefix[wholeBytes] & mask);
    }

    private static void deny(String code) {
        throw new RestAssuredEngineException(code, "REST Assured target is not permitted");
    }

    public record AuthorizedTarget(URI uri, String logicalHost, List<InetAddress> pinnedAddresses) {
        public AuthorizedTarget {
            pinnedAddresses = List.copyOf(pinnedAddresses);
        }
    }
}
