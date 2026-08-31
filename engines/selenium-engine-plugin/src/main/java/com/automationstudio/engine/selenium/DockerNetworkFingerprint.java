package com.automationstudio.engine.selenium;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical verified D2b network identity and daemon-selected IPv4 topology facts. */
record DockerNetworkFingerprint(String networkId, UUID executionId, long acquisitionRevision,
        String attemptNonce, String deadlineCorrelation, String subnet, String gateway,
        Map<String, String> labels) {
    private static final Pattern ID = Pattern.compile("[a-f0-9]{64}");

    DockerNetworkFingerprint {
        if (networkId == null || !ID.matcher(networkId).matches())
            throw new IllegalArgumentException("Invalid immutable network ID");
        Objects.requireNonNull(executionId, "executionId");
        if (acquisitionRevision <= 0) throw new IllegalArgumentException("Invalid revision");
        labels = Map.copyOf(Objects.requireNonNull(labels, "labels"));
        if (!labels.equals(DockerNetworkSpec.expectedLabels(executionId, acquisitionRevision,
                attemptNonce, deadlineCorrelation))) throw new IllegalArgumentException("Label mismatch");
        requireGatewayInSubnet(subnet, gateway);
    }

    private static void requireGatewayInSubnet(String cidr, String gateway) {
        try {
            String[] parts = Objects.requireNonNull(cidr, "subnet").split("/", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid IPv4 subnet");
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("Invalid IPv4 prefix");
            int base = ipv4(parts[0]), candidate = ipv4(Objects.requireNonNull(gateway, "gateway"));
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            if ((base & mask) != base || (candidate & mask) != base)
                throw new IllegalArgumentException("Gateway outside canonical subnet");
        } catch (RuntimeException failure) { throw failure; }
    }

    private static int ipv4(String text) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("Canonical dotted IPv4 required");
        int value = 0;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}"))
                throw new IllegalArgumentException("Canonical dotted IPv4 required");
            int parsed = Integer.parseInt(octet);
            if (parsed > 255) throw new IllegalArgumentException("Invalid IPv4 octet");
            value = value << 8 | parsed;
        }
        return value;
    }
}
